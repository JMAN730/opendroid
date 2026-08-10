package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailActionTest {

    @Test
    fun `opening compose returns pending instead of sent success`() = runBlocking {
        val composer = RecordingEmailComposer(EmailComposeOutcome.COMPOSED)

        val result = CommunicationActions.SendEmailAction(composer).execute(
            params = mapOf(
                "to" to "private@example.com",
                "subject" to "Private subject",
                "body" to "Private body"
            ),
            context = nullContext()
        )

        assertTrue(result is ActionResult.UserActionRequired)
        assertFalse(result.success)
        assertTrue(result.error!!.contains("review", ignoreCase = true))
        assertFalse(result.error!!.contains("private@example.com"))
        assertFalse(result.error!!.contains("Private subject"))
        assertFalse(result.error!!.contains("Private body"))
        assertEquals(1, composer.calls)
    }

    @Test
    fun `provider-confirmed outcome is the only email success path`() = runBlocking {
        val result = CommunicationActions.SendEmailAction(
            RecordingEmailComposer(EmailComposeOutcome.VERIFIED_SENT)
        ).execute(
            params = mapOf("to" to "private@example.com"),
            context = nullContext()
        )

        assertTrue(result is ActionResult.Success)
        assertEquals("Email sent successfully.", result.data)
    }

    @Test
    fun `missing email app returns generic failure without message data`() = runBlocking {
        val result = CommunicationActions.SendEmailAction(
            RecordingEmailComposer(EmailComposeOutcome.UNAVAILABLE)
        ).execute(
            params = mapOf("to" to "private@example.com"),
            context = nullContext()
        )

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals("Couldn't open the email app. Is one installed?", result.error)
        assertFalse(result.error!!.contains("private@example.com"))
    }

    @Test
    fun `launcher exception returns generic failure`() = runBlocking {
        val result = CommunicationActions.SendEmailAction(
            RecordingEmailComposer(failure = IllegalStateException("recipient private@example.com"))
        ).execute(
            params = mapOf("to" to "private@example.com"),
            context = nullContext()
        )

        assertTrue(result is ActionResult.Failure)
        assertEquals("Couldn't open the email app. Is one installed?", result.error)
        assertFalse(result.error!!.contains("private@example.com"))
    }

    private fun nullContext(): Context = ContextWrapper(null)

    private class RecordingEmailComposer(
        private val outcome: EmailComposeOutcome = EmailComposeOutcome.UNAVAILABLE,
        private val failure: Exception? = null
    ) : EmailComposer {
        var calls = 0

        override fun open(
            context: Context,
            to: String,
            subject: String,
            body: String
        ): EmailComposeOutcome {
            calls++
            failure?.let { throw it }
            return outcome
        }
    }
}
