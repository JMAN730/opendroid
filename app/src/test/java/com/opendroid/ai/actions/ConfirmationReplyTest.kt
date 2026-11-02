package com.opendroid.ai.actions

import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationReplyTest {

    @Test
    fun `refusals that contain an affirmative substring are not confirmations`() {
        listOf(
            "I disagree",
            "wait, let me look at it first",
            "do it later",
            "I'll do it myself later",
            "I already told him yesterday",
            "I'm unsure",
            "are you sure?"
        ).forEach { reply ->
            assertFalse(reply, ConfirmationReply.parse(reply) == ConfirmationReply.Confirmed)
        }
    }

    @Test
    fun `plain affirmatives confirm`() {
        listOf("yes", "ok", "sure", "go ahead", "do it", "Yes, please!", "OK do it now", "yeah go ahead").forEach { reply ->
            assertEquals(reply, ConfirmationReply.Confirmed, ConfirmationReply.parse(reply))
        }
    }

    @Test
    fun `negators and deferrals decline, whatever else the reply says`() {
        listOf(
            "no", "No.", "cancel", "not now", "wait", "hold on", "never mind", "later", "tomorrow",
            "yes but not now", "don't", "don’t do it", "I disagree", "I'm unsure", "let me check",
            "nahi", "mat karo", "não", "nein", "नहीं"
        ).forEach { reply ->
            assertEquals(reply, ConfirmationReply.Declined, ConfirmationReply.parse(reply))
        }
    }

    @Test
    fun `anything else is unclear`() {
        listOf("", "   ", "?", "I already told him yesterday", "are you sure?", "banana", "okay-ish").forEach { reply ->
            assertEquals(reply, ConfirmationReply.Unclear, ConfirmationReply.parse(reply))
        }
    }

    @Test
    fun `a decline returns the dedicated result without asking again`() = runBlocking {
        val prompts = mutableListOf<String>()

        val result = askForConfirmation("Send it?") { prompts += it; "I disagree" }

        assertTrue(result is ActionResult.Declined)
        assertFalse(result.success)
        assertEquals(1, prompts.size)
    }

    @Test
    fun `a confirmation succeeds`() = runBlocking {
        val result = askForConfirmation("Send it?") { "go ahead" }

        assertTrue(result.success)
        assertEquals("User confirmed: go ahead", result.data)
    }

    @Test
    fun `an unclear reply is asked once more and can still confirm`() = runBlocking {
        val replies = ArrayDeque(listOf("are you sure?", "yes"))
        val prompts = mutableListOf<String>()

        val result = askForConfirmation("Send it?") { prompts += it; replies.removeFirst() }

        assertTrue(result.success)
        assertEquals(2, prompts.size)
        assertEquals("Send it?", prompts.first())
        assertTrue(prompts.last(), prompts.last().contains("Send it?"))
    }

    @Test
    fun `two unclear replies decline`() = runBlocking {
        val prompts = mutableListOf<String>()

        val result = askForConfirmation("Send it?") { prompts += it; "banana" }

        assertTrue(result is ActionResult.Declined)
        assertEquals(2, prompts.size)
    }

    @Test
    fun `a cancel answer to a plain question declines instead of becoming the answer`() {
        listOf("cancel", "Cancel.", "no", "stop", "never mind", "forget it", "not now").forEach { reply ->
            assertTrue(reply, askUserResult(reply) is ActionResult.Declined)
        }
    }

    @Test
    fun `a refusal with filler or a second refusal is still nothing but a refusal`() {
        listOf(
            "no, cancel", "no cancel", "please cancel", "cancel please", "stop please", "no, stop",
            "no no", "no, thank you", "no thanks, cancel it", "cancel, thank you", "just stop",
            "stop now", "not now please", "no, don't", "don't do it", "no, do not do it", "nahi, cancel"
        ).forEach { reply ->
            assertTrue(reply, ConfirmationReply.isCancellation(reply))
            assertTrue(reply, askUserResult(reply) is ActionResult.Declined)
        }
    }

    @Test
    fun `filler alone or filler around free text is still the answer to a plain question`() {
        listOf(
            "please", "thanks", "thank you", "now", "just", "please call me",
            "no cancel fee today", "please stop by", "stop the car now"
        ).forEach { reply ->
            assertFalse(reply, ConfirmationReply.isCancellation(reply))
            assertEquals(reply, reply, askUserResult(reply).data)
        }
    }

    @Test
    fun `free text that only mentions a refusal is still the answer to a plain question`() {
        listOf("Stop by at 5", "no problem, see you then", "+15550100", "later today", "mat", "yes").forEach { reply ->
            val result = askUserResult(reply)
            assertTrue(reply, result.success)
            assertEquals(reply, result.data)
        }
    }

    @Test
    fun `confirmation aliases reach the action that parses the reply and keep its prompt`() {
        val registered = setOf("CONFIRM_ACTION", "ASK_USER")
        val params = mapOf("message" to "Send 500 to bob?")

        listOf("ASK_CONFIRMATION", "REQUEST_CONFIRMATION", "CONFIRM_USER", "CONFIRM").forEach { alias ->
            val mapping = ActionAutoMapper().mapAction(alias, params, registered)
            assertEquals(alias, "CONFIRM_ACTION", mapping.mappedAction)
            assertEquals(alias, params, mapping.mappedParams)
        }
    }
}
