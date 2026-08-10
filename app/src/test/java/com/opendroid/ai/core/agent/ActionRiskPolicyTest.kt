package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRiskPolicyTest {
    @Test
    fun `catalog derives irreversible and advanced risks`() {
        assertEquals(ActionRisk.IRREVERSIBLE, ActionSchema.riskForAction("DELETE_FILE"))
        assertEquals(ActionRisk.ADVANCED_CONTROL, ActionSchema.riskForAction("CLICK_TEXT"))
        assertEquals(ActionRisk.SENSITIVE, ActionSchema.riskForAction("SEND_SMS"))
        assertEquals(ActionRisk.READ_ONLY, ActionSchema.riskForAction("WEB_SEARCH"))
    }

    @Test
    fun `highest risk is used for a plan`() {
        assertEquals(
            ActionRisk.IRREVERSIBLE,
            ActionSchema.highestRisk(listOf("WEB_SEARCH", "DELETE_FILE"))
        )
    }

    @Test
    fun `automatic fallback is limited to low impact plans`() {
        assertTrue(ActionRiskPolicy.allowsAutomaticFallback(ActionRisk.READ_ONLY))
        assertTrue(ActionRiskPolicy.allowsAutomaticFallback(ActionRisk.REVERSIBLE))
        assertFalse(ActionRiskPolicy.allowsAutomaticFallback(ActionRisk.SENSITIVE))
        assertFalse(ActionRiskPolicy.allowsAutomaticFallback(ActionRisk.ADVANCED_CONTROL))
        assertFalse(ActionRiskPolicy.allowsAutomaticFallback(ActionRisk.IRREVERSIBLE))
    }
}
