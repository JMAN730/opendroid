package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * neverAutoApprove flag: actions that move money or have irreversible
 * consequences must always hit the approval modal in Auto mode.
 */
class NeverAutoApproveTest {

    @Test
    fun `exactly the spec's ten actions are flagged`() {
        val flagged = ActionSchema.ALL_ACTIONS.filter { it.neverAutoApprove }.map { it.name }.toSet()
        assertEquals(
            setOf(
                // Money
                "PAY_UPI", "ORDER_FOOD", "ORDER_GROCERY", "BOOK_UBER", "BOOK_OLA",
                // Irreversible / destructive
                "INSTALL_APP", "RESTART_DEVICE", "DELETE_FILE", "CLEAR_BROWSER_DATA", "LOCK_DOOR"
            ),
            flagged
        )
    }

    @Test
    fun `communication sends stay grantable`() {
        assertFalse(ActionSchema.isNeverAutoApprove("SEND_SMS"))
        assertFalse(ActionSchema.isNeverAutoApprove("SEND_EMAIL"))
        assertFalse(ActionSchema.isNeverAutoApprove("SEND_WHATSAPP"))
    }

    @Test
    fun `lookup helper matches the flag and tolerates unknown actions`() {
        assertTrue(ActionSchema.isNeverAutoApprove("PAY_UPI"))
        assertFalse(ActionSchema.isNeverAutoApprove("WEB_SEARCH"))
        assertFalse(ActionSchema.isNeverAutoApprove("NOT_A_REAL_ACTION"))
    }
}
