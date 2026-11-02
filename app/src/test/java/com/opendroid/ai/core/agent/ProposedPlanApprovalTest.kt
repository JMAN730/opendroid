package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProposedPlanApprovalTest {

    private fun plan(id: String = "p1", status: PlanStatus = PlanStatus.PROPOSED) = Plan(
        planId = id,
        goal = "test goal",
        estimatedDuration = "1m",
        estimatedSteps = 0,
        steps = emptyList(),
        status = status
    )

    @Test
    fun `a late yes after Stop finds no proposal to claim`() {
        val state = MutableStateFlow<AgentState>(AgentState.Idle)

        assertFalse(claimProposedPlan(state, "p1"))
        assertEquals(AgentState.Idle, state.value)
    }

    @Test
    fun `an approval for an earlier proposal does not claim the one now showing`() {
        val showing = AgentState.PlanProposed(plan("p2"))
        val state = MutableStateFlow<AgentState>(showing)

        assertFalse(claimProposedPlan(state, "p1"))
        assertEquals(showing, state.value)
    }

    @Test
    fun `a late yes during an unrelated task leaves that task's state alone`() {
        val running = AgentState.ExecutingPlan("other task")
        val state = MutableStateFlow<AgentState>(running)

        assertFalse(claimProposedPlan(state, "p1"))
        assertEquals(running, state.value)
    }

    @Test
    fun `only the first of two approvals claims the proposal`() {
        val state = MutableStateFlow<AgentState>(AgentState.PlanProposed(plan()))

        assertTrue(claimProposedPlan(state, "p1"))
        assertEquals(AgentState.Thinking, state.value)
        assertFalse(claimProposedPlan(state, "p1"))
    }

    @Test
    fun `only the matching plan still in PROPOSED is awaiting approval`() {
        assertTrue(isAwaitingApproval(plan(), "p1"))
        assertFalse(isAwaitingApproval(null, "p1"))
        assertFalse(isAwaitingApproval(plan("p2"), "p1"))
        PlanStatus.values().filter { it != PlanStatus.PROPOSED }.forEach { status ->
            assertFalse(status.name, isAwaitingApproval(plan(status = status), "p1"))
        }
    }

    @Test
    fun `a failure while running an approved plan fails the plan instead of escaping`() = runBlocking {
        val state = MutableStateFlow<AgentState>(AgentState.Thinking)
        var planFailed = false

        runApprovedPlan(state, failPlan = { planFailed = true }) {
            throw IllegalStateException("database is closed")
        }

        assertTrue(planFailed)
        assertEquals(AgentState.Error("database is closed"), state.value)
    }

    @Test
    fun `the error still shows when failing the plan throws too`() = runBlocking {
        val state = MutableStateFlow<AgentState>(AgentState.Thinking)

        runApprovedPlan(state, failPlan = { error("still closed") }) {
            throw IllegalStateException("database is closed")
        }

        assertEquals(AgentState.Error("database is closed"), state.value)
    }

    @Test
    fun `a cancel while running an approved plan is not reported as a failure`() = runBlocking {
        val state = MutableStateFlow<AgentState>(AgentState.Thinking)
        var planFailed = false

        try {
            runApprovedPlan(state, failPlan = { planFailed = true }) {
                throw CancellationException("stopped")
            }
            fail("CancellationException was swallowed")
        } catch (_: CancellationException) {
        }

        assertFalse(planFailed)
        assertEquals(AgentState.Thinking, state.value)
    }
}
