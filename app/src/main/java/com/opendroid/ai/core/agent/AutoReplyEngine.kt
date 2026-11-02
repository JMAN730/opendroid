package com.opendroid.ai.core.agent

import android.content.Context
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.util.Log
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the auto-reply pipeline:
 * 1. Check if auto-reply is enabled for this app/contact
 * 2. Wait the configured delay (default 15 min) — abort if user replies manually
 * 3. Build context from this contact's notification history only
 * 4. Generate reply via LLM
 * 5. Dispatch via ReplyDispatcher
 * 6. Log result to database
 */
@Singleton
class AutoReplyEngine @Inject constructor(
    private val notificationDao: NotificationDao,
    private val llmProviderFactory: LLMProviderFactory,
    private val replyDispatcher: ReplyDispatcher,
    private val settingsRepository: SettingsRepository,
    userProfileStore: UserProfileStore
) {
    companion object {
        private const val TAG = "AutoReplyEngine"
        // Cooldown period after sending an auto-reply. Any notification from the same
        // contact within this window is treated as the messaging app echoing our own
        // sent reply back as a notification — skip it to prevent infinite loops.
        private const val REPLY_BOUNCEBACK_COOLDOWN_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val replyGenerator = AutoReplyGenerator(
        notificationDao = notificationDao,
        userProfileStore = userProfileStore,
        activeProvider = { llmProviderFactory.getActiveProvider() }
    )

    // Track pending auto-replies so we can cancel them if user replies manually
    private val pendingReplies = PendingReplies()

    // Track when we last sent an auto-reply per contact key to detect bouncebacks
    // Store both timestamp and the reply text to verify it's actually an echo
    private data class SentReply(val timestamp: Long, val replyText: String)
    private val recentlySent = ConcurrentHashMap<String, SentReply>()

    /**
     * Returns true if a notification from this package/contact is likely our own
     * auto-reply bouncing back as a notification (e.g. WhatsApp showing "You: ...")
     * within the cooldown window. Now requires matching reply text to confirm it's
     * actually an echo, not just any message during the cooldown.
     */
    fun isOwnReplyBounceback(packageName: String, contactName: String?, messageText: String): Boolean {
        val contactKey = "$packageName:${contactName ?: ""}"
        val sentReply = recentlySent[contactKey] ?: return false
        val elapsed = System.currentTimeMillis() - sentReply.timestamp

        if (elapsed < REPLY_BOUNCEBACK_COOLDOWN_MS) {
            // Check if the message text matches our sent reply
            val isMatch = messageText.contains(sentReply.replyText, ignoreCase = true) ||
                          sentReply.replyText.contains(messageText, ignoreCase = true)
            if (isMatch) {
                Log.d(TAG, "Suppressing bounceback for $contactKey (sent ${elapsed}ms ago, text matches)")
                return true
            } else {
                Log.d(TAG, "Not a bounceback for $contactKey: text doesn't match (expected '${sentReply.replyText}', got '$messageText')")
                return false
            }
        }
        // Cooldown expired — clean up
        recentlySent.remove(contactKey)
        return false
    }

    /**
     * Called when a message notification is received.
     * Schedules an auto-reply after the configured delay.
     */
    fun scheduleAutoReply(
        notification: NotificationEntity,
        sbn: StatusBarNotification?,
        context: Context
    ) {
        val contactKey = "${notification.packageName}:${notification.contactName ?: notification.title}"

        // LAZY so the job is registered before it can run: with a zero delay it could
        // otherwise finish before registration and leave a stale entry behind.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val config = settingsRepository.autoReplyConfig.first()

                val channel = replyChannelFor(notification, config, Telephony.Sms.getDefaultSmsPackage(context))
                if (channel == null) {
                    Log.d(TAG, "Auto-reply skipped for ${notification.contactName}: policy check failed")
                    return@launch
                }

                // Wait the configured delay if greater than 0
                val delayMs = if (config.replyDelayMinutes > 0) config.replyDelayMinutes * 60 * 1000L else 0L
                if (delayMs > 0L) {
                    Log.d(TAG, "Scheduling auto-reply in ${config.replyDelayMinutes} min for ${notification.contactName}")
                    delay(delayMs)
                } else {
                    Log.d(TAG, "Executing auto-reply immediately (0 sec delay) for ${notification.contactName}")
                }

                // Re-check the full policy: during the wait the user may have turned
                // auto-reply off globally or for this app, or blocklisted the contact.
                val freshConfig = settingsRepository.autoReplyConfig.first()
                if (replyChannelFor(notification, freshConfig, Telephony.Sms.getDefaultSmsPackage(context)) == null) {
                    Log.d(TAG, "Auto-reply no longer allowed after the wait period")
                    return@launch
                }

                // Check rate limit
                val oneHourAgo = System.currentTimeMillis() - 3_600_000
                val recentReplyCount = notificationDao.getAutoReplyCountForContact(
                    notification.packageName,
                    notification.contactName ?: notification.title,
                    oneHourAgo
                )
                if (recentReplyCount >= freshConfig.maxRepliesPerContactPerHour) {
                    Log.d(TAG, "Rate limit reached for ${notification.contactName}")
                    return@launch
                }

                // Generate and send reply
                val replyText = replyGenerator.generate(notification, freshConfig, channel)
                if (replyText.isNullOrBlank()) {
                    Log.w(TAG, "LLM returned empty reply, skipping")
                    return@launch
                }

                if (!pendingReplies.claimForSending(contactKey, coroutineContext.job)) {
                    Log.d(TAG, "Auto-reply cancelled for ${notification.contactName} before sending")
                    return@launch
                }

                val sent = dispatchReply(notification, sbn, replyText, context, channel)
                if (sent) {
                    // Record the send timestamp AND reply text BEFORE marking in DB so the
                    // bounceback check is ready when the echo notification fires
                    recentlySent[contactKey] = SentReply(System.currentTimeMillis(), replyText)
                    // The reply is already out, so a newer message cancelling this job can't
                    // skip the record the hourly limit and the LLM history are built from
                    withContext(NonCancellable) {
                        notificationDao.markAsAutoReplied(notification.id, replyText)
                    }
                    Log.d(TAG, "Auto-reply sent to ${notification.contactName}: ${replyText.take(50)}...")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Auto-reply cancelled for ${notification.contactName}")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-reply failed: ${e.message}")
            } finally {
                // A cancelled send can finish long after a newer reply took over this key
                pendingReplies.finished(contactKey, coroutineContext.job)
            }
        }

        // Replaces (and cancels) any reply already waiting for this contact: they sent another message.
        pendingReplies.register(
            contactKey,
            job,
            ReplyConversation(notification.packageName, notification.contactName ?: notification.title, sbn)
        )
        job.start()
    }

    /**
     * Cancel the pending auto-reply for the conversation the user just answered by hand.
     */
    internal fun cancelPendingReply(outgoing: OutgoingMessage) {
        pendingReplies.cancelAnsweredBy(outgoing)
    }

    /**
     * Cancel all pending auto-replies.
     */
    fun cancelAll() {
        pendingReplies.cancelAll()
    }

    private suspend fun dispatchReply(
        notification: NotificationEntity,
        sbn: StatusBarNotification?,
        replyText: String,
        context: Context,
        channel: AutoReplyChannel
    ): Boolean {
        // Only ever reply on the conversation this job was scheduled for: if it has no active
        // notification, use the original SBN rather than another conversation's reply action
        val conversation = ReplyConversation(notification.packageName, notification.contactName, sbn)
        return when (channel) {
            // WhatsApp, Telegram, Discord — use notification inline reply with a fresh SBN
            AutoReplyChannel.WHATSAPP, AutoReplyChannel.TELEGRAM, AutoReplyChannel.DISCORD -> {
                // Fetch a fresh SBN from the NotificationListenerService — the original
                // one captured at schedule time may have a stale PendingIntent after the delay
                val listener = com.opendroid.ai.core.service.OpenDroidNotificationListener.getInstance()
                val freshSbn = listener?.getActiveNotification(conversation)
                val targetSbn = freshSbn ?: sbn
                if (targetSbn != null) {
                    val replied = replyDispatcher.replyViaNotificationAction(targetSbn, replyText)
                    if (!replied && freshSbn == null && sbn != null) {
                        // Both failed — the notification was probably dismissed
                        Log.w(TAG, "Inline notification reply failed: notification may have been dismissed")
                    }
                    replied
                } else {
                    Log.w(TAG, "No StatusBarNotification available for reply (neither fresh nor original)")
                    false
                }
            }
            // SMS
            AutoReplyChannel.SMS -> {
                // Try fresh SBN for inline reply first
                val listener = com.opendroid.ai.core.service.OpenDroidNotificationListener.getInstance()
                val freshSbn = listener?.getActiveNotification(conversation) ?: sbn

                if (freshSbn != null && replyDispatcher.replyViaNotificationAction(freshSbn, replyText)) {
                    true
                } else {
                    // Fallback to direct SMS send via phone number
                    val contact = notification.contactName ?: notification.title
                    val phoneRegex = Regex("""^\+?[0-9\s\-()]{7,20}$""")
                    if (phoneRegex.matches(contact.trim())) {
                        replyDispatcher.replyViaSms(contact.trim(), replyText, context)
                    } else {
                        Log.w(TAG, "SMS direct reply skipped: Contact name is not a valid phone number ($contact) and no notification reply action was available")
                        false
                    }
                }
            }
            // Email — use the extracted sender email address
            AutoReplyChannel.EMAIL -> {
                val senderEmail = notification.senderEmail ?: ""
                // Try fresh SBN for inline reply first
                val listener = com.opendroid.ai.core.service.OpenDroidNotificationListener.getInstance()
                val freshSbn = listener?.getActiveNotification(conversation) ?: sbn
                replyDispatcher.replyViaEmail(freshSbn, senderEmail, notification.title, replyText, context)
            }
        }
    }
}
