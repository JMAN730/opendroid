package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.QueryComplexity
import com.opendroid.ai.core.llm.prompts.PlanningPrompts
import com.opendroid.ai.core.llm.prompts.SystemPrompts
import com.opendroid.ai.data.models.ChatMessage

/**
 * The agent's planning call. This is the only place that sets [RequestPurpose.PLANNING], so the
 * rewrite to the main prompt can't be reached by a caller whose prompt embeds third-party text.
 */
internal fun buildPlanningRequest(
    userMsg: ChatMessage,
    relevantContext: String,
    isOnDevice: Boolean,
    temperature: Float,
    maxTokens: Int
): LLMRequest {
    val systemPrompt = if (isOnDevice) {
        val base = PlanningPrompts.buildOnDevicePlanningPrompt()
        if (relevantContext.isNotBlank()) "$base\n\nContext:\n${relevantContext.take(250)}" else base
    } else {
        "${PlanningPrompts.PLANNING_SYSTEM_PROMPT}\n\nContext about user and device:\n$relevantContext"
    }
    return LLMRequest(
        systemPrompt = systemPrompt,
        messages = listOf(userMsg),
        temperature = temperature,
        maxTokens = maxTokens,
        responseFormat = ResponseFormat.JSON,
        // The on-device prompt must reach its model as written: LiteRT would cut the main prompt
        // inside the action list, before the JSON formats and rules (#113).
        purpose = if (isOnDevice) RequestPurpose.GENERAL else RequestPurpose.PLANNING,
        memoryContext = if (isOnDevice) "" else relevantContext.trim()
    )
}

/** Swaps a planning request's system prompt for the main prompt, sized to the query. */
internal class PlanningRequestRewriter(
    private val classifyComplexity: (String) -> QueryComplexity,
    private val registeredActions: () -> List<String>,
    private val deviceState: () -> String,
    private val currentDateTime: () -> String = {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    }
) {
    fun rewrite(request: LLMRequest): LLMRequest {
        if (request.purpose != RequestPurpose.PLANNING) return request

        val maxSteps = when (classifyComplexity(request.messages.lastOrNull()?.text ?: "")) {
            QueryComplexity.SIMPLE -> 1
            QueryComplexity.MEDIUM -> 3
            QueryComplexity.COMPLEX -> 10
        }

        return request.copy(
            systemPrompt = SystemPrompts.buildMainPrompt(
                registeredActions = registeredActions(),
                memoryContext = request.memoryContext,
                currentDateTime = currentDateTime(),
                deviceState = deviceState(),
                maxSteps = maxSteps
            )
        )
    }
}
