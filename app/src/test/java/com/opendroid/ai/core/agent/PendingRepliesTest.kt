package com.opendroid.ai.core.agent

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRepliesTest {

    private val key = "com.whatsapp:Alice"
    private val alice = ReplyConversation("com.whatsapp", "Alice", "0|com.whatsapp|1|alice|10", "alice-shortcut")
    private val bob = ReplyConversation("com.whatsapp", "Bob", "0|com.whatsapp|1|bob|10", "bob-shortcut")

    @Test
    fun `a new message's reply replaces and cancels the one waiting`() {
        val pending = PendingReplies()
        val first = Job()
        val second = Job()

        pending.register(key, first, alice)
        pending.register(key, second, alice)

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertSame(second, pending.registered(key))
    }

    @Test
    fun `the replaced reply finishing does not unregister its successor`() {
        // Message 1 schedules a reply, message 2 replaces it. The cancelled first
        // reply's cleanup runs afterwards and must leave the second one cancellable (#83).
        val pending = PendingReplies()
        val first = Job()
        val second = Job()
        pending.register(key, first, alice)
        pending.register(key, second, alice)

        pending.finished(key, first)

        assertSame(second, pending.registered(key))
        pending.cancel(key)
        assertTrue(second.isCancelled)
    }

    @Test
    fun `a reply finishing unregisters itself`() {
        val pending = PendingReplies()
        val only = Job()
        pending.register(key, only, alice)

        pending.finished(key, only)

        assertNull(pending.registered(key))
    }

    @Test
    fun `cancel cancels only the reply waiting for that conversation`() {
        val pending = PendingReplies()
        val aliceReply = Job()
        val bobReply = Job()
        pending.register(key, aliceReply, alice)
        pending.register("com.whatsapp:Bob", bobReply, bob)

        pending.cancel(key)

        assertTrue(aliceReply.isCancelled)
        assertFalse(bobReply.isCancelled)
        assertNull(pending.registered(key))
    }

    @Test
    fun `a reply being sent cannot be cancelled by its own echo`() {
        // The app posts "You: ..." for our own reply while the sending job still has to
        // mark it as replied. Cancelling it there undercounts the hourly limit (#83).
        val pending = PendingReplies()
        val sending = Job()
        pending.register(key, sending, alice)

        assertTrue(pending.claimForSending(key, sending))
        pending.cancelAnsweredBy(OutgoingMessage("com.whatsapp", "Alice", null, null))

        assertFalse(sending.isCancelled)
        assertNull(pending.registered(key))
    }

    @Test
    fun `a new message does not cancel the reply being sent`() {
        val pending = PendingReplies()
        val sending = Job()
        val next = Job()
        pending.register(key, sending, alice)
        pending.claimForSending(key, sending)

        pending.register(key, next, alice)

        assertFalse(sending.isCancelled)
        assertSame(next, pending.registered(key))
    }

    @Test
    fun `a reply answered by hand before sending cannot claim the send`() {
        val pending = PendingReplies()
        val waiting = Job()
        pending.register(key, waiting, alice)

        pending.cancelAnsweredBy(OutgoingMessage("com.whatsapp", "Alice", null, null))

        assertTrue(waiting.isCancelled)
        assertFalse(pending.claimForSending(key, waiting))
    }

    @Test
    fun `a replaced reply cannot claim the send`() {
        val pending = PendingReplies()
        val first = Job()
        val second = Job()
        pending.register(key, first, alice)
        pending.register(key, second, alice)

        assertFalse(pending.claimForSending(key, first))
        assertSame(second, pending.registered(key))
    }

    @Test
    fun `an unnamed outgoing message cancels the pending reply with its notification identity`() {
        // A one-to-one notification titled "You" names no contact, so package:You must
        // not be what gets cancelled: the identity leads to package:Alice instead (#83).
        val pending = PendingReplies()
        val aliceReply = Job()
        val bobReply = Job()
        pending.register(key, aliceReply, alice)
        pending.register("com.whatsapp:Bob", bobReply, bob)

        pending.cancelAnsweredBy(OutgoingMessage("com.whatsapp", null, "0|com.whatsapp|1|alice|10", null))

        assertTrue(aliceReply.isCancelled)
        assertFalse(bobReply.isCancelled)
        assertNull(pending.registered(key))
    }
}
