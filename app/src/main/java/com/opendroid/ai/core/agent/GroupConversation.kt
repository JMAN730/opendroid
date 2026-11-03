package com.opendroid.ai.core.agent

import android.app.Notification
import androidx.core.app.NotificationCompat

/** True if a reply through [notification] would reach a group rather than one person. */
internal fun isGroupConversation(notification: Notification): Boolean {
    val extras = notification.extras ?: return false
    if (extras.getBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION)) return true

    // Apps targeting below API 28 mark a group only by its title, so the flag is false for them.
    // A one-to-one conversation titled with the contact's name is the only titled one we can tell apart.
    val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
    if (title.isNullOrBlank()) return false
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    val selfName = style?.user?.name?.toString()
    val senders = style?.messages.orEmpty()
        .mapNotNull { it.person?.name?.toString() }
        .filter { it != selfName }
    return senders.isEmpty() || senders.any { it != title }
}
