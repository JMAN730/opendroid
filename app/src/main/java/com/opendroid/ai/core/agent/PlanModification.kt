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

    /**
     * [revisionLost] means the answer asked for changes and every one was rejected, so
     * the plan left in [steps] is not what the model wanted to run.
     */
    data class MergeResult(
        val steps: List<PlanStep>,
        val rejected: List<PlanStep>,
        val revisionLost: Boolean = false
    )

    /**
     * Pending steps are replaced by stepId and new ones appended. Pending steps the
     * answer omits stay queued, and settled steps are never touched. Steps naming an
     * action outside [ActionSchema] are returned in [MergeResult.rejected]. Status,
     * result and error are execution state, so the model cannot set them.
     *
     * A step reusing a settled stepId is not dropped, because the model often renumbers
     * the remainder from "s1". An unchanged echo of the settled step is ignored. Anything
     * else takes over a pending step with the same action, since appending it next to
     * that step would send the same message twice, or runs under a new "<id>_r1" id when
     * there is no such step. Either way it reaches [requiresApproval] as a change.
     *
     * [repairing] names the step that just failed with an unknown action. A step reusing
     * its id with a different action replaces it in place, so it runs before the steps
     * that follow it. Keeping the action cannot fix an unknown action, so that is ignored.
     *
     * [asked] is the plan the model was shown. The answer can only be stale about a
     * pending step the user edited or deleted since then, so what it says about that
     * step is dropped and the user's version stands: a deleted step must not come back
     * as a "new" one, and an echo must not undo an edit.
     */
    fun merge(
        current: List<PlanStep>,
        updated: List<PlanStep>,
        asked: List<PlanStep> = current,
        repairing: String? = null
    ): MergeResult {
        val settled = current.filter { it.status != StepStatus.PENDING }.associateBy { it.stepId }
        val repairable = settled[repairing]?.takeIf { it.status == StepStatus.FAILED }
        val userChanged = asked.filter { it.status == StepStatus.PENDING && it !in current }
        val userChangedIds = userChanged.map { it.stepId }.toSet()
        val userChangedActions = userChanged.map { it.action }.toSet()
        val (valid, rejected) = updated
            .distinctBy { it.stepId }
            .filter { it.stepId !in userChangedIds }
            .partition(::hasKnownActions)
        val incoming = valid
            .filter { step ->
                val old = settled[step.stepId]
                when {
                    old == null -> true
                    old === repairable -> step.action != old.action
                    else -> old.action != step.action || old.fallback != step.fallback || old.params != step.params
                }
            }
            .map { it.copy(status = StepStatus.PENDING, result = null, error = null) }

        val open = current
            .filter { it.status == StepStatus.PENDING && it.stepId !in userChangedIds }
            .toMutableList()
        val usedIds = (current + incoming).map { it.stepId }.toMutableSet()
        val replacements = mutableMapOf<String, PlanStep>()
        val added = mutableListOf<PlanStep>()

        fun placeWithoutOwnSlot(step: PlanStep) {
            val slot = open.firstOrNull { it.action == step.action }
            if (slot != null) {
                open -= slot
                replacements[slot.stepId] = step.copy(stepId = slot.stepId, dependsOn = step.dependsOn - slot.stepId)
            } else {
                val newId = generateSequence(1) { it + 1 }.map { "${step.stepId}_r$it" }.first(usedIds::add)
                added += step.copy(stepId = newId)
            }
        }

        // Collisions go first so a renumbered list claims steps in order: a later step
        // whose own id was just claimed moves on to the next match instead of overwriting it.
        val (collided, others) = incoming.partition { it.stepId in settled && settled[it.stepId] !== repairable }
        collided.filter { it.action !in userChangedActions }.forEach(::placeWithoutOwnSlot)
        others.forEach { step ->
            when {
                step.stepId == repairable?.stepId -> replacements[step.stepId] = step
                current.none { it.stepId == step.stepId } -> added += step
                open.removeAll { it.stepId == step.stepId } -> replacements[step.stepId] = step
                else -> placeWithoutOwnSlot(step)
            }
        }

        val steps = current.map { replacements[it.stepId] ?: it } + added
        return MergeResult(steps, rejected, revisionLost = rejected.isNotEmpty() && incoming.isEmpty())
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
