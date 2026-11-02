package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus

/**
 * Pure merge and approval logic for a MODIFY answer from [ReEvaluationEngine].
 * That answer is LLM output built from step results, which can carry third-party
 * text (notifications, screen contents), so it never inherits the approval the
 * plan it modifies was running under.
 */
object PlanModification {

    data class MergeResult(val steps: List<PlanStep>, val rejected: List<PlanStep>)

    /**
     * Pending steps are replaced by stepId and new ones appended. Pending steps the
     * answer omits stay queued, and settled steps are never touched. Steps naming an
     * action outside [ActionSchema] are returned in [MergeResult.rejected]. Status,
     * result and error are execution state, so the model cannot set them.
     *
     * [asked] is the plan the model was shown. The answer can only be stale about a
     * pending step the user edited or deleted since then, so what it says about that
     * step is dropped and the user's version stands: a deleted step must not come back
     * as a "new" one, and an echo must not undo an edit.
     */
    fun merge(
        current: List<PlanStep>,
        updated: List<PlanStep>,
        asked: List<PlanStep> = current
    ): MergeResult {
        val pendingIds = current.filter { it.status == StepStatus.PENDING }.map { it.stepId }.toSet()
        val settledIds = current.map { it.stepId }.toSet() - pendingIds
        val userChangedIds = asked
            .filter { it.status == StepStatus.PENDING && it !in current }
            .map { it.stepId }
            .toSet()
        val (valid, rejected) = updated
            .distinctBy { it.stepId }
            .filter { it.stepId !in settledIds && it.stepId !in userChangedIds }
            .partition(::hasKnownActions)
        val incoming = valid.map { it.copy(status = StepStatus.PENDING, result = null, error = null) }
        val replacements = incoming.associateBy { it.stepId }

        val steps = current.map { step ->
            if (step.status == StepStatus.PENDING) replacements[step.stepId] ?: step else step
        } + incoming.filter { it.stepId !in pendingIds }
        return MergeResult(steps, rejected)
    }

    /**
     * Whether the pending steps of [after] must go back to the user, given the plan
     * as it stood [before] the modification.
     *
     * AUTO re-runs the allowlist on the whole remainder. The one exception is a plan
     * the user approved by hand: blocked steps they already approved are not asked
     * about again unless the remainder actually changed.
     * OFF asks whenever a pending step is new or its action, fallback or params changed.
     * YOLO approves everything by owner decision, see [AutoApprovalPolicy].
     */
    fun requiresApproval(
        mode: AutoMode,
        granted: Set<String>,
        before: List<PlanStep>,
        after: Plan,
        autoApproved: Boolean
    ): Boolean {
        val pending = after.steps.filter { it.status == StepStatus.PENDING }
        return when (mode) {
            AutoMode.YOLO -> false
            AutoMode.OFF -> hasUnapprovedChange(before, pending)
            AutoMode.AUTO ->
                !AutoApprovalPolicy.shouldAutoApprove(mode, granted, after.copy(steps = pending)) &&
                    (autoApproved || hasUnapprovedChange(before, pending))
        }
    }

    private fun hasKnownActions(step: PlanStep): Boolean =
        ActionSchema.isValid(step.action) && (step.fallback.isBlank() || ActionSchema.isValid(step.fallback))

    private fun hasUnapprovedChange(before: List<PlanStep>, pending: List<PlanStep>): Boolean {
        val previous = before.filter { it.status == StepStatus.PENDING }.associateBy { it.stepId }
        return pending.any { step ->
            val old = previous[step.stepId]
            old == null || old.action != step.action || old.fallback != step.fallback || old.params != step.params
        }
    }
}
