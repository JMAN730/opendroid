package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A re-evaluation MODIFY can point a step at a different unresolved contact while
 * the ask step the validator queued for the old contact is still in the plan. Two
 * steps sharing one id would both take the first answer.
 */
class PlanValidatorAskStepTest {

    private fun ask(id: String, status: StepStatus = StepStatus.PENDING) = PlanStep(
        stepId = id,
        order = 1,
        description = "ask",
        action = "ASK_USER",
        params = mapOf("question" to "number?"),
        fallback = "",
        status = status
    )

    private fun sms(id: String, contact: String) = PlanStep(
        stepId = id,
        order = 2,
        description = "text",
        action = "SEND_SMS",
        params = mapOf("contact" to contact, "message" to "hi"),
        fallback = ""
    )

    @Test
    fun `a pending ask its step no longer reads is dropped`() {
        val steps = listOf(ask("s2_ask"), sms("s2", "Bob"))

        assertEquals(listOf("s2"), PlanValidator.withoutOrphanedAsks(steps).map { it.stepId })
    }

    @Test
    fun `an ask its step still reads is kept`() {
        val steps = listOf(ask("s2_ask"), sms("s2", "\$s2_ask"))

        assertEquals(steps, PlanValidator.withoutOrphanedAsks(steps))
    }

    @Test
    fun `a reference to a longer id does not keep a shorter one`() {
        val steps = listOf(ask("s2_ask"), ask("s2_ask2"), sms("s2", "\$s2_ask2"))

        assertEquals(listOf("s2_ask2", "s2"), PlanValidator.withoutOrphanedAsks(steps).map { it.stepId })
    }

    @Test
    fun `an answered ask is history and stays`() {
        val steps = listOf(ask("s2_ask", StepStatus.COMPLETED), sms("s2", "Bob"))

        assertEquals(steps, PlanValidator.withoutOrphanedAsks(steps))
    }

    @Test
    fun `an ask with no matching step is not the validator's and stays`() {
        val steps = listOf(ask("s9_ask"), sms("s2", "Bob"))

        assertEquals(steps, PlanValidator.withoutOrphanedAsks(steps))
    }

    @Test
    fun `a new ask id never reuses one already in the plan`() {
        assertEquals("s2_ask", PlanValidator.askStepIdFor("s2", setOf("s2")))
        assertEquals("s2_ask2", PlanValidator.askStepIdFor("s2", setOf("s2", "s2_ask")))
        assertEquals("s2_ask3", PlanValidator.askStepIdFor("s2", setOf("s2", "s2_ask", "s2_ask2")))
    }
}
