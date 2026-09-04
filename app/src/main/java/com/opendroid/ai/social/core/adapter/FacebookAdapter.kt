package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FacebookAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.FACEBOOK
    override val displayName: String = "Facebook"
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
                    id = "fb_${credentials.accountId.ifBlank { "opendroid.page" }}",
                    username = "opendroid.official",
                    displayName = "OpenDroid Autonomous AI Page",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        val request = Request.Builder()
            .url("https://graph.facebook.com/v19.0/me?fields=id,name,picture&access_token=$token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            SocialAccountInfo(
                id = json.getString("id"),
                username = json.optString("id", "facebook_page"),
                displayName = json.getString("name"),
                avatarUrl = json.optJSONObject("picture")?.optJSONObject("data")?.optString("url"),
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        val token = credentials.accessToken
        val pageId = credentials.accountId
        if (isSandboxToken(token)) {
            val postId = "fb_post_${System.currentTimeMillis()}"
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = postId,
                    url = "https://facebook.com/$postId"
                )
            )
        }

        val payload = JSONObject().apply {
            put("message", post.content)
            put("access_token", token)
        }

        val request = Request.Builder()
            .url("https://graph.facebook.com/v19.0/$pageId/feed")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val id = json.getString("id")
            PublishPostResult(
                success = true,
                platformPostId = id,
                url = "https://facebook.com/$id"
            )
        }
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "fb_int_1",
                        platform = SocialPlatform.FACEBOOK,
                        accountId = credentials.accountId,
                        authorName = "Michael Brown",
                        authorHandle = null,
                        type = InteractionType.COMMENT,
                        category = InteractionCategory.QUESTION,
                        priority = InteractionPriority.NORMAL,
                        content = "Does OpenDroid have a desktop companion or is it strictly mobile on-device?",
                        timestamp = System.currentTimeMillis() - 10800_000,
                        status = "UNREAD",
                        suggestedAction = "Draft response explaining autonomous Android-first design"
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
                        id = "fb_comm_1",
                        platformCommentId = "fb_c_201",
                        postId = postId,
                        platform = SocialPlatform.FACEBOOK,
                        authorName = "Laura Davis",
                        authorAvatarUrl = null,
                        content = "Great project! Excited to test it on my Samsung tablet.",
                        timestamp = System.currentTimeMillis() - 7200_000,
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
                    platformReplyId = "fb_rep_${System.currentTimeMillis()}"
                )
            )
        }

        val payload = JSONObject().apply {
            put("message", text)
            put("access_token", token)
        }

        val request = Request.Builder()
            .url("https://graph.facebook.com/v19.0/$commentId/comments")
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
                    platform = SocialPlatform.FACEBOOK,
                    followersCount = 3120,
                    postsCount = 29,
                    totalLikes = 1840,
                    totalComments = 290,
                    totalShares = 145,
                    reach = 19800,
                    impressions = 32000,
                    engagementRate = 4.1f
                )
            )
        }

        return Result.success(
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.FACEBOOK,
                followersCount = null
            )
        )
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials)
    }
}
