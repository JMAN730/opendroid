package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstagramAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.INSTAGRAM
    override val displayName: String = "Instagram"
    override val supportedPermissions: List<SocialPermission> = listOf(
        SocialPermission.READ_PROFILE,
        SocialPermission.READ_POSTS,
        SocialPermission.READ_COMMENTS,
        SocialPermission.REPLY_TO_COMMENTS,
        SocialPermission.PUBLISH_POSTS,
        SocialPermission.SCHEDULE_POSTS,
        SocialPermission.READ_ANALYTICS
    )

    override suspend fun validateCredentials(credentials: SocialCredentials): Result<SocialAccountInfo> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAccountInfo(
                    id = "ig_${credentials.accountId.ifBlank { "opendroid.app" }}",
                    username = "opendroid.app",
                    displayName = "OpenDroid AI Assistant",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        val request = Request.Builder()
            .url("https://graph.facebook.com/v19.0/me?fields=id,username,name,profile_picture_url&access_token=$token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            SocialAccountInfo(
                id = json.getString("id"),
                username = json.optString("username", "instagram_user"),
                displayName = json.optString("name", "Instagram Account"),
                avatarUrl = json.optString("profile_picture_url", null),
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            val mediaId = "ig_media_${System.currentTimeMillis()}"
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = mediaId,
                    url = "https://instagram.com/p/$mediaId"
                )
            )
        }

        val igUserId = credentials.accountId
        val mediaUrl = post.mediaUrls.firstOrNull() ?: "https://opendroid.ai/assets/logo.png"

        // 1. Create Media Container
        val containerPayload = JSONObject().apply {
            put("image_url", mediaUrl)
            put("caption", post.content)
            put("access_token", token)
        }

        val containerRequest = Request.Builder()
            .url("https://graph.facebook.com/v19.0/$igUserId/media")
            .post(containerPayload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(containerRequest).mapCatching { containerJsonStr ->
            val containerJson = JSONObject(containerJsonStr)
            val creationId = containerJson.getString("id")

            // 2. Publish Media Container
            val publishPayload = JSONObject().apply {
                put("creation_id", creationId)
                put("access_token", token)
            }
            val publishRequest = Request.Builder()
                .url("https://graph.facebook.com/v19.0/$igUserId/media_publish")
                .post(publishPayload.toString().toRequestBody(jsonMediaType))
                .build()

            val publishJsonStr = executeRequest(publishRequest).getOrThrow()
            val publishJson = JSONObject(publishJsonStr)
            val publishedId = publishJson.getString("id")

            PublishPostResult(
                success = true,
                platformPostId = publishedId,
                url = "https://instagram.com/p/$publishedId"
            )
        }
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "ig_int_1",
                        platform = SocialPlatform.INSTAGRAM,
                        accountId = credentials.accountId,
                        authorName = "Chloe Martin",
                        authorHandle = "@chloe_design",
                        type = InteractionType.DIRECT_MESSAGE,
                        category = InteractionCategory.QUESTION,
                        priority = InteractionPriority.NORMAL,
                        content = "Is the classic pure white theme already available on Google Play?",
                        timestamp = System.currentTimeMillis() - 4200_000,
                        status = "UNREAD",
                        suggestedAction = "Draft response confirming rollout status"
                    )
                )
            )
        }
        return Result.success(emptyList())
    }

    override suspend fun fetchComments(credentials: SocialCredentials, postId: String?): Result<List<SocialComment>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialComment(
                        id = "ig_comm_1",
                        platformCommentId = "ig_c_99",
                        postId = postId,
                        platform = SocialPlatform.INSTAGRAM,
                        authorName = "Jordan K",
                        authorAvatarUrl = null,
                        content = "The fluid animations and wave listening mic look gorgeous!",
                        timestamp = System.currentTimeMillis() - 1900_000,
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
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                ReplyCommentResult(
                    success = true,
                    platformReplyId = "ig_rep_${System.currentTimeMillis()}"
                )
            )
        }

        val payload = JSONObject().apply {
            put("message", text)
            put("access_token", token)
        }

        val request = Request.Builder()
            .url("https://graph.facebook.com/v19.0/$commentId/replies")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            ReplyCommentResult(
                success = true,
                platformReplyId = json.getString("id")
            )
        }
    }

    override suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAnalytics(
                    accountId = credentials.accountId,
                    platform = SocialPlatform.INSTAGRAM,
                    followersCount = 4920,
                    followingCount = 280,
                    postsCount = 36,
                    totalLikes = 3840,
                    totalComments = 512,
                    totalShares = 280,
                    reach = 34500,
                    impressions = 61200,
                    engagementRate = 7.8f,
                    profileVisits = 1420
                )
            )
        }

        val igUserId = credentials.accountId
        val request = Request.Builder()
            .url("https://graph.facebook.com/v19.0/$igUserId?fields=followers_count,follows_count,media_count&access_token=$token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.INSTAGRAM,
                followersCount = json.optInt("followers_count"),
                followingCount = json.optInt("follows_count"),
                postsCount = json.optInt("media_count")
            )
        }
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials)
    }
}
