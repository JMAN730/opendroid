package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscordAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.DISCORD
    override val displayName: String = "Discord"
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
        val webhook = credentials.webhookUrl
        val botToken = credentials.botToken ?: credentials.accessToken

        if (isSandboxToken(botToken) || isSandboxToken(webhook.orEmpty())) {
            return Result.success(
                SocialAccountInfo(
                    id = "dc_${credentials.accountId.ifBlank { "opendroid_server" }}",
                    username = "OpenDroidCommunity",
                    displayName = "OpenDroid Discord",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        if (!webhook.isNullOrBlank()) {
            val request = Request.Builder().url(webhook).get().build()
            return executeRequest(request).mapCatching { jsonStr ->
                val json = JSONObject(jsonStr)
                SocialAccountInfo(
                    id = json.optString("id", "dc_webhook"),
                    username = json.optString("name", "Discord Webhook"),
                    displayName = "Discord: " + json.optString("name", "Channel"),
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            }
        }

        val request = Request.Builder()
            .url("https://discord.com/api/v10/users/@me")
            .header("Authorization", "Bot $botToken")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            SocialAccountInfo(
                id = json.getString("id"),
                username = json.getString("username"),
                displayName = json.optString("global_name", json.getString("username")),
                avatarUrl = json.optString("avatar", null)?.let { "https://cdn.discordapp.com/avatars/${json.getString("id")}/$it.png" },
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        val webhook = credentials.webhookUrl
        val botToken = credentials.botToken ?: credentials.accessToken
        val channelId = credentials.channelOrTargetId

        if (isSandboxToken(botToken) || isSandboxToken(webhook.orEmpty())) {
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = "dc_msg_${System.currentTimeMillis()}",
                    url = "https://discord.com/channels/opendroid/${System.currentTimeMillis()}"
                )
            )
        }

        val payload = JSONObject().apply {
            put("content", post.content)
        }

        val request = if (!webhook.isNullOrBlank()) {
            Request.Builder()
                .url("$webhook?wait=true")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        } else if (!channelId.isNullOrBlank()) {
            Request.Builder()
                .url("https://discord.com/api/v10/channels/$channelId/messages")
                .header("Authorization", "Bot $botToken")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        } else {
            return Result.failure(IllegalArgumentException("Discord requires either a webhookUrl or a channelId"))
        }

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val id = json.optString("id", System.currentTimeMillis().toString())
            PublishPostResult(
                success = true,
                platformPostId = id,
                url = if (!channelId.isNullOrBlank()) "https://discord.com/channels/@me/$channelId/$id" else null
            )
        }
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        val botToken = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(botToken) || isSandboxToken(credentials.webhookUrl.orEmpty())) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "dc_int_1",
                        platform = SocialPlatform.DISCORD,
                        accountId = credentials.accountId,
                        authorName = "KernelHacker",
                        authorHandle = "kernel#4021",
                        type = InteractionType.MENTION,
                        category = InteractionCategory.FEATURE_REQUEST,
                        priority = InteractionPriority.HIGH,
                        content = "Could OpenDroid integrate with custom local Ollama models over LAN?",
                        timestamp = System.currentTimeMillis() - 7200_000,
                        status = "UNREAD",
                        suggestedAction = "Confirm Ollama HTTP URL support in settings"
                    )
                )
            )
        }
        return Result.success(emptyList())
    }

    override suspend fun fetchComments(credentials: SocialCredentials, postId: String?): Result<List<SocialComment>> {
        val botToken = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(botToken)) {
            return Result.success(
                listOf(
                    SocialComment(
                        id = "dc_comm_1",
                        platformCommentId = "dc_msg_8821",
                        postId = postId,
                        platform = SocialPlatform.DISCORD,
                        authorName = "SamR",
                        authorAvatarUrl = null,
                        content = "Love the clean OLED black design on the new update!",
                        timestamp = System.currentTimeMillis() - 4000_000,
                        sentiment = Sentiment.POSITIVE,
                        category = InteractionCategory.POSITIVE_FEEDBACK,
                        priority = InteractionPriority.NORMAL
                    )
                )
            )
        }
        return Result.success(emptyList())
    }

    override suspend fun replyToComment(credentials: SocialCredentials, commentId: String, text: String): Result<ReplyCommentResult> {
        val webhook = credentials.webhookUrl
        val botToken = credentials.botToken ?: credentials.accessToken
        val channelId = credentials.channelOrTargetId

        if (isSandboxToken(botToken) || isSandboxToken(webhook.orEmpty())) {
            return Result.success(
                ReplyCommentResult(
                    success = true,
                    platformReplyId = "dc_reply_${System.currentTimeMillis()}"
                )
            )
        }

        val payload = JSONObject().apply {
            put("content", text)
            if (commentId.isNotBlank()) {
                val ref = JSONObject().apply { put("message_id", commentId) }
                put("message_reference", ref)
            }
        }

        val request = if (!channelId.isNullOrBlank()) {
            Request.Builder()
                .url("https://discord.com/api/v10/channels/$channelId/messages")
                .header("Authorization", "Bot $botToken")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        } else if (!webhook.isNullOrBlank()) {
            Request.Builder()
                .url("$webhook?wait=true")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        } else {
            return Result.failure(IllegalArgumentException("Discord target channel or webhook missing"))
        }

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            ReplyCommentResult(
                success = true,
                platformReplyId = json.optString("id", System.currentTimeMillis().toString())
            )
        }
    }

    override suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics> {
        val botToken = credentials.botToken ?: credentials.accessToken
        if (isSandboxToken(botToken) || isSandboxToken(credentials.webhookUrl.orEmpty())) {
            return Result.success(
                SocialAnalytics(
                    accountId = credentials.accountId,
                    platform = SocialPlatform.DISCORD,
                    followersCount = 890,
                    postsCount = 28,
                    totalViews = 9500,
                    totalComments = 412,
                    engagementRate = 5.6f
                )
            )
        }
        return Result.success(
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.DISCORD,
                followersCount = null
            )
        )
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials)
    }
}
