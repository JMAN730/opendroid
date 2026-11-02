package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval card and the spoken prompt describe the steps that will run, not the
 * goal and step count the model wrote about its own plan (#162).
 */
class PlanApprovalSummaryTest {

    private fun step(id: String, order: Int, action: String, params: Map<String, String> = emptyMap()) = PlanStep(
        stepId = id,
        order = order,
        description = "Check the forecast",
        action = action,
        params = params,
        fallback = ""
    )

    // What PlanValidator makes of {"goal": "Check today's weather", "estimatedSteps": 1,
    // "steps": [GET_WEATHER, SEND_SMS to an unknown name]}.
    private val validated = Plan(
        planId = "p1",
        goal = "Check today's weather",
        estimatedDuration = "",
        estimatedSteps = 3,
        steps = listOf(
            step("s1", 1, "GET_WEATHER"),
            step("s2_ask", 2, "ASK_USER", mapOf("question" to "What is their phone number?")),
            step("s2", 3, "SEND_SMS", mapOf("contact" to "\$s2_ask", "message" to "hi")),
        )
    )

    @Test
    fun `the step count comes from the steps, not the model's estimate`() {
        val lied = validated.copy(estimatedSteps = 1)
        assertEquals(3, PlanApprovalSummary.stepLines(lied).size)
        assertTrue(PlanApprovalSummary.spokenPrompt(lied).startsWith("I've planned 3 steps:"))
    }

    @Test
    fun `every action is named, with its recipient`() {
        val plan = validated.copy(
            steps = listOf(
                step("s1", 1, "GET_WEATHER"),
                step("s2", 2, "SEND_SMS", mapOf("contact" to "+19005550100", "message" to "hi"))
            )
        )
        assertEquals(listOf("Get weather", "Send SMS to +19005550100"), PlanApprovalSummary.stepLines(plan))
        assertEquals(
            "I've planned 2 steps: Get weather, Send SMS to +19005550100. Say approve to run, or cancel.",
            PlanApprovalSummary.spokenPrompt(plan)
        )
    }

    @Test
    fun `the model's goal is not what the prompt reads out`() {
        assertFalse(PlanApprovalSummary.spokenPrompt(validated).contains("Check today's weather"))
    }

    @Test
    fun `a recipient taken from an earlier step names that step`() {
        assertEquals("Send SMS to the answer to step 2", PlanApprovalSummary.stepLines(validated)[2])
    }

    @Test
    fun `amounts and paths are shown`() {
        val plan = validated.copy(
            steps = listOf(
                step("s1", 1, "PAY_UPI", mapOf("to" to "bob@upi", "amount" to "500", "note" to "")),
                step("s2", 2, "DELETE_FILE", mapOf("filePath" to "/sdcard/Documents/taxes.pdf"))
            )
        )
        assertEquals(
            listOf("Pay UPI to bob@upi (amount 500)", "Delete file /sdcard/Documents/taxes.pdf"),
            PlanApprovalSummary.stepLines(plan)
        )
    }

    @Test
    fun `a long value is cut short`() {
        val plan = validated.copy(steps = listOf(step("s1", 1, "SEND_EMAIL", mapOf("to" to "a".repeat(80)))))
        assertEquals("Send email to ${"a".repeat(39)}…", PlanApprovalSummary.stepLines(plan).single())
    }

    @Test
    fun `one step reads as singular`() {
        val plan = validated.copy(steps = listOf(step("s1", 1, "GET_WEATHER")))
        assertEquals("I've planned 1 step: Get weather. Say approve to run, or cancel.", PlanApprovalSummary.spokenPrompt(plan))
    }
}
