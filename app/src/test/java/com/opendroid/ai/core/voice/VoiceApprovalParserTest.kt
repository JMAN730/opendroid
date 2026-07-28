package com.opendroid.ai.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceApprovalParserTest {

    @Test
    fun `approve terms`() {
        for (u in listOf("approve", "Approve it", "yes", "yes please", "run", "run it", "go ahead", "okay do it")) {
            assertEquals(u, VoiceApprovalIntent.APPROVE, VoiceApprovalParser.parse(u))
        }
    }

    @Test
    fun `reject terms`() {
        for (u in listOf("cancel", "no", "No thanks", "stop", "reject", "don't run that", "do not run it")) {
            assertEquals(u, VoiceApprovalIntent.REJECT, VoiceApprovalParser.parse(u))
        }
    }

    @Test
    fun `reject wins over approve in mixed utterances`() {
        assertEquals(VoiceApprovalIntent.REJECT, VoiceApprovalParser.parse("no, don't run it"))
        assertEquals(VoiceApprovalIntent.REJECT, VoiceApprovalParser.parse("yes actually cancel that"))
    }

    @Test
    fun `word boundaries - substrings do not match`() {
        // "know" contains "no", "canceled culture" is contrived but "runway" contains "run"
        assertEquals(VoiceApprovalIntent.NONE, VoiceApprovalParser.parse("I don't know"))
        assertEquals(VoiceApprovalIntent.NONE, VoiceApprovalParser.parse("show me the runway"))
    }

    @Test
    fun `anything else is a no-op`() {
        assertEquals(VoiceApprovalIntent.NONE, VoiceApprovalParser.parse("what's the weather"))
        assertEquals(VoiceApprovalIntent.NONE, VoiceApprovalParser.parse(""))
    }
}
