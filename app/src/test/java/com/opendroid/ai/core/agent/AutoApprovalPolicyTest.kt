package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoApprovalPolicyTest {

    private fun step(id: String, action: String, fallback: String = "") = PlanStep(
        stepId = id,
        order = id.substring(1).toInt(),
        description = "step $id",
        action = action,
        params = emptyMap(),
        fallback = fallback
    )

    private fun plan(vararg actions: String) = Plan(
        planId = "p1",
        goal = "test goal",
        estimatedDuration = "1m",
        estimatedSteps = actions.size,
        steps = actions.mapIndexed { i, a -> step("s$i", a) }
    )

    private val granted = setOf("WEB_SEARCH", "GET_WEATHER", "SET_BRIGHTNESS")

    @Test
    fun `OFF never auto-approves`() {
        assertFalse(AutoApprovalPolicy.shouldAutoApprove(AutoMode.OFF, granted, plan("WEB_SEARCH")))
    }

    @Test
    fun `AUTO approves when every step is granted`() {
        assertTrue(AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted, plan("WEB_SEARCH", "GET_WEATHER")))
    }

    @Test
    fun `AUTO is all-or-nothing - one ungranted step blocks the whole plan`() {
        assertFalse(AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted, plan("WEB_SEARCH", "SEND_SMS")))
    }

    @Test
    fun `AUTO never approves a neverAutoApprove action even if somehow granted`() {
        assertFalse(AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted + "PAY_UPI", plan("PAY_UPI")))
    }

    @Test
    fun `YOLO auto-approves even neverAutoApprove actions`() {
        assertTrue(AutoApprovalPolicy.shouldAutoApprove(AutoMode.YOLO, emptySet(), plan("PAY_UPI", "DELETE_FILE")))
    }

    @Test
    fun `YOLO approves safe actions without an allowlist grant`() {
        assertTrue(AutoApprovalPolicy.shouldAutoApprove(AutoMode.YOLO, emptySet(), plan("WEB_SEARCH")))
    }

    @Test
    fun `blockedActions lists distinct ungranted or flagged actions in step order`() {
        val steps = plan("WEB_SEARCH", "SEND_SMS", "PAY_UPI", "SEND_SMS").steps
        assertEquals(listOf("SEND_SMS", "PAY_UPI"), AutoApprovalPolicy.blockedActions(granted, steps))
    }

    @Test
    fun `grantable excludes neverAutoApprove and unknown actions`() {
        assertTrue(AutoApprovalPolicy.isGrantable("SEND_SMS"))
        assertFalse(AutoApprovalPolicy.isGrantable("PAY_UPI"))
        assertFalse(AutoApprovalPolicy.isGrantable("NOT_A_REAL_ACTION"))
    }

    @Test
    fun `AUTO blocks when primary is granted but fallback is not`() {
        val steps = listOf(step("s0", "WEB_SEARCH", fallback = "SEND_SMS"))
        val p = Plan("p1", "test", "1m", 1, steps)
        assertFalse(AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted, p))
        assertEquals(listOf("SEND_SMS"), AutoApprovalPolicy.blockedActions(granted, steps))
    }

    @Test
    fun `AUTO blocks neverAutoApprove fallback even if somehow granted`() {
        val steps = listOf(step("s0", "WEB_SEARCH", fallback = "PAY_UPI"))
        assertFalse(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted + "PAY_UPI", Plan("p1", "test", "1m", 1, steps))
        )
    }

    @Test
    fun `AUTO approves when primary and fallback are both granted`() {
        val steps = listOf(step("s0", "WEB_SEARCH", fallback = "GET_WEATHER"))
        assertTrue(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted, Plan("p1", "test", "1m", 1, steps))
        )
    }

    @Test
    fun `blank fallback is ignored`() {
        val steps = listOf(step("s0", "WEB_SEARCH", fallback = "   "))
        assertTrue(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted, Plan("p1", "test", "1m", 1, steps))
        )
    }

    @Test
    fun `AUTO proposes a conditional send that runs before anything checks the condition`() {
        val smsGranted = granted + "SEND_SMS"
        val conditional = "If it rains tomorrow, text my wife that I'll pick up the kids"
        assertFalse(AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, smsGranted, plan("SEND_SMS"), conditional))
        assertFalse(
            AutoApprovalPolicy.shouldAutoApprove(
                AutoMode.AUTO, smsGranted, plan("SEND_SMS", "GET_WEATHER"), "Text my wife I'll pick up the kids if it rains"
            )
        )
    }

    @Test
    fun `AUTO still runs a conditional send once a data step checks the condition first`() {
        val smsGranted = granted + "SEND_SMS"
        val conditional = "If it rains tomorrow, text my wife that I'll pick up the kids"
        assertTrue(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, smsGranted, plan("GET_WEATHER", "SEND_SMS"), conditional)
        )
    }

    @Test
    fun `AUTO still runs an unconditional granted send`() {
        assertTrue(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, granted + "SEND_SMS", plan("SEND_SMS"), "text my wife hi")
        )
    }

    @Test
    fun `YOLO keeps approving a conditional send`() {
        assertTrue(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.YOLO, emptySet(), plan("SEND_SMS"), "if it rains text my wife")
        )
    }

    @Test
    fun `blank primary action is ignored`() {
        val steps = listOf(step("s0", "   "))
        assertTrue(
            AutoApprovalPolicy.shouldAutoApprove(AutoMode.AUTO, emptySet(), Plan("p1", "test", "1m", 1, steps))
        )
        assertEquals(emptyList<String>(), AutoApprovalPolicy.blockedActions(emptySet(), steps))
    }
}
