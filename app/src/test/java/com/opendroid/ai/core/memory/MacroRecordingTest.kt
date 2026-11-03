package com.opendroid.ai.core.memory

import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRecordingTest {
    @Test
    fun `completed task serialization is stable and redacts credentials`() {
        val first = history(
            id = 1,
            action = "CALL_API",
            params = "{\"z\":\"ordinary\",\"apiKey\":\"not-a-real-key\"}",
            timestamp = 20
        )
        val second = history(
            id = 2,
            action = "SHOW_RESULT",
            params = "{\"token\":\"sk-test-secret-value\",\"a\":\"ok\"}",
            timestamp = 10
        )

        val jsonOne = MacroRecording.serializeCompletedTask(listOf(first, second))
        val jsonTwo = MacroRecording.serializeCompletedTask(
            listOf(
                second.copy(paramsJson = "{\"a\":\"ok\",\"token\":\"sk-test-secret-value\"}"),
                first.copy(paramsJson = "{\"apiKey\":\"not-a-real-key\",\"z\":\"ordinary\"}")
            )
        )

        assertEquals(jsonOne, jsonTwo)
        assertTrue(jsonOne!!.contains("recorded-step-1"))
        assertTrue(jsonOne.indexOf("SHOW_RESULT") < jsonOne.indexOf("CALL_API"))
        assertFalse(jsonOne.contains("sk-test-secret-value"))
        assertFalse(jsonOne.contains("not-a-real-key"))
        assertTrue(jsonOne.contains("[REDACTED]"))
    }

    @Test
    fun `failed or malformed history cannot be saved as a macro`() {
        assertNull(MacroRecording.serializeCompletedTask(listOf(history(1, success = false))))
        assertNull(
            MacroRecording.serializeCompletedTask(
                listOf(history(1, params = "not-json"))
            )
        )
    }

    @Test
    fun `one run of a plan stays together`() {
        val run = listOf(
            history(1, stepId = "s1", timestamp = 1_000),
            history(2, stepId = "s2", timestamp = 3_000),
            history(3, stepId = "s3", timestamp = 9 * 60_000L)
        )

        assertEquals(listOf(run), MacroRecording.runs(run))
    }

    @Test
    fun `a repeated stepId starts a new run under a shared planId`() {
        val rows = listOf(
            history(1, stepId = "s1", timestamp = 1_000),
            history(2, stepId = "s2", timestamp = 2_000),
            history(3, stepId = "s1", timestamp = 3_000)
        )

        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), MacroRecording.runs(rows).map { run -> run.map { it.id } })
    }

    @Test
    fun `a long gap starts a new run even when stepIds differ`() {
        val rows = listOf(
            history(1, stepId = "s1", timestamp = 0),
            history(2, stepId = "step_1", timestamp = 2 * 86_400_000L)
        )

        assertEquals(listOf(1L), MacroRecording.runContaining(rows, 1).map { it.id })
        assertEquals(listOf(2L), MacroRecording.runContaining(rows, 2).map { it.id })
    }

    @Test
    fun `only all-successful runs of real plans are saveable`() {
        val rows = listOf(
            history(1, stepId = "s1", timestamp = 0),
            history(2, stepId = "s1", timestamp = 86_400_000L, success = false),
            history(3, stepId = "plan-gen", timestamp = 0, planId = "n/a")
        )

        assertEquals(listOf(listOf(1L)), MacroRecording.saveableRuns(rows).map { run -> run.map { it.id } })
    }

    @Test
    fun `a repaired step keeps an app-minted task in one unsaveable run`() {
        val planId = Plan.newId()
        val rows = listOf(
            history(1, action = "TOGGLE_WIFI", stepId = "s1", timestamp = 1_000, planId = planId),
            history(2, action = "BOGUS_ACTION", stepId = "s2", timestamp = 2_000, planId = planId, success = false),
            history(3, action = "SEND_SMS", stepId = "s2", timestamp = 3_000, planId = planId),
            history(4, action = "MAKE_CALL", stepId = "s3", timestamp = 4_000, planId = planId)
        )

        assertEquals(listOf(listOf(1L, 2L, 3L, 4L)), MacroRecording.runs(rows).map { run -> run.map { it.id } })
        assertTrue(MacroRecording.saveableRuns(rows).isEmpty())
    }

    @Test
    fun `a repaired step under a model-written planId stays in the failed run`() {
        val rows = listOf(
            history(1, stepId = "s1", timestamp = 1_000),
            history(2, stepId = "s2", timestamp = 2_000, success = false),
            history(3, stepId = "s2", timestamp = 3_000),
            history(4, stepId = "s3", timestamp = 4_000)
        )

        assertEquals(listOf(listOf(1L, 2L, 3L, 4L)), MacroRecording.runs(rows).map { run -> run.map { it.id } })
        assertTrue(MacroRecording.saveableRuns(rows).isEmpty())
    }

    @Test
    fun `a long pause does not split an app-minted task`() {
        val planId = Plan.newId()
        val rows = listOf(
            history(1, stepId = "s1", timestamp = 0, planId = planId),
            history(2, stepId = "s2", timestamp = 1_000, planId = planId),
            history(3, stepId = "s3", timestamp = 15 * 60_000L, planId = planId)
        )

        assertEquals(listOf(listOf(1L, 2L, 3L)), MacroRecording.saveableRuns(rows).map { run -> run.map { it.id } })
        assertEquals(listOf(1L, 2L, 3L), MacroRecording.runContaining(rows, 1).map { it.id })
    }

    private fun history(
        id: Long,
        action: String = "TEST_ACTION",
        params: String = "{}",
        success: Boolean = true,
        timestamp: Long = id,
        stepId: String = "step-$id",
        planId: String = "plan-1"
    ) = TaskHistoryEntity(
        id = id,
        stepId = stepId,
        planId = planId,
        description = action,
        actionType = action,
        paramsJson = params,
        success = success,
        resultData = "done",
        errorMessage = null,
        timestamp = timestamp
    )
}
