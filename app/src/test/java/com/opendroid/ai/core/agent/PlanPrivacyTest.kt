package com.opendroid.ai.core.agent

import com.opendroid.ai.core.memory.ExecutionHistoryPrivacy
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPrivacyTest {

    @Test
    fun `save sensitive info declares its secret param`() {
        assertEquals(setOf("secret"), ActionSchema.getAction("SAVE_SENSITIVE_INFO")!!.sensitiveParams)
    }

    @Test
    fun `redacting leaves the executable plan untouched`() {
        val plan = planWithSecret()

        PlanPrivacy.redactPlan(plan)

        assertEquals(SECRET, plan.steps.first().params["secret"])
    }

    @Test
    fun `plan without sensitive steps is returned as is`() {
        val plan = planWithSecret().copy(steps = listOf(smsStep("hello")))

        assertSame(plan, PlanPrivacy.redactPlan(plan))
    }

    @Test
    fun `step that has run forgets its secret`() {
        val updated = PlanPrivacy.forgetStepSecrets(planWithSecret(), "s1")

        val step = updated.steps.first { it.stepId == "s1" }
        assertEquals(ExecutionHistoryPrivacy.REDACTED, step.params["secret"])
        assertEquals("locker", step.params["key"])
        assertFalse(step.description.contains(SECRET))
        assertFalse(updated.goal.contains(SECRET))
        assertTrue(updated.goal.contains("locker combination"))
    }

    @Test
    fun `later steps keep their params until they run`() {
        val updated = PlanPrivacy.forgetStepSecrets(planWithSecret(), "s1")

        assertEquals("The code is $SECRET", updated.steps.first { it.stepId == "s2" }.params["message"])
    }

    @Test
    fun `secret of a step that has run is still scrubbed from later steps`() {
        val redacted = PlanPrivacy.redactPlan(PlanPrivacy.forgetStepSecrets(planWithSecret(), "s1"))

        assertEquals(
            "The code is ${ExecutionHistoryPrivacy.REDACTED}",
            redacted.steps.first { it.stepId == "s2" }.params["message"]
        )
    }

    @Test
    fun `redacted plan keeps no retired secrets`() {
        val redacted = PlanPrivacy.redactPlan(PlanPrivacy.forgetStepSecrets(planWithSecret(), "s1"))

        assertFalse(redacted.toString(), redacted.toString().contains(SECRET))
    }

    @Test
    fun `retired secrets survive the plan copies a replan makes`() {
        val forgotten = PlanPrivacy.forgetStepSecrets(planWithSecret(), "s1")

        val merged = forgotten.copy(steps = forgotten.steps.filter { it.stepId == "s2" })

        assertFalse(PlanPrivacy.redactPlan(merged).toString().contains(SECRET))
    }

    @Test
    fun `retired secrets are never serialized`() {
        val plan = planWithSecret().let { it.copy(goal = "Remember it", steps = it.steps.take(1)) }

        val encoded = Json.encodeToString(PlanPrivacy.forgetStepSecrets(plan, "s1"))

        assertFalse(encoded, encoded.contains(SECRET))
    }

    @Test
    fun `forgetting a step with no secrets changes nothing`() {
        val plan = planWithSecret()

        assertSame(plan, PlanPrivacy.forgetStepSecrets(plan, "s2"))
    }

    @Test
    fun `follow-up params for the last action drop the secret`() {
        val params = PlanPrivacy.redactParams(
            "SAVE_SENSITIVE_INFO",
            mapOf("key" to "locker", "secret" to SECRET)
        )

        assertEquals(mapOf("key" to "locker", "secret" to ExecutionHistoryPrivacy.REDACTED), params)
    }

    @Test
    fun `ordinary action params are not redacted`() {
        val params = mapOf("contact" to "Sam", "message" to "hello")

        assertEquals(params, PlanPrivacy.redactParams("SEND_SMS", params))
    }

    @Test
    fun `secret that is a substring of the placeholder does not mangle it`() {
        val plan = planWithSecret().let { base ->
            base.copy(
                steps = base.steps.map { step ->
                    if (step.stepId == "s1") step.copy(params = mapOf("key" to "locker", "secret" to "ED")) else step
                }
            )
        }

        val redacted = PlanPrivacy.redactPlan(plan)

        assertEquals(ExecutionHistoryPrivacy.REDACTED, redacted.steps.first { it.stepId == "s1" }.params["secret"])
    }

    private fun planWithSecret() = Plan(
        planId = "p1",
        goal = "Remember my locker combination is $SECRET",
        estimatedDuration = "instant",
        estimatedSteps = 2,
        steps = listOf(
            PlanStep(
                stepId = "s1",
                order = 1,
                description = "Save combination $SECRET",
                action = "SAVE_SENSITIVE_INFO",
                params = mapOf("key" to "locker", "secret" to SECRET)
            ),
            smsStep("The code is $SECRET")
        )
    )

    private fun smsStep(message: String) = PlanStep(
        stepId = "s2",
        order = 2,
        description = "Text Sam",
        action = "SEND_SMS",
        params = mapOf("contact" to "Sam", "message" to message)
    )

    private companion object {
        const val SECRET = "4589"
    }
}
