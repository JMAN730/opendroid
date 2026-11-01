package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers provider selection for a persisted name. The factory itself needs the
 * Hilt graph, so the pure [LLMProviderFactory.resolveProvider] seam is exercised
 * with a call-counting Gemini double standing in for the registered providers.
 */
class LLMProviderFactoryTest {

    @Test
    fun `unknown active provider never reaches Gemini and fails every call as invalid`() = runBlocking {
        val gemini = CountingProvider("Google Gemini")
        var wrapped = 0

        val provider = LLMProviderFactory.resolveProvider(
            name = "Renamed Vendor",
            knownProvider = { name -> gemini.takeIf { name == gemini.name } },
            wrap = { raw -> wrapped++; WrappedLLMProvider(raw, { geminiConfig() }) }
        )

        val completeFailure = runCatching { provider.complete(request()) }.exceptionOrNull()
        val streamFailure = runCatching { provider.streamComplete(request()).toList() }.exceptionOrNull()
        val generateFailure = runCatching {
            provider.generate(request().messages).toList()
        }.exceptionOrNull()

        assertTrue(provider is UnknownLLMProvider)
        assertEquals("Renamed Vendor", provider.name)
        assertEquals(0, wrapped)
        assertEquals(0, gemini.calls)
        assertFalse(provider.isAvailable())
        assertTrue(provider.availableModels.isEmpty())
        assertRequestInvalid(completeFailure)
        assertRequestInvalid(streamFailure)
        assertRequestInvalid(generateFailure)
    }

    @Test
    fun `known provider is wrapped and routed to its own implementation`() = runBlocking {
        val gemini = CountingProvider("Google Gemini")
        var wrappedDelegate: LLMProvider? = null

        val provider = LLMProviderFactory.resolveProvider(
            name = "Google Gemini",
            knownProvider = { name -> gemini.takeIf { name == gemini.name } },
            wrap = { raw -> wrappedDelegate = raw; WrappedLLMProvider(raw, { geminiConfig() }) }
        )
        val response = provider.complete(request())

        assertSame(gemini, wrappedDelegate)
        assertTrue(provider is WrappedLLMProvider)
        assertEquals(1, gemini.calls)
        assertEquals("Google Gemini", response.provider)
    }

    private fun assertRequestInvalid(failure: Throwable?) {
        assertTrue("expected LLMException but was $failure", failure is LLMException)
        assertEquals(LLMError.RequestInvalid, (failure as LLMException).error)
        // LLMException redacts names the catalog does not know; the stale name must not leak.
        assertEquals("Unknown provider", failure.provider)
        assertFalse(failure.retryable)
    }

    private fun geminiConfig() = LLMConfig(
        activeProvider = "Google Gemini",
        activeModel = "gemini-2.5-flash",
        apiKeys = mapOf("Google Gemini" to "gemini-key")
    )

    private fun request() = LLMRequest(
        systemPrompt = "constant",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        responseFormat = ResponseFormat.TEXT
    )

    private class CountingProvider(override val name: String) : LLMProvider {
        var calls = 0
        override val availableModels: List<String> = emptyList()

        override suspend fun complete(request: LLMRequest): LLMResponse {
            calls++
            return LLMResponse("pong", 1, request.model.orEmpty(), name, 1)
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flow {
            calls++
            emit("pong")
        }

        override suspend fun isAvailable(): Boolean {
            calls++
            return true
        }
    }
}
