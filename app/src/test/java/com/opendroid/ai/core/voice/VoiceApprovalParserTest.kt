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
    fun `negated approvals never approve and leave the plan waiting`() {
        for (u in listOf(
            "don't approve",
            "do not approve that",
            "don't go ahead",
            "don't let it run",
            "I don't want it to run",
            "never run that",
            "not yet, run it later",
            "nope, go ahead later"
        )) {
            assertEquals(u, VoiceApprovalIntent.NONE, VoiceApprovalParser.parse(u))
        }
    }

    @Test
    fun `refusals in other languages never approve`() {
        for (u in listOf(
            "run mat karo",
            "nahi, run mat karo",
            "approve mat karo",
            "isko execute mat karo",
            "nahi yaar, go ahead mat karna",
            "Não, não execute",
            "run nako",
            "nicht ausführen, run nicht"
        )) {
            assertEquals(u, VoiceApprovalIntent.NONE, VoiceApprovalParser.parse(u))
        }
        for (u in listOf("no gracias", "no lo ejecutes")) {
            assertEquals(u, VoiceApprovalIntent.REJECT, VoiceApprovalParser.parse(u))
        }
    }

    @Test
    fun `anything beyond a bare affirmative never approves`() {
        for (u in listOf("run it later", "do it tomorrow", "yes but wait", "go ahead with something else")) {
            assertEquals(u, VoiceApprovalIntent.NONE, VoiceApprovalParser.parse(u))
        }
    }

    @Test
    fun `bare affirmatives approve with punctuation, fillers and native words`() {
        for (u in listOf(
            "Yes.",
            "Go ahead!",
            "yes, run it",
            "ok, approve",
            "go ahead and run it",
            "please do it now",
            "approve the plan",
            "haan",
            "sí",
            "si",
            "sim"
        )) {
            assertEquals(u, VoiceApprovalIntent.APPROVE, VoiceApprovalParser.parse(u))
        }
    }

    @Test
    fun `don't run still rejects with a curly or missing apostrophe`() {
        for (u in listOf("don’t run that", "dont run that", "don’t do it", "dont execute")) {
            assertEquals(u, VoiceApprovalIntent.REJECT, VoiceApprovalParser.parse(u))
        }
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
