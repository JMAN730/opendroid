package com.opendroid.ai.core.voice

import com.opendroid.ai.core.agent.AgentState

/**
 * The reply listen owed once the spoken approval prompt finishes, and the plan it is
 * for. Dropped as soon as that plan stops waiting at the gate, so tapping Stop during
 * the prompt doesn't open the microphone for an approval nobody can give any more.
 */
class PendingApprovalListen {
    @Volatile private var planId: String? = null

    fun arm(planId: String) {
        this.planId = planId
    }

    fun onAgentState(state: AgentState) {
        if ((state as? AgentState.PlanProposed)?.plan?.planId != planId) planId = null
    }

    /** The plan to listen for, at most once per [arm]. */
    fun take(): String? = planId.also { planId = null }
}
