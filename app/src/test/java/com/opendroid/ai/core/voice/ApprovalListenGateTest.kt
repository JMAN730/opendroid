package com.opendroid.ai.core.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalListenGateTest {

    @Test
    fun `an earlier utterance finishing does not open the mic for the approval prompt`() {
        val gate = ApprovalListenGate()
        gate.armFor("approval-prompt")

        assertFalse(gate.onUtteranceDone("earlier-reply"))
        assertTrue(gate.onUtteranceDone("approval-prompt"))
    }

    @Test
    fun `the gate opens once per armed prompt`() {
        val gate = ApprovalListenGate()
        gate.armFor("approval-prompt")

        assertTrue(gate.onUtteranceDone("approval-prompt"))
        assertFalse(gate.onUtteranceDone("approval-prompt"))
    }

    @Test
    fun `a disarmed gate never opens`() {
        val gate = ApprovalListenGate()
        gate.armFor("approval-prompt")
        gate.disarm()

        assertFalse(gate.onUtteranceDone("approval-prompt"))
    }
}
