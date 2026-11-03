package com.opendroid.ai.core.agent

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** The conversation an auto-reply was scheduled for. */
internal data class ReplyConversation(
    val packageName: String,
    val contactName: String?,
    val notificationKey: String? = null,
    val shortcutId: String? = null
) {
    constructor(packageName: String, contactName: String?, scheduledFor: StatusBarNotification?) : this(
        packageName = packageName,
        contactName = contactName,
        notificationKey = scheduledFor?.key,
        shortcutId = scheduledFor?.notification?.shortcutId
    )

    /** True only if [active] can be replied to and belongs to this conversation. */
    fun matches(active: ActiveConversation): Boolean {
        if (active.packageName != packageName || !active.hasReplyAction) return false
        if (shortcutId != null && active.shortcutId != null) return shortcutId == active.shortcutId
        // A different key is a different conversation even under the same name: a newer
        // message from this contact would already have replaced the job with its own key.
        if (notificationKey != null && notificationKey != active.notificationKey) return false
        // Apps can reuse one key for every conversation, so the name has to agree as well.
        return !contactName.isNullOrBlank() &&
            (active.title == contactName || active.conversationTitle == contactName)
    }

    /** True if the user sending [outgoing] answered this conversation. */
    fun isAnsweredBy(outgoing: OutgoingMessage): Boolean {
        if (outgoing.packageName != packageName) return false
        if (outgoing.contactName != null && outgoing.contactName == contactName) return true
        if (shortcutId != null && outgoing.shortcutId != null) return shortcutId == outgoing.shortcutId
        // Apps can reuse one key for every conversation, so a key alone only decides when
        // nothing names the contact. That can cancel several replies, and a reply that never
        // goes out is the safe side of sending one after the user has answered.
        return outgoing.contactName == null && notificationKey != null &&
            notificationKey == outgoing.notificationKey
    }
}

/** The fields of an active notification that identify its conversation. */
internal data class ActiveConversation(
    val packageName: String,
    val notificationKey: String?,
    val shortcutId: String?,
    val title: String?,
    val conversationTitle: String?,
    val hasReplyAction: Boolean
) {
    constructor(sbn: StatusBarNotification) : this(
        packageName = sbn.packageName,
        notificationKey = sbn.key,
        shortcutId = sbn.notification?.shortcutId,
        title = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        conversationTitle = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
        hasReplyAction = sbn.notification?.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
    )
}

/**
 * A message the user sent, as the messaging app shows it in a notification. [contactName]
 * is null when the notification doesn't say who it was sent to.
 */
internal data class OutgoingMessage(
    val packageName: String,
    val contactName: String?,
    val notificationKey: String?,
    val shortcutId: String?
) {
    constructor(sbn: StatusBarNotification, contactName: String?) : this(
        packageName = sbn.packageName,
        contactName = contactName,
        notificationKey = sbn.key,
        shortcutId = sbn.notification?.shortcutId
    )
}

/** True if [name] is the label apps show for the user's own messages, not a contact. */
internal fun isOwnSenderLabel(name: String): Boolean = name.equals("You", ignoreCase = true)

/** The contact an outgoing [notification] was sent to; [contactName] is what its title gave. */
internal fun outgoingContactName(contactName: String?, notification: Notification): String? {
    if (!contactName.isNullOrBlank() && !isOwnSenderLabel(contactName)) return contactName

    // Titled "You" with no conversation title: only the other sender can name the contact.
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    val selfName = style?.user?.name?.toString()
    return style?.messages.orEmpty()
        .mapNotNull { it.person?.name?.toString() }
        .filter { it != selfName && !isOwnSenderLabel(it) }
        .distinct()
        .singleOrNull()
}
