package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanNormalizationTest {

    private fun step(id: String, order: Int, action: String = "OPEN_APP") = PlanStep(
        stepId = id,
        order = order,
        description = action,
        action = action
    )

    private fun plan(vararg steps: PlanStep) = Plan(
        planId = "p1",
        goal = "goal",
        estimatedDuration = "1m",
        estimatedSteps = steps.size,
        steps = steps.toList()
    )

    @Test
    fun `duplicate step ids are renamed so every step keeps its own status`() {
        val normalized = normalizePlan(
            plan(step("s1", 1, "OPEN_APP"), step("s1", 2, "SEND_SMS"), step("s1", 3, "MAKE_CALL"))
        )

        assertEquals(listOf("s1", "s1_2", "s1_3"), normalized.steps.map { it.stepId })
        assertEquals(listOf("OPEN_APP", "SEND_SMS", "MAKE_CALL"), normalized.steps.map { it.action })
    }

    @Test
    fun `a renamed duplicate never takes an id another step already has`() {
        val normalized = normalizePlan(
            plan(step("s1", 1), step("s1", 2), step("s1_2", 3))
        )

        assertEquals(3, normalized.steps.map { it.stepId }.toSet().size)
        assertEquals("s1", normalized.steps[0].stepId)
        assertEquals("s1_2", normalized.steps[2].stepId)
    }

    @Test
    fun `a blank id filled from its order does not collide with an explicit one`() {
        val normalized = normalizePlan(plan(step("s2", 1), step("", 2)))

        assertEquals(listOf("s2", "s2_2"), normalized.steps.map { it.stepId })
    }

    @Test
    fun `execution state written by the model is reset`() {
        val claimed = step("s1", 1, "SEND_SMS").copy(
            status = StepStatus.COMPLETED,
            result = "anything",
            error = "nothing"
        )

        val normalized = normalizePlan(plan(claimed)).steps.single()

        assertEquals(StepStatus.PENDING, normalized.status)
        assertEquals(null, normalized.result)
        assertEquals(null, normalized.error)
    }
}
