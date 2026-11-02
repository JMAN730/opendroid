package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.memory.NotificationHistoryCleaner
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.models.AutoReplyConfig
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
    private val settingsRepository: SettingsRepository,
    private val notificationHistoryCleaner: NotificationHistoryCleaner
) {

    fun getActions(): List<Action> = listOf(
        ReadNotificationsAction(notificationDao),
        AutoReplyToggleAction(settingsRepository),
        DismissNotificationAction(notificationDao, notificationHistoryCleaner::clearHistory)
    )
}

internal class ReadNotificationsAction(
    private val notificationDao: NotificationDao
) : Action {
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

            // A later step can send this text to someone ($$sN), so it holds the notifications and
            // nothing else. The learned patterns reach the model through MemoryManager instead.
            ActionResult(true, "Here are your recent notifications:\n\n$formatted", null)
        } catch (e: Exception) {
            Log.e("NotificationActions", "Failed to read notifications: ${e.message}")
            ActionResult(false, null, "Couldn't read notifications right now.")
        }
    }
}

internal class AutoReplyToggleAction(
    private val settingsRepository: SettingsRepository
) : Action {
    override val name: String = "AUTO_REPLY_TOGGLE"

    override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
        return try {
            val state = params["state"]?.trim()?.lowercase(Locale.ROOT)
            val app = params["app"]?.trim()?.lowercase(Locale.ROOT).orEmpty()

            // Null means the global switch. An app we don't know must not read as success: the
            // user asked for replies to stop, and they wouldn't.
            val channel = if (app in GLOBAL_APP_NAMES) {
                null
            } else {
                TOGGLE_CHANNELS.find { it.appName == app }
                    ?: return ActionResult.Failure(
                        errorMsg = "Auto-reply can't be set for \"$app\". " +
                            "Choose ${TOGGLE_CHANNELS.joinToString(", ") { it.label }}, or all."
                    )
            }

            val currentConfig = settingsRepository.autoReplyConfig.first()
            val wasEnabled = channel?.isEnabled?.invoke(currentConfig) ?: currentConfig.globalEnabled
            val enabled = state == "on" || (state != "off" && !wasEnabled)
            val newConfig = channel?.withEnabled?.invoke(currentConfig, enabled)
                ?: currentConfig.copy(globalEnabled = enabled)

            settingsRepository.updateAutoReplyConfig(newConfig)

            ActionResult(true, statusText(channel, enabled, newConfig), null)
        } catch (e: Exception) {
            Log.e("NotificationActions", "Failed to toggle auto-reply: ${e.message}")
            ActionResult(false, null, "Couldn't update auto-reply settings.")
        }
    }

    private fun statusText(channel: ToggleChannel?, enabled: Boolean, config: AutoReplyConfig): String {
        val subject = if (channel != null) "Auto-reply for ${channel.label}" else "Auto-reply"
        val changed = "$subject is now ${if (enabled) "ON" else "OFF"}."
        val enabledApps = TOGGLE_CHANNELS.filter { it.isEnabled(config) }
        return when {
            !config.globalEnabled && channel == null -> changed
            !config.globalEnabled -> "$changed Auto-reply is OFF overall, so no replies will go out."
            enabledApps.isEmpty() -> "$changed No apps are enabled, so no replies will go out."
            else -> "$changed Enabled for ${enabledApps.joinToString(", ") { it.label }}. " +
                "Reply delay: ${config.replyDelayMinutes} minutes."
        }
    }

    private class ToggleChannel(
        val appName: String,
        val label: String,
        val isEnabled: (AutoReplyConfig) -> Boolean,
        val withEnabled: (AutoReplyConfig, Boolean) -> AutoReplyConfig
    )

    private companion object {
        /** Blank covers a planner that leaves the app empty rather than out. */
        val GLOBAL_APP_NAMES = setOf("", "all", "everything", "global")

        val TOGGLE_CHANNELS = listOf(
            ToggleChannel("whatsapp", "WhatsApp", { it.whatsappEnabled }, { c, on -> c.copy(whatsappEnabled = on) }),
            ToggleChannel("telegram", "Telegram", { it.telegramEnabled }, { c, on -> c.copy(telegramEnabled = on) }),
            ToggleChannel("discord", "Discord", { it.discordEnabled }, { c, on -> c.copy(discordEnabled = on) }),
            ToggleChannel("sms", "SMS", { it.smsEnabled }, { c, on -> c.copy(smsEnabled = on) }),
            ToggleChannel("email", "Email", { it.emailEnabled }, { c, on -> c.copy(emailEnabled = on) })
        )
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
    private val notificationDao: NotificationDao,
    /** Clears every record and whatever was learned from them; used when no filter is given. */
    private val clearHistory: suspend () -> Int
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
                else -> clearHistory()
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
