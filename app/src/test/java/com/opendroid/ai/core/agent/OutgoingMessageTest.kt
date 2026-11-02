package com.opendroid.ai.core.agent

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutgoingMessageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val me = Person.Builder().setName("You").build()
    private val alice = Person.Builder().setName("Alice").build()
    private val bob = Person.Builder().setName("Bob").build()

    private val pending = ReplyConversation(
        packageName = "com.whatsapp",
        contactName = "Alice",
        notificationKey = "0|com.whatsapp|1|alice|10",
        shortcutId = "alice-shortcut"
    )

    private fun outgoing(
        contactName: String? = null,
        notificationKey: String? = null,
        shortcutId: String? = null,
        packageName: String = "com.whatsapp"
    ) = OutgoingMessage(packageName, contactName, notificationKey, shortcutId)

    private fun notificationFrom(vararg senders: Person): Notification {
        val style = NotificationCompat.MessagingStyle(me)
        senders.forEach { style.addMessage("hello", 1_000L, it) }
        return NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("You")
            .setStyle(style)
            .build()
    }

    @Test
    fun `a title naming the contact is the conversation`() {
        assertEquals("Alice", outgoingContactName("Alice", notificationFrom(bob)))
    }

    @Test
    fun `a title of You takes the contact from the other sender in the messages`() {
        assertEquals("Alice", outgoingContactName("You", notificationFrom(alice, me, alice)))
    }

    @Test
    fun `a title of You is never returned as the contact`() {
        assertNull(outgoingContactName("You", notificationFrom(me)))
        assertNull(outgoingContactName("you", notificationFrom(alice, bob)))
    }

    @Test
    fun `a title of You with no messaging style names no contact`() {
        val plain = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("You")
            .build()

        assertNull(outgoingContactName("You", plain))
    }

    @Test
    fun `a named message answers the conversation with that name`() {
        assertTrue(pending.isAnsweredBy(outgoing(contactName = "Alice")))
        assertFalse(pending.isAnsweredBy(outgoing(contactName = "Bob")))
    }

    @Test
    fun `an unnamed message answers the conversation with its shortcut`() {
        assertTrue(pending.isAnsweredBy(outgoing(shortcutId = "alice-shortcut")))
        assertFalse(pending.isAnsweredBy(outgoing(shortcutId = "bob-shortcut")))
    }

    @Test
    fun `an unnamed message falls back to the notification key`() {
        assertTrue(pending.isAnsweredBy(outgoing(notificationKey = "0|com.whatsapp|1|alice|10")))
        assertFalse(pending.isAnsweredBy(outgoing(notificationKey = "0|com.whatsapp|1|bob|10")))
    }

    @Test
    fun `a different shortcut outranks a shared notification key`() {
        // Apps can reuse one key for every conversation.
        assertFalse(
            pending.isAnsweredBy(
                outgoing(notificationKey = "0|com.whatsapp|1|alice|10", shortcutId = "bob-shortcut")
            )
        )
    }

    @Test
    fun `a named message does not answer another contact sharing the notification key`() {
        assertFalse(
            pending.isAnsweredBy(outgoing(contactName = "Bob", notificationKey = "0|com.whatsapp|1|alice|10"))
        )
    }

    @Test
    fun `an unidentifiable message answers nothing`() {
        assertFalse(pending.isAnsweredBy(outgoing()))
    }

    @Test
    fun `a message in another app answers nothing`() {
        assertFalse(pending.isAnsweredBy(outgoing(contactName = "Alice", packageName = "org.telegram.messenger")))
    }
}
