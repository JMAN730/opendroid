package com.opendroid.ai.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExecutionHistoryPrivacyTest {

    @Test
    fun `plan generation failure description never keeps the chat message`() {
        val message = "Securely remember my locker combination is 4589"

        val description = ExecutionHistoryPrivacy.sanitizeDescription("PLAN_GENERATION", message)

        assertFalse(description, description.contains("4589"))
        assertFalse(description, description.contains("locker"))
    }

    @Test
    fun `sensitive action description is not free text`() {
        val description = ExecutionHistoryPrivacy.sanitizeDescription(
            "SAVE_SENSITIVE_INFO",
            "Save locker combination 45-89"
        )

        assertFalse(description, description.contains("45-89"))
    }

    @Test
    fun `other descriptions pass through`() {
        assertEquals(
            "Set an alarm",
            ExecutionHistoryPrivacy.sanitizeDescription("SET_ALARM", "Set an alarm")
        )
    }
}
