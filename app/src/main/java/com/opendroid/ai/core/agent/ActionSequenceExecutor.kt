package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.crash.CrashLogRedactor
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The common ordered action-dispatch seam used by Plans and saved Macros.
 *
 * Macro execution is deliberately linear: a failed step stops the sequence.
 * A step may run one configured fallback action, but a fallback never gets a
 * fallback of its own.
 */
class ActionSequenceExecutor(
    private val executeAction: suspend (String, Map<String, String>, Context) -> ActionResult,
    private val hasAction: (String) -> Boolean
) {

    data class StepExecution(
        val step: PlanStep,
        val resolvedParams: Map<String, String>,
        val primaryResult: ActionResult,
        val fallbackResult: ActionResult? = null
    ) {
        val finalResult: ActionResult
            get() = fallbackResult ?: primaryResult

        val usedFallback: Boolean
            get() = fallbackResult != null
    }

    /** Dispatches one primary action through the normal ActionDispatcher path. */
    suspend fun dispatch(
        action: String,
        params: Map<String, String>,
        context: Context
    ): ActionResult = try {
        executeAction(action, params, context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ActionResult.Failure(
            errorMsg = e.localizedMessage ?: "Unknown execution error"
        )
    }

    /**
     * Resolves both `$stepId` and `$$stepId` references from completed steps.
     * The double-dollar form is retained for compatibility with the existing
     * AgentLoop executor and is replaced before the single-dollar form.
     */
    fun resolveParameters(
        params: Map<String, String>,
        completedResults: Map<String, String>
    ): Map<String, String> = params.mapValues { (_, value) ->
        completedResults.entries
            .sortedByDescending { it.key.length }
            .fold(value) { resolved, (stepId, result) ->
                val doubleReference = "$" + "$" + stepId
                val singleReference = "$" + stepId
                resolved
                    .replace(doubleReference, result)
                    .replace(singleReference, result)
            }
    }

    /** Resolves references using only completed PlanStep results. */
    fun resolveParameters(
        params: Map<String, String>,
        priorSteps: List<PlanStep>
    ): Map<String, String> = resolveParameters(
        params = params,
        completedResults = priorSteps.asSequence()
            .filter { it.status == StepStatus.COMPLETED && it.result != null }
            .associate { it.stepId to it.result!! }
    )

    /** Executes a step and, when appropriate, its single fallback action. */
    suspend fun executeStep(
        step: PlanStep,
        completedResults: Map<String, String>,
        context: Context
    ): StepExecution {
        val resolvedParams = resolveParameters(step.params, completedResults)
        val primaryResult = dispatch(step.action, resolvedParams, context)
        val fallbackResult = if (shouldAttemptFallback(primaryResult, step)) {
            dispatch(step.fallback, resolvedParams, context)
        } else {
            null
        }
        return StepExecution(
            step = step,
            resolvedParams = resolvedParams,
            primaryResult = primaryResult,
            fallbackResult = fallbackResult
        )
    }

    /** Whether the existing Plan fallback behavior applies to this result. */
    fun shouldAttemptFallback(result: ActionResult, step: PlanStep): Boolean =
        !result.success &&
            step.fallback.isNotBlank() &&
            hasAction(step.fallback) &&
            result !is ActionResult.UnknownAction &&
            result !is ActionResult.PendingUserAction &&
            result !is ActionResult.UserActionRequired

    /**
     * Runs a complete saved macro. Later steps are not dispatched after a
     * failure, so the returned result cannot claim work that did not happen.
     */
    suspend fun execute(
        steps: List<PlanStep>,
        context: Context
    ): ActionResult {
        if (steps.isEmpty()) {
            return ActionResult.Failure("Macro contains no executable steps.")
        }

        val orderedSteps = steps.sortedWith(compareBy<PlanStep> { it.order })
        val completedResults = linkedMapOf<String, String>()

        orderedSteps.forEachIndexed { index, step ->
            currentCoroutineContext().ensureActive()
            val execution = executeStep(step, completedResults, context)
            val result = execution.finalResult
            if (!result.success) {
                val actionName = step.action.ifBlank { "unknown action" }
                val detail = result.error
                    ?.let(CrashLogRedactor::redact)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Action execution failed."
                return ActionResult.Failure(
                    "Macro stopped at step ${index + 1} ($actionName): $detail"
                )
            }

            completedResults[step.stepId] = result.data ?: "Completed successfully."
        }

        return ActionResult.Success(
            dataMap = mapOf(
                "message" to "Macro completed successfully (${orderedSteps.size} steps)."
            )
        )
    }
}
