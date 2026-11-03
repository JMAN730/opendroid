package com.opendroid.ai.core.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupConversationAutoReplyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val listener = OpenDroidNotificationListener()

    private val me = Person.Builder().setName("Me").build()
    private val alice = Person.Builder().setName("Alice").build()
    private val bob = Person.Builder().setName("Bob").build()

    private fun builder() = NotificationCompat.Builder(context, "messages")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .addAction(
            NotificationCompat.Action.Builder(
                0,
                "Reply",
                PendingIntent.getBroadcast(context, 0, Intent("reply"), PendingIntent.FLAG_MUTABLE)
            ).addRemoteInput(RemoteInput.Builder("text").build()).build()
        )

    private fun messaging(
        conversationTitle: String? = null,
        group: Boolean? = null,
        vararg senders: Person = arrayOf(alice)
    ): Notification {
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(conversationTitle)
        if (group != null) style.setGroupConversation(group)
        senders.forEach { style.addMessage("hello", 1_000L, it) }
        return builder().setStyle(style).build()
    }

    @Suppress("DEPRECATION")
    private fun canAutoReply(notification: Notification, packageName: String = "com.whatsapp") =
        listener.isMessageNotification(
            StatusBarNotification(
                packageName, packageName, 1, null, 10_123, 0, 0,
                notification, Process.myUserHandle(), 1_000L
            )
        )

    @Test
    fun `a flagged group conversation gets no auto-reply`() {
        assertFalse(canAutoReply(messaging("Work", group = true)))
    }

    @Test
    fun `every messenger's flagged group conversation gets no auto-reply`() {
        listOf("com.whatsapp", "org.telegram.messenger", "com.discord", "com.google.android.apps.messaging")
            .forEach { packageName ->
                assertFalse(packageName, canAutoReply(messaging("Work", group = true), packageName))
            }
    }

    @Test
    fun `a titled conversation from another sender is a group even without the flag`() {
        assertFalse(canAutoReply(messaging("#general, My Server", group = false), "com.discord"))
    }

    @Test
    fun `a titled conversation with no sender to compare against is treated as a group`() {
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Work")
        style.addMessage("hello", 1_000L, me)

        assertFalse(canAutoReply(builder().setStyle(style).build()))
    }

    @Test
    fun `the group flag counts on a notification that has no MessagingStyle`() {
        val notification = builder().setContentTitle("Work").setContentText("Alice: hello").build()
        notification.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true)

        assertFalse(canAutoReply(notification))
    }

    @Test
    fun `a group summary gets no auto-reply`() {
        val summary = builder()
            .setContentTitle("Work")
            .setContentText("Alice: hello")
            .setGroup("chats")
            .setGroupSummary(true)
            .build()

        assertFalse(canAutoReply(summary))
    }

    @Test
    fun `a one-to-one conversation still gets an auto-reply`() {
        assertTrue(canAutoReply(messaging()))
    }

    @Test
    fun `a one-to-one conversation titled with the contact's name still gets an auto-reply`() {
        assertTrue(canAutoReply(messaging("Alice", senders = arrayOf(alice, me, alice))))
    }

    @Test
    fun `a titled conversation with a second sender is a group`() {
        assertFalse(canAutoReply(messaging("Alice", senders = arrayOf(alice, bob))))
    }

    @Test
    fun `a plain notification from a messenger still gets an auto-reply`() {
        val notification = builder().setContentTitle("Alice").setContentText("hello").build()

        assertTrue(canAutoReply(notification))
    }

    @Suppress("DEPRECATION")
    private fun isOutgoing(notification: Notification, title: String, text: String) =
        listener.isOutgoingMessage(
            StatusBarNotification(
                "com.whatsapp", "com.whatsapp", 1, null, 10_123, 0, 0,
                notification, Process.myUserHandle(), 1_000L
            ),
            title,
            text
        )

    @Test
    fun `the user's own message in a self-only titled conversation still cancels a pending reply`() {
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Alice")
        style.addMessage("on my way", 1_000L, me)
        val notification = builder().setStyle(style).build()

        assertFalse(canAutoReply(notification))
        assertTrue(isOutgoing(notification, "You", "on my way"))
        assertTrue(isOutgoing(notification, "Alice", "You: on my way"))
    }

    @Test
    fun `an incoming message is not an outgoing one`() {
        assertFalse(isOutgoing(messaging("Alice", senders = arrayOf(alice)), "Alice", "hello"))
    }
}
