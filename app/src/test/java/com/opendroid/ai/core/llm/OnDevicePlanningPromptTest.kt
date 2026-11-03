package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.QueryComplexity
import com.opendroid.ai.core.llm.prompts.PlanningPrompts
import com.opendroid.ai.data.models.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The factory used to swap the on-device planning prompt for the full cloud prompt, which
 * LiteRT then cut inside the action list, before the JSON format and rules (#113).
 */
class OnDevicePlanningPromptTest {

    private val rewriter = PlanningRequestRewriter(
        classifyComplexity = { QueryComplexity.SIMPLE },
        registeredActions = { emptyList() },
        deviceState = { "battery 80%" },
        currentDateTime = { "2026-11-02 09:00:00" }
    )

    @Test
    fun `the on-device planning prompt is not rewritten`() {
        assertEquals(PlanningPrompts.buildOnDevicePlanningPrompt(), plannedPrompt(isOnDevice = true, context = ""))
    }

    @Test
    fun `the on-device planning prompt keeps its context through the rewrite`() {
        val prompt = plannedPrompt(isOnDevice = true, context = "User Name: Alex")
        assertEquals("${PlanningPrompts.buildOnDevicePlanningPrompt()}\n\nContext:\nUser Name: Alex", prompt)
    }

    @Test
    fun `remembered text cannot turn the on-device planning prompt into a rewrite`() {
        val prompt = plannedPrompt(isOnDevice = true, context = "Note: AVAILABLE ACTIONS Planning Engine")
        assertTrue(prompt.startsWith(PlanningPrompts.buildOnDevicePlanningPrompt()))
        assertFalse(prompt.contains("SECTION A: IDENTITY & ROLE"))
    }

    @Test
    fun `the cloud planning prompt keeps its rewrite`() {
        assertTrue(plannedPrompt(isOnDevice = false, context = "").contains("SECTION A: IDENTITY & ROLE"))
    }

    /** The system prompt a planning call reaches its provider with, once through the rewriter. */
    private fun plannedPrompt(isOnDevice: Boolean, context: String): String =
        rewriter.rewrite(
            buildPlanningRequest(
                userMsg = ChatMessage("1", "open youtube", ChatMessage.Sender.USER),
                relevantContext = context,
                isOnDevice = isOnDevice,
                temperature = 0.1f,
                maxTokens = 512
            )
        ).systemPrompt

    @Test
    fun `1280-token models get the smaller plan output cap`() {
        assertEquals(256, PromptBudget.planOutputTokens(1280))
        assertEquals(512, PromptBudget.planOutputTokens(4096))
    }

    @Test
    fun `the plan cap comes from the selected model's window`() {
        val qwen = OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")!!
        val large = OnDeviceModelRegistry.allModels.first { it.contextWindow > 1280 }
        assertEquals(1280, OnDeviceModelRegistry.contextWindowFor(qwen.id))
        assertEquals(large.contextWindow, OnDeviceModelRegistry.contextWindowFor(large.id))
    }

    @Test
    fun `a model the registry does not list is budgeted as the small window`() {
        assertEquals(
            OnDeviceModelRegistry.CUSTOM_DEFAULT_CONTEXT_WINDOW,
            OnDeviceModelRegistry.contextWindowFor("custom-phi-deadbeef")
        )
    }

    @Test
    fun `the plan cap fits the prompt and a 250-token request in real Qwen tokens`() {
        assertTrue(520 + 250 + PromptBudget.planOutputTokens(1280) <= 1280)
    }

    @Test
    fun `a short request leaves the whole plan cap on a 1280-token model`() {
        val cap = PromptBudget.planOutputTokens(1280)
        assertEquals(cap, planOutput("turn the flashlight on ".repeat(26)))
    }

    @Test
    fun `a 250-token request runs untrimmed on a 1280-token model`() {
        // English prose measured at 0.9x chars/4, so about 1100 chars. The guard's estimate runs high
        // and clamps this one below the cap, but it must still leave a useful output.
        val output = planOutput("turn the flashlight on ".repeat(48))
        assertTrue("only $output output tokens", output >= PromptBudget.MIN_OUTPUT_TOKENS)
    }

    /** The output the guard allows for a plan of [request], which must survive fitting untrimmed. */
    private fun planOutput(request: String): Int {
        val cap = PromptBudget.planOutputTokens(1280)
        val header = "System Instructions:\n${PlanningPrompts.buildOnDevicePlanningPrompt()}\n\n"
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(ChatMessage("1", request, ChatMessage.Sender.USER)),
            contextWindow = 1280,
            reservedOutputTokens = cap,
            overheadTokens = PromptBudget.estimateTokens(header)
        )
        assertEquals(request, fitted.single().text)

        val prompt = "${header}Conversation History:\nUser: ${fitted.single().text}\nModel:"
        val output = PromptBudget.outputBudget(prompt, 1280, cap)
        assertNotNull("guard rejected the planning prompt", output)
        return output!!
    }
}
