package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.TELEGRAM
    override val displayName: String = "Telegram"
    override val supportedPermissions: List<SocialPermission> = listOf(
        SocialPermission.READ_PROFILE,
        SocialPermission.READ_POSTS,
        SocialPermission.READ_COMMENTS,
        SocialPermission.REPLY_TO_COMMENTS,
        SocialPermission.PUBLISH_POSTS,
        SocialPermission.SCHEDULE_POSTS,
        SocialPermission.MANAGE_MESSAGES
    )

    override suspend fun validateCredentials(credentials: SocialCredentials): Result<SocialAccountInfo> {
        val token = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAccountInfo(
                    id = "tg_${credentials.accountId.ifBlank { "opendroid_bot" }}",
                    username = "OpenDroidBot",
                    displayName = "OpenDroid Assistant",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/getMe")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("description", "Failed to validate Telegram bot"))
            }
            val result = json.getJSONObject("result")
            SocialAccountInfo(
                id = result.getLong("id").toString(),
                username = result.optString("username", "TelegramBot"),
                displayName = result.optString("first_name", "Telegram Bot"),
                avatarUrl = null,
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        val token = credentials.botToken ?: credentials.accessToken
        val chatId = credentials.channelOrTargetId ?: "@opendroid_community"

        if (isSandboxToken(token)) {
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = "tg_msg_${System.currentTimeMillis()}",
                    url = "https://t.me/opendroid_community/${System.currentTimeMillis()}"
                )
            )
        }

        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", post.content)
            put("parse_mode", "Markdown")
        }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("description", "Failed to publish Telegram message"))
            }
            val result = json.getJSONObject("result")
            val messageId = result.getLong("message_id").toString()
            PublishPostResult(
                success = true,
                platformPostId = messageId,
                url = if (chatId.startsWith("@")) "https://t.me/${chatId.removePrefix("@")}/$messageId" else null
            )
        }
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        val token = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "tg_int_1",
                        platform = SocialPlatform.TELEGRAM,
                        accountId = credentials.accountId,
                        authorName = "Alex Tech",
                        authorHandle = "@alextech",
                        type = InteractionType.DIRECT_MESSAGE,
                        category = InteractionCategory.QUESTION,
                        priority = InteractionPriority.NORMAL,
                        content = "Does OpenDroid v1.1 support offline local models?",
                        timestamp = System.currentTimeMillis() - 3600_000,
                        status = "UNREAD",
                        suggestedAction = "Draft response confirming LiteRT on-device support"
                    )
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/getUpdates?limit=20")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val updates = json.optJSONArray("result") ?: JSONArray()
            val list = mutableListOf<SocialInteraction>()
            for (i in 0 until updates.length()) {
                val item = updates.getJSONObject(i)
                val msg = item.optJSONObject("message") ?: continue
                val from = msg.optJSONObject("from")
                val text = msg.optString("text", "")
                val date = msg.optLong("date", 0) * 1000L
                if (text.isNotBlank()) {
                    list.add(
                        SocialInteraction(
                            id = "tg_upd_${item.optLong("update_id")}",
                            platform = SocialPlatform.TELEGRAM,
                            accountId = credentials.accountId,
                            authorName = from?.optString("first_name", "Telegram User") ?: "Telegram User",
                            authorHandle = from?.optString("username")?.let { "@$it" },
                            type = InteractionType.DIRECT_MESSAGE,
                            category = InteractionCategory.GENERAL,
                            priority = InteractionPriority.NORMAL,
                            content = text,
                            timestamp = if (date > 0) date else System.currentTimeMillis(),
                            status = "UNREAD"
                        )
                    )
                }
            }
            list
        }
    }

    override suspend fun fetchComments(credentials: SocialCredentials, postId: String?): Result<List<SocialComment>> {
        val token = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                listOf(
                    SocialComment(
                        id = "tg_comm_1",
                        platformCommentId = "tg_msg_901",
                        postId = postId,
                        platform = SocialPlatform.TELEGRAM,
                        authorName = "DevDan",
                        authorAvatarUrl = null,
                        content = "When is the official rollout for OpenDroid?",
                        timestamp = System.currentTimeMillis() - 1800_000,
                        sentiment = Sentiment.POSITIVE,
                        category = InteractionCategory.QUESTION,
                        priority = InteractionPriority.HIGH
                    )
                )
            )
        }
        return Result.success(emptyList())
    }

    override suspend fun replyToComment(credentials: SocialCredentials, commentId: String, text: String): Result<ReplyCommentResult> {
        val token = credentials.botToken ?: credentials.accessToken
        val chatId = credentials.channelOrTargetId ?: "@opendroid_community"

        if (isSandboxToken(token)) {
            return Result.success(
                ReplyCommentResult(
                    success = true,
                    platformReplyId = "tg_reply_${System.currentTimeMillis()}"
                )
            )
        }

        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            if (commentId.toLongOrNull() != null) {
                put("reply_to_message_id", commentId.toLong())
            }
        }

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val result = json.getJSONObject("result")
            ReplyCommentResult(
                success = true,
                platformReplyId = result.getLong("message_id").toString()
            )
        }
    }

    override suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics> {
        val token = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAnalytics(
                    accountId = credentials.accountId,
                    platform = SocialPlatform.TELEGRAM,
                    followersCount = 1420,
                    postsCount = 45,
                    totalViews = 18400,
                    totalComments = 320,
                    engagementRate = 4.2f
                )
            )
        }
        return Result.success(
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.TELEGRAM,
                followersCount = null, // Telegram Bot API does not expose subscriber count directly unless administrator
                postsCount = null
            )
        )
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials) // Bot tokens do not expire
    }
}
