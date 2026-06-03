package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Action handlers for notification-related commands:
 * - READ_NOTIFICATIONS: Query and display recent notifications
 * - AUTO_REPLY_TOGGLE: Enable/disable auto-reply
 */
@Singleton
class NotificationActions @Inject constructor(
    private val notificationDao: NotificationDao,
    private val notificationIntelligence: NotificationIntelligence,
    private val settingsRepository: SettingsRepository
) {

    fun getActions(): List<Action> = listOf(
        ReadNotificationsAction(),
        AutoReplyToggleAction()
    )

    private inner class ReadNotificationsAction : Action {
        override val name: String = "READ_NOTIFICATIONS"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val app = params["app"]
                val count = params["count"]?.toIntOrNull() ?: 10

                val notifications = if (!app.isNullOrBlank()) {
                    // Map friendly app name to package
                    val packageFilter = when (app.lowercase()) {
                        "whatsapp" -> "com.whatsapp"
                        "sms", "messages", "messaging" -> "com.google.android.apps.messaging"
                        "gmail", "email" -> "com.google.android.gm"
                        "instagram" -> "com.instagram.android"
                        "telegram" -> "org.telegram.messenger"
                        "twitter", "x" -> "com.twitter.android"
                        else -> app
                    }
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
