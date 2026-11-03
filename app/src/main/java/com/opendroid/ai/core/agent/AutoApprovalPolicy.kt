package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep

/**
 * Pure decision logic for Auto mode (upstream issue 18 spec, YOLO semantics
 * revised per #52 discussion). Mixed plans are all-or-nothing in AUTO: a plan
 * auto-runs only when EVERY step's action is granted and none is
 * neverAutoApprove. YOLO is all-in by owner decision — the user opted out of
 * every approval gate, including the neverAutoApprove guard. No mid-plan pause
 * states — a blocked plan falls back to the normal PlanProposed gate whole.
 */
object AutoApprovalPolicy {

    /**
     * [userText] is the request the plan answers. In AUTO, a conditional request
     * ("if it rains, text my wife …") whose plan acts before any step checks the
     * condition is proposed instead: re-evaluation only runs after a step, so
     * nothing could stop that first action.
     */
    fun shouldAutoApprove(mode: AutoMode, granted: Set<String>, plan: Plan, userText: String = ""): Boolean = when (mode) {
        AutoMode.OFF -> false
        AutoMode.YOLO -> true
        AutoMode.AUTO -> plan.steps
            .flatMap {
                step -> listOfNotNull(step.action.takeIf { it.isNotBlank() }, step.fallback.takeIf { it.isNotBlank() })
            }
            .none { ActionSchema.isNeverAutoApprove(it) || it !in granted } &&
            !(IntentClassifier.hasConditionalMarker(userText) && actsBeforeChecking(plan.steps))
    }

    /** True when the plan's first step is an action rather than a step that reads data. */
    internal fun actsBeforeChecking(steps: List<PlanStep>): Boolean {
        val first = steps.sortedBy { it.order }.firstOrNull { it.action.isNotBlank() } ?: return false
        return first.action.uppercase() !in CONDITION_CHECK_ACTIONS
    }

    /** Steps that can check a condition: data producers, plus the notification read rule 5 of the planning prompt names. */
    private val CONDITION_CHECK_ACTIONS = PlanValidator.DATA_PRODUCING_ACTIONS + "READ_NOTIFICATIONS"

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
