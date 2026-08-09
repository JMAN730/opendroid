package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Action handlers for notification-related commands:
 * - READ_NOTIFICATIONS: Query and display recent notifications
 * - AUTO_REPLY_TOGGLE: Enable/disable auto-reply
 * - DISMISS_NOTIFICATION: Remove saved notification history
 */
@Singleton
class NotificationActions @Inject constructor(
    private val notificationDao: NotificationDao,
    private val notificationIntelligence: NotificationIntelligence,
    private val settingsRepository: SettingsRepository
) {

    fun getActions(): List<Action> = listOf(
        ReadNotificationsAction(),
        AutoReplyToggleAction(),
        DismissNotificationAction(notificationDao)
    )

    private inner class ReadNotificationsAction : Action {
        override val name: String = "READ_NOTIFICATIONS"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val app = params["app"]
                val count = params["count"]?.toIntOrNull() ?: 10

                val notifications = if (!app.isNullOrBlank()) {
                    // Map friendly app name to package
                    val packageFilter = notificationPackageForApp(app)
                    notificationDao.getNotificationsByApp(packageFilter, count)
                } else {
                    notificationDao.getRecentNotifications(count)
                }

                if (notifications.isEmpty()) {
                    return ActionResult(true, "No notifications found.", null)
                }

                val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                val formatted = notifications.joinToString("\n\n") { notif ->
                    val time = dateFormat.format(java.util.Date(notif.timestamp))
                    val replied = if (notif.isAutoReplied) "\n  ↳ Auto-replied: ${notif.autoReplyText?.take(50)}" else ""
                    "📱 ${notif.appName} • $time\n  From: ${notif.contactName ?: notif.title}\n  ${notif.text.take(120)}$replied"
                }

                // Also include learned patterns
                val patterns = notificationIntelligence.getLearnedPatterns()
                val patternsSection = if (patterns.isNotBlank()) "\n\n📊 What I've learned:\n$patterns" else ""

                ActionResult(true, "Here are your recent notifications:\n\n$formatted$patternsSection", null)
            } catch (e: Exception) {
                Log.e("NotificationActions", "Failed to read notifications: ${e.message}")
                ActionResult(false, null, "Couldn't read notifications right now.")
            }
        }
    }

    private inner class AutoReplyToggleAction : Action {
        override val name: String = "AUTO_REPLY_TOGGLE"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val state = params["state"]?.lowercase()
                val app = params["app"]?.lowercase()

                val currentConfig = settingsRepository.autoReplyConfig.first()

                val newConfig = when {
                    // Toggle specific app
                    app != null -> when (app) {
                        "whatsapp" -> currentConfig.copy(
                            whatsappEnabled = state == "on" || (state != "off" && !currentConfig.whatsappEnabled)
                        )
                        "sms" -> currentConfig.copy(
                            smsEnabled = state == "on" || (state != "off" && !currentConfig.smsEnabled)
                        )
                        "email" -> currentConfig.copy(
                            emailEnabled = state == "on" || (state != "off" && !currentConfig.emailEnabled)
                        )
                        else -> currentConfig
                    }
                    // Toggle global
                    else -> currentConfig.copy(
                        globalEnabled = state == "on" || (state != "off" && !currentConfig.globalEnabled)
                    )
                }

                settingsRepository.updateAutoReplyConfig(newConfig)

                val statusText = buildString {
                    append("Auto-reply is now ${if (newConfig.globalEnabled) "ON" else "OFF"}")
                    if (newConfig.globalEnabled) {
                        val apps = mutableListOf<String>()
                        if (newConfig.whatsappEnabled) apps.add("WhatsApp")
                        if (newConfig.smsEnabled) apps.add("SMS")
                        if (newConfig.emailEnabled) apps.add("Email")
                        if (apps.isNotEmpty()) {
                            append(" for ${apps.joinToString(", ")}")
                        }
                        append(". Reply delay: ${newConfig.replyDelayMinutes} minutes.")
                    }
                }

                ActionResult(true, statusText, null)
            } catch (e: Exception) {
                Log.e("NotificationActions", "Failed to toggle auto-reply: ${e.message}")
                ActionResult(false, null, "Couldn't update auto-reply settings.")
            }
        }
    }
}

/** Maps the app names accepted by notification actions to stored package names. */
internal fun notificationPackageForApp(app: String): String =
    when (val normalized = app.trim().lowercase(Locale.ROOT)) {
        "whatsapp" -> "com.whatsapp"
        "sms", "messages", "messaging" -> "com.google.android.apps.messaging"
        "gmail", "email" -> "com.google.android.gm"
        "instagram" -> "com.instagram.android"
        "telegram" -> "org.telegram.messenger"
        "twitter", "x" -> "com.twitter.android"
        else -> normalized
    }

/**
 * Removes notification history from the app's own Room store. Android's live
 * notification dismissal API needs a listener-side key and additional product
 * semantics; this action deliberately has the narrower, deterministic contract
 * of clearing the records OpenDroid captured.
 */
internal class DismissNotificationAction(
    private val notificationDao: NotificationDao
) : Action {
    override val name: String = "DISMISS_NOTIFICATION"

    override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
        return try {
            val app = params["app"]?.trim()?.takeIf { it.isNotEmpty() }
            val notificationIdText = params["notificationId"]?.trim()?.takeIf { it.isNotEmpty() }

            if (app != null && notificationIdText != null) {
                return ActionResult(false, error = "Provide either an app or a notification ID, not both.")
            }

            val deleted = when {
                notificationIdText != null -> {
                    val notificationId = notificationIdText.toLongOrNull()
                        ?: return ActionResult(false, error = "Notification ID must be a number.")
                    notificationDao.deleteNotificationById(notificationId)
                }
                app != null -> notificationDao.deleteNotificationsByApp(notificationPackageForApp(app))
                else -> notificationDao.clearAll()
            }

            if (deleted == 0) {
                ActionResult(true, "No matching notifications found.", null)
            } else {
                val noun = if (deleted == 1) "notification" else "notifications"
                ActionResult(true, "Dismissed $deleted $noun.", null)
            }
        } catch (e: Exception) {
            Log.e("NotificationActions", "Failed to dismiss notifications: ${e.message}")
            ActionResult(false, error = "Couldn't dismiss notifications right now.")
        }
    }
}
