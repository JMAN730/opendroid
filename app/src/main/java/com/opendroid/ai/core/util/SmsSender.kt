package com.opendroid.ai.core.util

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal enum class SmsSendOutcome {
    /** The radio returned RESULT_OK for every part. */
    SENT,

    /** The radio reported an error for a single-part message, so nothing went out. */
    FAILED,

    /**
     * The radio reported an error for at least one part of a multipart message.
     * Other parts may still have reached the recipient, so a blind resend can duplicate them.
     */
    PARTLY_FAILED,

    /** The radio didn't answer in time, so the message may or may not go out later. */
    UNCONFIRMED
}

/**
 * Sends an SMS of any length and reports what the radio said about it.
 *
 * SmsManager.sendTextMessage doesn't split text, and it only reports failures,
 * including "message too long", through a sent PendingIntent.
 */
internal object SmsSender {

    private const val SENT_RESULT_TIMEOUT_MS = 60_000L

    /**
     * @throws Exception whatever SmsManager throws when it refuses the message outright.
     */
    suspend fun send(
        context: Context,
        smsManager: SmsManager,
        phoneNumber: String,
        message: String,
        timeoutMs: Long = SENT_RESULT_TIMEOUT_MS
    ): SmsSendOutcome {
        val appContext = context.applicationContext
        val parts = smsManager.divideMessage(message)
        // Unique per send, so concurrent sends can't count each other's results.
        val action = "${appContext.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val sentIntents = ArrayList(
            parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    appContext,
                    index,
                    Intent(action).setPackage(appContext.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            }
        )

        val outcome = CompletableDeferred<SmsSendOutcome>()
        val receiver = object : BroadcastReceiver() {
            private var partsLeft = parts.size

            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) {
                    outcome.complete(
                        if (parts.size > 1) SmsSendOutcome.PARTLY_FAILED else SmsSendOutcome.FAILED
                    )
                } else if (--partsLeft == 0) {
                    outcome.complete(SmsSendOutcome.SENT)
                }
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null)
            // The text has left the app, so cancelling can't unsend it. The caller still
            // needs the result, e.g. to record an auto-reply that a newer message cancelled.
            return withContext(NonCancellable) {
                withTimeoutOrNull(timeoutMs) { outcome.await() } ?: SmsSendOutcome.UNCONFIRMED
            }
        } finally {
            appContext.unregisterReceiver(receiver)
        }
    }
}
