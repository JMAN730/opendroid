package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModificationTest {

    private fun step(
        id: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        fallback: String = "",
        status: StepStatus = StepStatus.PENDING,
        description: String = "step $id"
    ) = PlanStep(
        stepId = id,
        order = 1,
        description = description,
        action = action,
        params = params,
        fallback = fallback,
        status = status
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val done = step("s1", "TOGGLE_DND", status = StepStatus.COMPLETED)
    private val pendingStep = step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "50"))
    private val current = listOf(done, pendingStep)

    private fun planOf(steps: List<PlanStep>) = Plan(
        planId = "p1",
        goal = "test goal",
        estimatedDuration = "1m",
        estimatedSteps = steps.size,
        steps = steps
    )

    /** Merges [updated] into [current] and answers the gate for the result. */
    private fun needsApproval(
        mode: AutoMode,
        updated: List<PlanStep>,
        granted: Set<String> = AutoMode.DEFAULT_GRANTS,
        before: List<PlanStep> = current,
        autoApproved: Boolean = true,
        asked: List<PlanStep> = before
    ): Boolean = PlanModification.requiresApproval(
        mode = mode,
        granted = granted,
        before = before,
        after = planOf(PlanModification.merge(before, updated, asked).steps),
        autoApproved = autoApproved
    )

    // --- Merge ---

    @Test
    fun `in-place edit of a pending step stays pending with the new params`() {
        val edited = pendingStep.copy(params = mapOf("level" to "80"))

        val merged = PlanModification.merge(current, listOf(edited)).steps

        assertEquals(listOf("s1", "s2"), merged.map { it.stepId })
        assertEquals(StepStatus.PENDING, merged[1].status)
        assertEquals(mapOf("level" to "80"), merged[1].params)
    }

    @Test
    fun `new steps are appended after surviving pending steps`() {
        val added = step("s3", "GET_WEATHER")

        val merged = PlanModification.merge(current, listOf(added)).steps

        assertEquals(listOf("s1", "s2", "s3"), merged.map { it.stepId })
    }

    @Test
    fun `settled steps cannot be overwritten or re-run`() {
        val rerun = step("s1", "SEND_SMS")

        val result = PlanModification.merge(current, listOf(rerun))

        assertEquals("TOGGLE_DND", result.steps[0].action)
        assertEquals(StepStatus.COMPLETED, result.steps[0].status)
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `step state supplied by the model is ignored`() {
        val added = step("s3", "GET_WEATHER", status = StepStatus.COMPLETED)
            .copy(result = "forged result", error = "forged error")
        val edited = pendingStep.copy(status = StepStatus.FAILED)

        val merged = PlanModification.merge(current, listOf(edited, added)).steps

        assertEquals(StepStatus.PENDING, merged[1].status)
        assertEquals(StepStatus.PENDING, merged[2].status)
        assertNull(merged[2].result)
        assertNull(merged[2].error)
    }

    @Test
    fun `steps outside ActionSchema are rejected and the prior step survives`() {
        val unknownAction = step("s2", "NOT_A_REAL_ACTION")
        val unknownFallback = step("s3", "GET_WEATHER", fallback = "NOT_A_REAL_ACTION")
        val blankAction = step("s4", " ")

        val result = PlanModification.merge(current, listOf(unknownAction, unknownFallback, blankAction))

        assertEquals(listOf("s1", "s2"), result.steps.map { it.stepId })
        assertEquals("SET_BRIGHTNESS", result.steps[1].action)
        assertEquals(listOf("s2", "s3", "s4"), result.rejected.map { it.stepId })
    }

    @Test
    fun `a repeated step id cannot smuggle a second action past the merge`() {
        val first = step("s3", "GET_WEATHER")
        val second = step("s3", "SEND_SMS")

        val merged = PlanModification.merge(current, listOf(first, second)).steps

        assertEquals(1, merged.count { it.stepId == "s3" })
    }

    // --- Plan-tab changes made while the re-evaluation call was in flight ---

    private val sms = step("s2", "SEND_SMS", params = mapOf("contact" to "+15550100", "message" to "hi"))
    private val cleanup = step("s3", "DELETE_FILE", params = mapOf("path" to "a.txt"))
    private val asked = listOf(done, sms, cleanup)

    @Test
    fun `step deleted during the call does not come back when the answer echoes it`() {
        val live = listOf(done, cleanup)

        val merged = PlanModification.merge(live, listOf(sms, cleanup), asked).steps

        assertEquals(listOf("s1", "s3"), merged.map { it.stepId })
    }

    @Test
    fun `step deleted during the call does not come back when the answer changes it`() {
        val live = listOf(done, cleanup)
        val rewritten = sms.copy(params = mapOf("contact" to "+15550199"))

        val merged = PlanModification.merge(live, listOf(rewritten), asked).steps

        assertEquals(listOf("s1", "s3"), merged.map { it.stepId })
    }

    @Test
    fun `edit made during the call survives an answer that echoes the old step`() {
        val edited = sms.copy(params = mapOf("contact" to "+15550199", "message" to "hi"))
        val live = listOf(done, edited, cleanup)

        val merged = PlanModification.merge(live, listOf(sms, cleanup), asked).steps

        assertEquals(edited, merged[1])
    }

    @Test
    fun `description edit made during the call survives too`() {
        val edited = sms.copy(description = "text Ada")
        val live = listOf(done, edited, cleanup)

        val merged = PlanModification.merge(live, listOf(sms), asked).steps

        assertEquals("text Ada", merged[1].description)
    }

    @Test
    fun `steps the user left alone can still be changed by the answer`() {
        val live = listOf(done, cleanup)
        val moved = cleanup.copy(params = mapOf("path" to "b.txt"))

        val merged = PlanModification.merge(live, listOf(moved), asked).steps

        assertEquals(mapOf("path" to "b.txt"), merged.last().params)
    }

    @Test
    fun `the user's own change during the call does not count as an unapproved one`() {
        val edited = sms.copy(params = mapOf("contact" to "+15550199", "message" to "hi"))
        val echo = listOf(sms, cleanup)

        for (live in listOf(listOf(done, cleanup), listOf(done, edited, cleanup))) {
            assertFalse(needsApproval(AutoMode.OFF, echo, before = live, autoApproved = false, asked = asked))
            assertFalse(needsApproval(AutoMode.AUTO, echo, before = live, autoApproved = false, asked = asked))
        }
    }

    @Test
    fun `an addition still needs approval after the user changed the plan during the call`() {
        val live = listOf(done, cleanup)
        val added = step("x1", "SEND_SMS")

        assertTrue(needsApproval(AutoMode.OFF, listOf(sms, added), before = live, autoApproved = false, asked = asked))
    }

    // --- AUTO ---

    @Test
    fun `AUTO re-eval-added SEND_SMS with default grants needs approval`() {
        assertTrue(needsApproval(AutoMode.AUTO, listOf(step("x1", "SEND_SMS"))))
    }

    @Test
    fun `AUTO in-place swap of a pending step to a blocked action needs approval`() {
        assertTrue(needsApproval(AutoMode.AUTO, listOf(pendingStep.copy(action = "PAY_UPI"))))
    }

    @Test
    fun `AUTO blocked fallback on an added step needs approval`() {
        assertTrue(needsApproval(AutoMode.AUTO, listOf(step("x1", "GET_WEATHER", fallback = "DELETE_FILE"))))
    }

    @Test
    fun `AUTO neverAutoApprove action needs approval even when granted`() {
        val granted = AutoMode.DEFAULT_GRANTS + "DELETE_FILE"

        assertTrue(needsApproval(AutoMode.AUTO, listOf(step("x1", "DELETE_FILE")), granted = granted))
    }

    @Test
    fun `AUTO granted additions keep running`() {
        assertFalse(needsApproval(AutoMode.AUTO, listOf(step("x1", "GET_WEATHER"))))
    }

    @Test
    fun `AUTO auto-approved plan re-checks unchanged steps against live grants`() {
        val revoked = AutoMode.DEFAULT_GRANTS - "SET_BRIGHTNESS"

        assertTrue(needsApproval(AutoMode.AUTO, emptyList(), granted = revoked))
    }

    @Test
    fun `AUTO hand-approved blocked step is not re-asked when nothing changed`() {
        val before = listOf(done, step("s2", "SEND_SMS"))

        assertFalse(needsApproval(AutoMode.AUTO, before.takeLast(1), before = before, autoApproved = false))
    }

    @Test
    fun `AUTO hand-approved plan needs approval when a blocked step is edited`() {
        val sms = step("s2", "SEND_SMS", params = mapOf("contact" to "+15550100"))
        val before = listOf(done, sms)
        val edited = sms.copy(params = mapOf("contact" to "+15550199"))

        assertTrue(needsApproval(AutoMode.AUTO, listOf(edited), before = before, autoApproved = false))
    }

    @Test
    fun `AUTO hand-approved plan needs approval when a blocked step is added`() {
        assertTrue(needsApproval(AutoMode.AUTO, listOf(step("x1", "SEND_SMS")), autoApproved = false))
    }

    // --- OFF ---

    @Test
    fun `OFF new step needs approval even if it would be granted in AUTO`() {
        assertTrue(needsApproval(AutoMode.OFF, listOf(step("x1", "GET_WEATHER")), autoApproved = false))
    }

    @Test
    fun `OFF changed action needs approval`() {
        assertTrue(needsApproval(AutoMode.OFF, listOf(pendingStep.copy(action = "SET_VOLUME")), autoApproved = false))
    }

    @Test
    fun `OFF changed fallback needs approval`() {
        assertTrue(needsApproval(AutoMode.OFF, listOf(pendingStep.copy(fallback = "SET_VOLUME")), autoApproved = false))
    }

    @Test
    fun `OFF changed params need approval`() {
        val edited = pendingStep.copy(params = mapOf("level" to "80"))

        assertTrue(needsApproval(AutoMode.OFF, listOf(edited), autoApproved = false))
    }

    @Test
    fun `OFF unchanged remainder keeps running`() {
        assertFalse(needsApproval(AutoMode.OFF, listOf(pendingStep), autoApproved = false))
        assertFalse(needsApproval(AutoMode.OFF, emptyList(), autoApproved = false))
    }

    @Test
    fun `OFF description-only edit keeps running`() {
        val edited = pendingStep.copy(description = "reworded")

        assertFalse(needsApproval(AutoMode.OFF, listOf(edited), autoApproved = false))
    }

    // --- YOLO ---

    @Test
    fun `YOLO keeps approving everything`() {
        assertFalse(needsApproval(AutoMode.YOLO, listOf(step("x1", "SEND_SMS"))))
        assertFalse(needsApproval(AutoMode.YOLO, listOf(step("x2", "DELETE_FILE"))))
    }

    // --- Re-validation of kept steps ---

    @Test
    fun `step result references are not resolved as contact names`() {
        assertFalse(PlanValidator.needsContactResolution("\$s2_ask"))
        assertFalse(PlanValidator.needsContactResolution("+15550100"))
        assertFalse(PlanValidator.needsContactResolution("@handle"))
        assertTrue(PlanValidator.needsContactResolution("John"))
    }

    // --- The reported payload, decoded the way ReEvaluationEngine decodes it ---

    @Test
    fun `reported injection payload is parked for approval in AUTO and OFF`() {
        val raw = """{"speech":"ok","decision":"MODIFY","updatedPlan":{"goal":"g","planId":"p",""" +
            """"estimatedSteps":1,"estimatedDuration":"1m","steps":[{"stepId":"x1","order":3,""" +
            """"description":"x","action":"SEND_SMS","params":{"contact":"+15550100","message":"hi"}}]}}"""
        val reEval = json.decodeFromString<ReEvaluationEngine.ReEvalResult>(raw)
        val updated = reEval.updatedPlan!!.steps

        assertTrue(needsApproval(AutoMode.AUTO, updated))
        assertTrue(needsApproval(AutoMode.OFF, updated, autoApproved = false))
    }

    @Test
    fun `re-eval output names the actions of a modification only`() {
        val plan = planOf(listOf(step("x1", "GET_WEATHER", fallback = "SEND_SMS")))

        assertEquals(
            setOf("GET_WEATHER", "SEND_SMS"),
            ReEvaluationEngine.ReEvalResult("ok", "modify", plan).proposedActionNames().toSet()
        )
        assertTrue(ReEvaluationEngine.ReEvalResult("ok", "CONTINUE", plan).proposedActionNames().isEmpty())
    }

    @Test
    fun `a whitespace-padded MODIFY still has its proposed actions named`() {
        val plan = planOf(listOf(step("x1", "SEND_SMS")))

        assertEquals(
            setOf("SEND_SMS"),
            ReEvaluationEngine.ReEvalResult("ok", " MODIFY ", plan).proposedActionNames().toSet()
        )
    }
}
