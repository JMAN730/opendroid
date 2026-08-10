package com.opendroid.ai.core.memory

import com.opendroid.ai.data.db.entities.TaskHistoryEntity
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

    private fun history(
        id: Long,
        action: String = "TEST_ACTION",
        params: String = "{}",
        success: Boolean = true,
        timestamp: Long = id
    ) = TaskHistoryEntity(
        id = id,
        stepId = "step-$id",
        planId = "plan-1",
        description = action,
        actionType = action,
        paramsJson = params,
        success = success,
        resultData = "done",
        errorMessage = null,
        timestamp = timestamp
    )
}
