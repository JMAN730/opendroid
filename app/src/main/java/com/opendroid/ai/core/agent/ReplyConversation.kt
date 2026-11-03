package com.opendroid.ai.core.agent

import android.app.Notification
import android.service.notification.StatusBarNotification

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
