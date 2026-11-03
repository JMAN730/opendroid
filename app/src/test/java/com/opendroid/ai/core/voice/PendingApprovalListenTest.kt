package com.opendroid.ai.core.voice

import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingApprovalListenTest {

    private fun proposed(id: String) = AgentState.PlanProposed(
        Plan(
            planId = id,
            goal = "test goal",
            estimatedDuration = "1m",
            estimatedSteps = 0,
            steps = emptyList(),
            status = PlanStatus.PROPOSED
        )
    )

    @Test
    fun `the end of the prompt starts one listen, for the plan it announced`() {
        val pending = PendingApprovalListen()
        pending.arm("p1")
        pending.onAgentState(proposed("p1"))

        assertEquals("p1", pending.take())
        assertNull(pending.take())
    }

    @Test
    fun `leaving PlanProposed while the prompt plays cancels the listen`() {
        val pending = PendingApprovalListen()
        pending.arm("p1")

        pending.onAgentState(AgentState.Idle)

        assertNull(pending.take())
    }

    @Test
    fun `a different proposal replacing the announced one cancels the listen`() {
        val pending = PendingApprovalListen()
        pending.arm("p1")

        pending.onAgentState(proposed("p2"))

        assertNull(pending.take())
    }
}
