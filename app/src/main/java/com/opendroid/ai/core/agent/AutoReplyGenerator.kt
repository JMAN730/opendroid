package com.opendroid.ai.core.agent

import android.util.Log
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.prompts.AutoReplyPrompts
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.AutoReplyConfig

/**
 * Writes the text of an auto-reply. The reply goes to a third party unreviewed, so the prompt
 * only ever holds the user's display name, the configured tone, and the history stored under the
 * sender's name in the same app. The table has no conversation id, so two senders who use one name
 * in one app still share that history.
 */
internal class AutoReplyGenerator(
    private val notificationDao: NotificationDao,
    private val userProfileStore: UserProfileStore,
    private val activeProvider: suspend () -> LLMProvider
) {
    companion object {
        private const val TAG = "AutoReplyGenerator"
    }

    suspend fun generate(
        notification: NotificationEntity,
        config: AutoReplyConfig,
        channel: AutoReplyChannel
    ): String? {
        if (!config.customReplyText.isNullOrBlank()) {
            return config.customReplyText
        }
        return try {
            val provider = activeProvider()

            val userName = displayName()
            val senderName = notification.contactName ?: notification.title

            // Build conversation history from recent notifications with this contact, in this
            // app only: the name is a free-form title, so a sender could otherwise pick another
            // app's rows, such as an OTP sender's, just by taking its name.
            val recentMessages = notificationDao.getNotificationsForContactInApp(
                notification.packageName, senderName
            )
            val conversationHistory = recentMessages
                .sortedBy { it.timestamp }
                .takeLast(10)
                .joinToString("\n") { msg ->
                    val prefix = if (msg.isAutoReplied) userName else senderName
                    val text = if (msg.isAutoReplied) (msg.autoReplyText ?: "") else msg.text
                    "[$prefix]: $text"
                }

            // Build the prompt based on app type
            val systemPrompt = when (channel) {
                AutoReplyChannel.WHATSAPP, AutoReplyChannel.TELEGRAM, AutoReplyChannel.DISCORD ->
                    AutoReplyPrompts.buildWhatsAppReplyPrompt(
                        userName, senderName, notification.text,
                        conversationHistory, config.customPrompt
                    )
                AutoReplyChannel.EMAIL ->
                    AutoReplyPrompts.buildEmailReplyPrompt(
                        userName, senderName, notification.title, notification.text,
                        config.customPrompt
                    )
                AutoReplyChannel.SMS ->
                    AutoReplyPrompts.buildSmsReplyPrompt(
                        userName, senderName, notification.text,
                        conversationHistory, config.customPrompt
                    )
            }

            val response = provider.complete(
                LLMRequest(
                    systemPrompt = systemPrompt,
                    messages = listOf(
                        com.opendroid.ai.data.models.ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = "Generate the reply.",
                            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.7f,
                    maxTokens = 150,
                    responseFormat = ResponseFormat.TEXT
                )
            )

            response.content.trim().removeSurrounding("\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate reply: ${e.message}")
            null
        }
    }

    /** An unreadable or empty profile falls back to a generic name rather than failing the reply. */
    private fun displayName(): String {
        val profile = (userProfileStore.read() as? ProfileStoreResult.Success)?.value
        return profile?.name?.trim().orEmpty().ifEmpty { "User" }
    }
}
