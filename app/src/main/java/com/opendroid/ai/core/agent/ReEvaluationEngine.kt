package com.opendroid.ai.core.agent

import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderSelectionPolicy
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.llm.prompts.ReEvalPrompts
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReEvaluationEngine @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory,
    private val actionDispatcher: dagger.Lazy<ActionDispatcher>,
    private val unknownActionDao: dagger.Lazy<UnknownActionDao>
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    data class ReEvalResult(
        val speech: String,
        val decision: String, // "CONTINUE" | "MODIFY" | "ABANDON"
        val updatedPlan: Plan? = null
    )

    suspend fun evaluateStepResult(
        originalGoal: String,
        completedSteps: List<PlanStep>,
        failedSteps: List<PlanStep>,
        remainingSteps: List<PlanStep>,
        planId: String
    ): ReEvalResult {
        return try {
            // Format details for the prompt
            val inputDetails = """
                - Original goal: $originalGoal
                - Completed steps: ${json.encodeToString(completedSteps)}
                - Failed steps: ${json.encodeToString(failedSteps)}
                - Remaining steps: ${json.encodeToString(remainingSteps)}
            """.trimIndent()

            completeAndDecode(
                LLMRequest(
                    systemPrompt = ReEvalPrompts.RE_EVAL_SYSTEM_PROMPT,
                    messages = listOf(
                        com.opendroid.ai.data.models.ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = inputDetails,
                            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.0f,
                    maxTokens = 1000,
                    responseFormat = ResponseFormat.JSON
                ),
                actionNames = (completedSteps + failedSteps + remainingSteps).map { it.action }
            ) { cleaned -> json.decodeFromString<ReEvalResult>(cleaned) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            throw e
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable("Unknown provider", "", e)
        }
    }

    suspend fun replanAfterUnknownAction(
        originalGoal: String,
        failedStep: PlanStep,
        completedSteps: List<PlanStep>,
        remainingSteps: List<PlanStep>,
        planId: String
    ): ReEvalResult {
        return try {
            val whitelist = ActionSchema.getAllActionNames()
            
            // Format details for the prompt
            val systemPrompt = """
                You are OpenDroid's Re-evaluation Engine. A plan step failed because the action '${failedStep.action}' is NOT registered.
                
                You must rewrite the plan starting from this failed step. 
                You MUST ONLY use the actions listed in the Whitelist below. Any other actions will fail.
                
                Whitelist:
                ${whitelist.joinToString("\n") { "  - $it" }}
                
                Respond strictly in JSON:
                {
                  "speech": "An explanation to the user of how you fixed the plan.",
                  "decision": "MODIFY",
                  "updatedPlan": {
                    "goal": "$originalGoal",
                    "planId": "$planId",
                    "estimatedSteps": ${remainingSteps.size + 1},
                    "estimatedDuration": "2 minutes",
                    "steps": [
                      // List the corrected step and the remaining steps. Use steps from the whitelist only.
                    ]
                  }
                }
            """.trimIndent()

            val inputDetails = """
                - Failed step: ${json.encodeToString(failedStep)}
                - Completed steps: ${json.encodeToString(completedSteps)}
                - Remaining steps: ${json.encodeToString(remainingSteps)}
            """.trimIndent()

            val result = completeAndDecode(
                LLMRequest(
                    systemPrompt = systemPrompt,
                    messages = listOf(
                        com.opendroid.ai.data.models.ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = inputDetails,
                            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.0f,
                    maxTokens = 1000,
                    responseFormat = ResponseFormat.JSON
                ),
                actionNames = listOf(failedStep.action) +
                    completedSteps.map { it.action } + remainingSteps.map { it.action }
            ) { cleaned -> json.decodeFromString<ReEvalResult>(cleaned) }
            
            // Log to unknown actions DB only — never to semantic memory
            try {
                unknownActionDao.get().insertUnknownAction(
                    UnknownActionEntity(
                        attemptedAction = failedStep.action,
                        goal = originalGoal,
                        fixStatus = "REPLANNED"
                    )
                )
            } catch (e: Exception) {}
            
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            try {
                unknownActionDao.get().insertUnknownAction(
                    UnknownActionEntity(
                        attemptedAction = failedStep.action,
                        goal = originalGoal,
                        fixStatus = "FAILED"
                    )
                )
            } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            try {
                unknownActionDao.get().insertUnknownAction(
                    UnknownActionEntity(
                        attemptedAction = failedStep.action,
                        goal = originalGoal,
                        fixStatus = "FAILED"
                    )
                )
            } catch (_: Exception) {}
            throw LLMErrorMapper.fromThrowable("Unknown provider", "", e)
        }
    }

    suspend fun extractLearning(attemptedAction: String, goal: String) {
        // Log unknown actions to the UnknownActionDao only — NEVER to semantic memory.
        // Writing error data to semantic memory causes memory poisoning where the LLM
        // sees the bad action name in context and keeps hallucinating the same invalid action.
        try {
            unknownActionDao.get().insertUnknownAction(
                UnknownActionEntity(
                    attemptedAction = attemptedAction,
                    goal = goal,
                    fixStatus = "LOGGED"
                )
            )
        } catch (e: Exception) {
            // Ignore DB errors
        }
    }

    private fun cleanJsonString(raw: String): String {
        var content = raw.trim()
        if (content.startsWith("```json")) {
            content = content.removePrefix("```json")
        }
        if (content.endsWith("```")) {
            content = content.removeSuffix("```")
        }
        return content.trim()
    }

    /**
     * A malformed local plan is the only condition that may use a configured
     * alternate planner. Provider errors retain their existing retry/error
     * contract and never trigger a silent vendor switch.
     */
    private suspend fun <T> completeAndDecode(
        request: LLMRequest,
        actionNames: Collection<String>,
        decode: (String) -> T
    ): T {
        val providers = llmProviderFactory.getPlanningProviders(actionNames)
        var lastMalformed: LLMException? = null

        for ((index, provider) in providers.withIndex()) {
            val response = try {
                provider.complete(request)
            } catch (failure: LLMException) {
                if (ProviderSelectionPolicy.shouldEscalateMalformed(
                        failure.error,
                        index < providers.lastIndex
                    )
                ) {
                    lastMalformed = failure
                    continue
                }
                throw failure
            }

            llmProviderFactory.recordPlanningLatency(response)
            try {
                return decode(cleanJsonString(response.content))
            } catch (_: Exception) {
                lastMalformed = LLMErrorMapper.malformed(provider.name, response.model)
                if (index == providers.lastIndex) break
            }
        }

        val activeProvider = providers.firstOrNull()?.name ?: "On-Device AI"
        if (lastMalformed == null) {
            throw LLMErrorMapper.safeFallbackUnavailable(
                activeProvider,
                providers.firstOrNull()?.availableModels?.firstOrNull().orEmpty()
            )
        }
        when (ProviderSelectionPolicy.terminalError(activeProvider, providers.size)) {
            LLMError.SafeFallbackUnavailable ->
                throw LLMErrorMapper.safeFallbackUnavailable(activeProvider, lastMalformed.model)
            else -> throw lastMalformed
        }
    }
}
