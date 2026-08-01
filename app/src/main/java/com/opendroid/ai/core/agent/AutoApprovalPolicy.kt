package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep

/**
 * Pure decision logic for Auto mode (upstream issue 18 spec). Mixed plans are
 * all-or-nothing: a plan auto-runs only when EVERY step's action is granted
 * and none is neverAutoApprove; YOLO widens grants but never bypasses the
 * destructive-action guard. No mid-plan pause
 * states — a blocked plan falls back to the normal PlanProposed gate whole.
 */
object AutoApprovalPolicy {

    fun shouldAutoApprove(mode: AutoMode, granted: Set<String>, plan: Plan): Boolean {
        if (mode == AutoMode.OFF) return false
        return plan.steps
            .flatMap {
                step -> listOfNotNull(step.action.takeIf { it.isNotBlank() }, step.fallback.takeIf { it.isNotBlank() })
            }
            .none { ActionSchema.isNeverAutoApprove(it) || (mode == AutoMode.AUTO && it !in granted) }
    }

    /**
     * Distinct actions (in step order) that keep this plan from auto-running.
     * Includes each step's non-blank [PlanStep.fallback] — AgentLoop executes
     * fallbacks on primary failure, so they must pass the same allowlist gate.
     */
    fun blockedActions(granted: Set<String>, steps: List<PlanStep>): List<String> =
        steps.flatMap { step ->
            listOfNotNull(step.action.takeIf { it.isNotBlank() }, step.fallback.takeIf { it.isNotBlank() })
        }
            .distinct()
            .filter { it !in granted || ActionSchema.isNeverAutoApprove(it) }

    /** Only known, non-neverAutoApprove actions may ever be granted. */
    fun isGrantable(actionName: String): Boolean =
        ActionSchema.ALL_ACTIONS.any { it.name == actionName } && !ActionSchema.isNeverAutoApprove(actionName)
}
