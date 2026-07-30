package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedLLMProviderTest {

    @Test
    fun `complete snapshots provider model and retries only retryable failures`() = runBlocking {
        var config = LLMConfig(
            activeProvider = "OpenAI",
            activeModel = "gpt-4o",
            selectedModels = mapOf("OpenAI" to "gpt-4-turbo"),
            apiKeys = mapOf("OpenAI" to "test-key")
        )
        val seenModels = mutableListOf<String?>()
        var attempts = 0
        var clock = 0L
        val delays = mutableListOf<Long>()
        val delegate = FakeProvider("OpenAI", completion = { request ->
            attempts++
            seenModels += request.model
            if (attempts == 1) {
                config = config.copy(selectedModels = mapOf("OpenAI" to "o3-mini"))
                throw LLMException(
                    error = LLMError.ServerError,
                    provider = "OpenAI",
                    model = request.model.orEmpty(),
                    status = 503,
                    retryable = true
                )
            }
            response(request)
        })
        val wrapper = WrappedLLMProvider(
            delegate = delegate,
            configProvider = { config },
            retryRuntime = RetryRuntime(
                nowMillis = { clock },
                delayMillis = { delay -> delays += delay; clock += delay },
                jitterMillis = { upperBound -> upperBound }
            )
        )

        wrapper.complete(request())

        assertEquals(2, attempts)
        assertEquals(listOf("gpt-4-turbo", "gpt-4-turbo"), seenModels)
        assertEquals(listOf(750L), delays)
    }

    @Test
    fun `none policy performs exactly one attempt`() = runBlocking {
        var attempts = 0
        val delegate = FakeProvider("OpenAI", completion = {
            attempts++
            throw LLMException(
                error = LLMError.RateLimited,
                provider = "OpenAI",
                model = "gpt-4o",
                status = 429,
                retryable = true
            )
        })
        val wrapper = wrapper(delegate)

        runCatching { wrapper.complete(request().copy(retryPolicy = RetryPolicy.NONE)) }

        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation is never classified or retried`() = runBlocking {
        var attempts = 0
        val delegate = FakeProvider("OpenAI", completion = {
            attempts++
            throw CancellationException("stop")
        })
        val wrapper = wrapper(delegate)

        val failure = runCatching { wrapper.complete(request()) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, attempts)
    }

    @Test
    fun `stream retries before emission but never after a partial response`() = runBlocking {
        var preEmissionAttempts = 0
        val preEmission = FakeProvider(
            name = "OpenAI",
            stream = {
                preEmissionAttempts++
                flow {
                    if (preEmissionAttempts == 1) {
                        throw LLMException(
                            error = LLMError.Network,
                            provider = "OpenAI",
                            model = "gpt-4o",
                            retryable = true
                        )
                    }
                    emit("ok")
                }
            }
        )
        val retried = wrapper(preEmission).streamComplete(request()).toList()

        var partialAttempts = 0
        val partial = FakeProvider(
            name = "OpenAI",
            stream = {
                partialAttempts++
                flow {
                    emit("partial")
                    throw LLMException(
                        error = LLMError.Network,
                        provider = "OpenAI",
                        model = "gpt-4o",
                        retryable = true
                    )
                }
            }
        )
        val partialFailure = runCatching {
            wrapper(partial).streamComplete(request()).toList()
        }.exceptionOrNull()

        assertEquals(listOf("ok"), retried)
        assertEquals(2, preEmissionAttempts)
        assertTrue(partialFailure is LLMException)
        assertEquals(1, partialAttempts)
    }

    @Test
    fun `custom provider cannot inherit an implicit OpenAI endpoint`() = runBlocking {
        var called = false
        val delegate = FakeProvider("Custom OpenAI Compatible", completion = {
            called = true
            response(it)
        })
        val wrapper = WrappedLLMProvider(
            delegate = delegate,
            configProvider = {
                LLMConfig(
                    activeProvider = "Custom OpenAI Compatible",
                    apiKeys = mapOf("Custom OpenAI Compatible" to "test-key"),
                    customEndpoints = emptyMap()
                )
            }
        )

        val failure = runCatching { wrapper.complete(request()) }.exceptionOrNull()

        assertTrue(failure is LLMException)
        assertEquals(LLMError.RequestInvalid, (failure as LLMException).error)
        assertFalse(called)
    }

    @Test
    fun `non-active provider complete uses that provider selected model`() = runBlocking {
        val seen = mutableListOf<String?>()
        val delegate = FakeProvider("Google Gemini", completion = { req ->
            seen += req.model
            response(req)
        })
        val wrapper = WrappedLLMProvider(
            delegate = delegate,
            configProvider = {
                LLMConfig(
                    activeProvider = "OpenAI",
                    activeModel = "gpt-4o",
                    selectedModels = mapOf(
                        "OpenAI" to "gpt-4o",
                        "Google Gemini" to "gemini-1.5-pro"
                    ),
                    apiKeys = mapOf("Google Gemini" to "gemini-key")
                )
            }
        )

        wrapper.complete(request())

        assertEquals(listOf("gemini-1.5-pro"), seen)
    }

    private fun wrapper(delegate: LLMProvider) = WrappedLLMProvider(
        delegate = delegate,
        configProvider = {
            LLMConfig(
                activeProvider = delegate.name,
                activeModel = ProviderCatalog.defaultModel(delegate.name),
                apiKeys = mapOf(delegate.name to "test-key")
            )
        },
        retryRuntime = RetryRuntime(
            nowMillis = { 0L },
            delayMillis = {},
            jitterMillis = { 0L }
        )
    )

    private fun request() = LLMRequest(
        systemPrompt = "constant",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        responseFormat = ResponseFormat.TEXT
    )

    private fun response(request: LLMRequest) = LLMResponse(
        content = "pong",
        tokensUsed = 1,
        model = request.model.orEmpty(),
        provider = "OpenAI",
        latencyMs = 1
    )

    private class FakeProvider(
        override val name: String,
        private val stream: ((LLMRequest) -> Flow<String>)? = null,
        private val completion: suspend (LLMRequest) -> LLMResponse = { request ->
            LLMResponse("ok", 1, request.model.orEmpty(), name, 1)
        }
    ) : LLMProvider {
        override val availableModels: List<String> = emptyList()
        override suspend fun complete(request: LLMRequest): LLMResponse = completion(request)
        override fun streamComplete(request: LLMRequest): Flow<String> =
            stream?.invoke(request) ?: flow { emit(completion(request).content) }
        override suspend fun isAvailable(): Boolean = true
    }
}
