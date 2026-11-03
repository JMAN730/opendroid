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
    fun `settled steps cannot be overwritten`() {
        val rerun = step("s1", "SEND_SMS")

        val result = PlanModification.merge(current, listOf(rerun))

        assertEquals("TOGGLE_DND", result.steps[0].action)
        assertEquals(StepStatus.COMPLETED, result.steps[0].status)
    }

    @Test
    fun `a settled step revised under its own id is not run again`() {
        val revised = done.copy(status = StepStatus.PENDING, params = mapOf("state" to "on"))

        val result = PlanModification.merge(current, listOf(revised))

        assertEquals(current, result.steps)
    }

    @Test
    fun `a settled step echoed back is not run again`() {
        val failed = step("s3", "GET_WEATHER", status = StepStatus.FAILED)
        val settled = listOf(done, failed)
        val echoes = listOf(done.copy(description = "reworded"), failed.copy(status = StepStatus.PENDING))

        val result = PlanModification.merge(settled, echoes)

        assertEquals(settled, result.steps)
        assertFalse(result.revisionLost)
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

    // --- Revisions that reuse a settled stepId (#142) ---

    private val search = step("s1", "WEB_SEARCH", status = StepStatus.COMPLETED)
    private val queuedSms = step("s2", "SEND_SMS", params = mapOf("contact" to "Mom", "message" to "\$\$s1"))
    private fun pendingOf(steps: List<PlanStep>) = steps.filter { it.status == StepStatus.PENDING }

    @Test
    fun `pending step revised under its own id is replaced`() {
        val revised = queuedSms.copy(params = mapOf("contact" to "Mom", "message" to "22C"))

        val merged = PlanModification.merge(listOf(search, queuedSms), listOf(revised)).steps

        assertEquals(listOf("s2"), pendingOf(merged).map { it.stepId })
        assertEquals("22C", pendingOf(merged).single().params["message"])
    }

    @Test
    fun `renumbered revision replaces the matching pending step instead of sending twice`() {
        val renumbered = step("s1", "SEND_SMS", params = mapOf("contact" to "Mom", "message" to "22C"))

        val merged = PlanModification.merge(listOf(search, queuedSms), listOf(renumbered)).steps

        val pending = pendingOf(merged).single()
        assertEquals("s2", pending.stepId)
        assertEquals("22C", pending.params["message"])
        assertEquals(search, merged[0])
    }

    @Test
    fun `a fully renumbered remainder lines up with its pending steps`() {
        val alarm = step("s3", "SET_ALARM", params = mapOf("time" to "07:00"))
        val renumbered = listOf(
            step("s1", "SEND_SMS", params = mapOf("message" to "22C")),
            step("s2", "SET_ALARM", params = mapOf("time" to "08:00"))
        )

        val merged = PlanModification.merge(listOf(search, queuedSms, alarm), renumbered).steps

        assertEquals(listOf("SEND_SMS", "SET_ALARM"), pendingOf(merged).map { it.action })
        assertEquals(listOf("s2", "s3"), pendingOf(merged).map { it.stepId })
        assertEquals("08:00", pendingOf(merged)[1].params["time"])
    }

    @Test
    fun `colliding revision with no matching pending step is re-identified`() {
        val added = step("s1", "SET_ALARM")

        val merged = PlanModification.merge(listOf(search, queuedSms), listOf(added)).steps

        assertEquals(listOf("s1", "s2", "s1_r1"), merged.map { it.stepId })
        assertEquals(StepStatus.PENDING, merged[2].status)
        assertEquals(search, merged[0])
    }

    @Test
    fun `re-identified id avoids ids already in use`() {
        val earlierRetry = step("s1_r1", "GET_WEATHER", status = StepStatus.COMPLETED)

        val merged = PlanModification.merge(listOf(search, earlierRetry), listOf(step("s1", "SET_ALARM"))).steps

        assertEquals("s1_r2", merged.last().stepId)
    }

    @Test
    fun `a takeover cannot leave a step depending on itself`() {
        val renumbered = step("s1", "SEND_SMS").copy(dependsOn = listOf("s2"))

        val merged = PlanModification.merge(listOf(search, queuedSms), listOf(renumbered)).steps

        assertTrue(pendingOf(merged).single().dependsOn.isEmpty())
    }

    @Test
    fun `references between renumbered steps follow them to their new ids`() {
        val weather = step("s2", "GET_WEATHER")
        val sms = step("s3", "SEND_SMS", params = mapOf("message" to "\$\$s2"))
        val renumbered = listOf(
            step("s1", "GET_WEATHER", params = mapOf("city" to "Oslo")),
            step("s2", "SEND_SMS", params = mapOf("message" to "Weather: \$\$s1 (\$s1)")).copy(dependsOn = listOf("s1"))
        )

        val merged = PlanModification.merge(listOf(search, weather, sms), renumbered).steps

        val newSms = merged.single { it.action == "SEND_SMS" }
        assertEquals("s3", newSms.stepId)
        assertEquals(listOf("s2"), newSms.dependsOn)
        assertEquals("Weather: \$\$s2 (\$s2)", newSms.params["message"])
    }

    @Test
    fun `references to a re-identified step follow it to its new id`() {
        val renumbered = listOf(
            step("s1", "SET_ALARM"),
            step("s3", "SEND_SMS", params = mapOf("message" to "\$\$s1")).copy(dependsOn = listOf("s1"))
        )

        val merged = PlanModification.merge(listOf(search), renumbered).steps

        val sms = merged.single { it.action == "SEND_SMS" }
        assertEquals(listOf("s1_r1"), sms.dependsOn)
        assertEquals("\$\$s1_r1", sms.params["message"])
    }

    @Test
    fun `renumbered revision does not undo a step the user changed during the call`() {
        val edited = queuedSms.copy(params = mapOf("contact" to "Dad", "message" to "hi"))
        val renumbered = step("s1", "SEND_SMS", params = mapOf("message" to "22C"))
        val asked = listOf(search, queuedSms)

        assertEquals(
            listOf(edited),
            pendingOf(PlanModification.merge(listOf(search, edited), listOf(renumbered), asked).steps)
        )
        assertTrue(pendingOf(PlanModification.merge(listOf(search), listOf(renumbered), asked).steps).isEmpty())
    }

    @Test
    fun `a sent message echoed with its reference resolved is not queued again`() {
        val sent = queuedSms.copy(status = StepStatus.COMPLETED)
        val alarm = step("s3", "SET_ALARM")
        val resolved = sent.copy(status = StepStatus.PENDING, params = mapOf("contact" to "Mom", "message" to "22C"))

        val merged = PlanModification.merge(listOf(search, sent, alarm), listOf(resolved, alarm)).steps

        assertEquals(listOf(search, sent, alarm), merged)
        assertFalse(needsApproval(AutoMode.OFF, listOf(resolved, alarm), before = listOf(search, sent, alarm)))
    }

    @Test
    fun `a sent message echoed under its own id does not take over another recipient's step`() {
        val sent = queuedSms.copy(status = StepStatus.COMPLETED)
        val toDad = step("s3", "SEND_SMS", params = mapOf("contact" to "Dad", "message" to "hi"))
        val resolved = sent.copy(status = StepStatus.PENDING, params = mapOf("contact" to "Mom", "message" to "22C"))

        val merged = PlanModification.merge(listOf(search, sent, toDad), listOf(resolved, toDad)).steps

        assertEquals(listOf(search, sent, toDad), merged)
    }

    @Test
    fun `a renumbered echo of a sent message is not queued again`() {
        val sent = queuedSms.copy(status = StepStatus.COMPLETED)
        val alarm = step("s3", "SET_ALARM")
        val renumbered = listOf(
            step("s1", "SEND_SMS", params = mapOf("contact" to "Mom", "message" to "22C")),
            alarm.copy(stepId = "s2")
        )

        val merged = PlanModification.merge(listOf(search, sent, alarm), renumbered).steps

        assertEquals(listOf(search, sent, alarm), merged)
    }

    @Test
    fun `re-identified and taken-over steps still go through approval`() {
        val before = listOf(search, queuedSms)

        assertTrue(needsApproval(AutoMode.OFF, listOf(step("s1", "SET_ALARM")), before = before, autoApproved = false))
        assertTrue(
            needsApproval(
                AutoMode.OFF,
                listOf(step("s1", "SEND_SMS", params = mapOf("message" to "22C"))),
                before = before,
                autoApproved = false
            )
        )
    }

    // --- Repairing the step whose action was unknown (#142) ---

    private val bogus = step("s2", "BOGUS", status = StepStatus.FAILED)
    private val afterBogus = step("s3", "SET_ALARM")
    private val failedPlan = listOf(search, bogus, afterBogus)

    @Test
    fun `revision reusing the failed step's id becomes its replacement in place`() {
        val corrected = step("s2", "SUMMARIZE_URL")

        val merged = PlanModification.merge(failedPlan, listOf(corrected, afterBogus), repairing = "s2").steps

        assertEquals(listOf("s1", "s2", "s3"), merged.map { it.stepId })
        assertEquals("SUMMARIZE_URL", merged[1].action)
        assertEquals(StepStatus.PENDING, merged[1].status)
        assertNull(merged[1].error)
    }

    @Test
    fun `repair goes through approval`() {
        val corrected = listOf(step("s2", "SEND_SMS"))
        val after = planOf(PlanModification.merge(failedPlan, corrected, repairing = "s2").steps)

        assertTrue(PlanModification.requiresApproval(AutoMode.OFF, AutoMode.DEFAULT_GRANTS, failedPlan, after, false))
        assertTrue(PlanModification.requiresApproval(AutoMode.AUTO, AutoMode.DEFAULT_GRANTS, failedPlan, after, false))
    }

    @Test
    fun `repair keeping the failed action does not requeue it`() {
        val unregistered = step("s2", "SET_ALARM", status = StepStatus.FAILED)
        val retried = step("s2", "SET_ALARM", params = mapOf("time" to "07:00"))

        val merged = PlanModification.merge(listOf(search, unregistered), listOf(retried), repairing = "s2").steps

        assertEquals(listOf(search, unregistered), merged)
    }

    @Test
    fun `only a failed step can be repaired`() {
        val completedId = step("s1", "SUMMARIZE_URL")

        val merged = PlanModification.merge(listOf(search, afterBogus), listOf(completedId), repairing = "s1").steps

        assertEquals(search, merged[0])
        assertEquals("s1_r1", merged.last().stepId)
    }

    // --- A revision with nothing left in it (#142) ---

    @Test
    fun `revision whose every step was rejected is reported lost`() {
        val result = PlanModification.merge(listOf(search, queuedSms), listOf(step("s2", "NOT_A_REAL_ACTION")))

        assertTrue(result.revisionLost)
        assertEquals(listOf(search, queuedSms), result.steps)
    }

    @Test
    fun `failed repair that names another unknown action is reported lost`() {
        val result = PlanModification.merge(failedPlan, listOf(step("s2", "ALSO_BOGUS")), repairing = "s2")

        assertTrue(result.revisionLost)
    }

    @Test
    fun `revision is not lost when any step survives or nothing was asked`() {
        val mixed = listOf(step("s3", "GET_WEATHER"), step("s4", "NOT_A_REAL_ACTION"))

        assertFalse(PlanModification.merge(current, mixed).revisionLost)
        assertFalse(PlanModification.merge(current, emptyList()).revisionLost)
    }

    @Test
    fun `dropping an answer about a step the user changed is not a lost revision`() {
        val rewritten = queuedSms.copy(params = mapOf("message" to "22C"))

        val result = PlanModification.merge(listOf(search), listOf(rewritten), asked = listOf(search, queuedSms))

        assertFalse(result.revisionLost)
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
    fun `reference to a completed step is kept when its id is reused by the revised step`() {
        val search = step("s1", "WEB_SEARCH", status = StepStatus.COMPLETED)
        val sms = step("s2", "SEND_SMS", params = mapOf("contact" to "+15550100", "message" to "\$\$s1"))
        val revised = step("s1", "SEND_SMS", params = mapOf("contact" to "+15550100", "message" to "\$\$s1"))

        val merged = PlanModification.merge(listOf(search, sms), listOf(revised)).steps

        assertEquals("s2", merged[1].stepId)
        assertEquals("\$\$s1", merged[1].params["message"])
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
