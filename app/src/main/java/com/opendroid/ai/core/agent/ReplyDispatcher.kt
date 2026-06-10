package com.opendroid.ai.core.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches auto-replies through WhatsApp (inline reply), SMS, and Email.
 * Uses notification RemoteInput actions for WhatsApp direct reply without opening the app.
 */
@Singleton
class ReplyDispatcher @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ReplyDispatcher"
    }

    /**
     * Reply to a WhatsApp notification using its inline reply RemoteInput action.
     * This sends the reply directly without opening the WhatsApp UI.
     */
    fun replyViaNotificationAction(sbn: StatusBarNotification, replyText: String): Boolean {
        try {
            val notification = sbn.notification ?: return false
            val actions = notification.actions ?: return false

            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                if (remoteInputs.isEmpty()) continue

                // Found a reply action with remote input
                val intent = Intent()
                val bundle = Bundle()
                for (remoteInput in remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

                try {
                    action.actionIntent.send(context, 0, intent)
                    Log.d(TAG, "Successfully sent reply via notification action: ${replyText.take(30)}...")
                    return true
                } catch (e: PendingIntent.CanceledException) {
                    Log.e(TAG, "PendingIntent cancelled: ${e.message}")
                }
            }
            Log.w(TAG, "No reply action found in notification")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reply via notification: ${e.message}")
            return false
        }
    }

    /**
     * Send an SMS reply directly using SmsManager.
     */
    fun replyViaSms(phoneNumber: String, replyText: String, context: Context): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            if (smsManager != null) {
                smsManager.sendTextMessage(phoneNumber, null, replyText, null, null)
                Log.d(TAG, "SMS reply sent to $phoneNumber")
                true
            } else {
                Log.e(TAG, "SmsManager not available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS reply: ${e.message}")
            false
        }
    }

    /**
     * Open email compose with pre-filled reply.
     * For Gmail, we attempt to use the notification's reply action first.
     */
    fun replyViaEmail(
        sbn: StatusBarNotification?,
        to: String,
        subject: String,
        replyText: String,
        context: Context
    ): Boolean {
        // First try notification inline reply (works for Gmail)
        if (sbn != null) {
            val replied = replyViaNotificationAction(sbn, replyText)
            if (replied) return true
        }

        // Fallback: open email compose intent
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, "Re: $subject")
                putExtra(Intent.EXTRA_TEXT, replyText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Email reply compose opened for $to")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open email reply: ${e.message}")
            false
        }
    }
}
