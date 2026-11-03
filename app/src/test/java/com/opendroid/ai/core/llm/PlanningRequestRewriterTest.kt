package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.llm.prompts.PlanningPrompts
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every provider is wrapped so a cloud planning request is rewritten into
 * [com.opendroid.ai.core.llm.prompts.SystemPrompts.buildMainPrompt]. A
 * conditional request must keep the conditional rule and room for a check step.
 */
class PlanningRequestRewriterTest {

    private val classifier = IntentClassifier(dagger.Lazy { error("LLM provider is not used by classifyComplexity") })

    private fun plannedSystemPrompt(userText: String): String = runBlocking {
        val seen = mutableListOf<String>()
        val delegate = RecordingProvider { seen += it.systemPrompt }
        val wrapper = WrappedLLMProvider(
            delegate = delegate,
            configProvider = {
                LLMConfig(
                    activeProvider = delegate.name,
                    activeModel = ProviderCatalog.defaultModel(delegate.name),
                    apiKeys = mapOf(delegate.name to "test-key")
                )
            },
            requestRewriter = { request ->
                PlanningRequestRewriter.rewrite(
                    request = request,
                    classifyComplexity = classifier::classifyComplexity,
                    registeredActions = { emptyList() },
                    deviceState = { "battery 80%" },
                    currentDateTime = { "2026-11-02 09:00:00" }
                )
            },
            retryRuntime = RetryRuntime(nowMillis = { 0L }, delayMillis = {}, jitterMillis = { 0L })
        )
        wrapper.complete(
            LLMRequest(
                systemPrompt = PlanningPrompts.PLANNING_SYSTEM_PROMPT,
                messages = listOf(ChatMessage("1", userText, ChatMessage.Sender.USER)),
                responseFormat = ResponseFormat.JSON
            )
        )
        assertEquals(1, seen.size)
        seen.single()
    }

    @Test
    fun `a conditional send keeps the conditional rule and is not capped at one step`() {
        listOf(
            "If it's going to rain tomorrow, text my wife that I'll pick up the kids",
            "Text my wife that I'll pick up the kids if it rains tomorrow",
            "If my battery is below 15% text Mom that my phone is dying",
            "If I have no meetings this afternoon tell John I can meet"
        ).forEach { userText ->
            val prompt = plannedSystemPrompt(userText)
            assertTrue(userText, prompt.contains("CONDITIONAL REQUESTS"))
            assertTrue(userText, prompt.contains("Re-Evaluation Engine"))
            assertFalse(userText, prompt.contains("maximum number of steps allowed is 1."))
        }
    }

    @Test
    fun `a plain simple request is still capped at one step`() {
        val prompt = plannedSystemPrompt("call mom")
        assertTrue(prompt.contains("The maximum number of steps allowed is 1."))
    }

    @Test
    fun `the rewritten prompt teaches the step reference syntax and no placeholder message`() {
        val prompt = plannedSystemPrompt("message mom on whatsapp")
        assertTrue(prompt.contains("\$\$s1"))
        assertFalse(prompt.contains("message: \"...\""))
    }

    @Test
    fun `a non-planning request passes through unchanged`() {
        val request = LLMRequest(
            systemPrompt = "You are a helpful assistant.",
            messages = listOf(ChatMessage("1", "if it rains text my wife", ChatMessage.Sender.USER))
        )
        val rewritten = PlanningRequestRewriter.rewrite(
            request = request,
            classifyComplexity = { error("not a planning request") },
            registeredActions = { error("not a planning request") },
            deviceState = { error("not a planning request") },
            currentDateTime = { error("not a planning request") }
        )
        assertEquals(request, rewritten)
    }

    private class RecordingProvider(private val record: (LLMRequest) -> Unit) : LLMProvider {
        override val name: String = "OpenAI"
        override val availableModels: List<String> = emptyList()
        override suspend fun complete(request: LLMRequest): LLMResponse {
            record(request)
            return LLMResponse("{}", 1, request.model.orEmpty(), name, 1)
        }
        override fun streamComplete(request: LLMRequest): Flow<String> = flow { emit(complete(request).content) }
        override suspend fun isAvailable(): Boolean = true
    }
}
