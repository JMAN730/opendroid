package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XAdapter @Inject constructor(
    httpClient: OkHttpClient
) : BaseSocialAdapter(httpClient), SocialPlatformAdapter {

    override val platform: SocialPlatform = SocialPlatform.X
    override val displayName: String = "X (Twitter)"
    override val supportedPermissions: List<SocialPermission> = listOf(
        SocialPermission.READ_PROFILE,
        SocialPermission.READ_POSTS,
        SocialPermission.READ_COMMENTS,
        SocialPermission.READ_MENTIONS,
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
                    id = "x_${credentials.accountId.ifBlank { "opendroid_ai" }}",
                    username = "OpenDroidAI",
                    displayName = "OpenDroid AI",
                    avatarUrl = null,
                    grantedPermissions = supportedPermissions
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.twitter.com/2/users/me?user.fields=profile_image_url,public_metrics")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val data = json.getJSONObject("data")
            SocialAccountInfo(
                id = data.getString("id"),
                username = data.getString("username"),
                displayName = data.getString("name"),
                avatarUrl = data.optString("profile_image_url", null),
                grantedPermissions = supportedPermissions
            )
        }
    }

    override suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            val tweetId = "tweet_${System.currentTimeMillis()}"
            return Result.success(
                PublishPostResult(
                    success = true,
                    platformPostId = tweetId,
                    url = "https://x.com/OpenDroidAI/status/$tweetId"
                )
            )
        }

        val payload = JSONObject().apply {
            put("text", post.content)
        }

        val request = Request.Builder()
            .url("https://api.twitter.com/2/tweets")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val data = json.getJSONObject("data")
            val tweetId = data.getString("id")
            PublishPostResult(
                success = true,
                platformPostId = tweetId,
                url = "https://x.com/i/status/$tweetId"
            )
        }
    }

    override suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                listOf(
                    SocialInteraction(
                        id = "x_int_1",
                        platform = SocialPlatform.X,
                        accountId = credentials.accountId,
                        authorName = "Elena Vance",
                        authorHandle = "@elena_v",
                        type = InteractionType.MENTION,
                        category = InteractionCategory.POSITIVE_FEEDBACK,
                        priority = InteractionPriority.NORMAL,
                        content = "Tested @OpenDroidAI on my Pixel phone and the voice assistant latency is incredible 🔥",
                        timestamp = System.currentTimeMillis() - 5400_000,
                        status = "UNREAD",
                        suggestedAction = "Thank user and retweet/quote"
                    )
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.twitter.com/2/users/${credentials.accountId}/mentions?max_results=10")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val data = json.optJSONArray("data")
            val list = mutableListOf<SocialInteraction>()
            if (data != null) {
                for (i in 0 until data.length()) {
                    val tweet = data.getJSONObject(i)
                    list.add(
                        SocialInteraction(
                            id = "x_mention_${tweet.getString("id")}",
                            platform = SocialPlatform.X,
                            accountId = credentials.accountId,
                            authorName = "X User",
                            authorHandle = null,
                            type = InteractionType.MENTION,
                            category = InteractionCategory.GENERAL,
                            priority = InteractionPriority.NORMAL,
                            content = tweet.getString("text"),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            list
        }
    }

    override suspend fun fetchComments(credentials: SocialCredentials, postId: String?): Result<List<SocialComment>> {
        if (isSandboxToken(credentials.accessToken)) {
            return Result.success(
                listOf(
                    SocialComment(
                        id = "x_comm_1",
                        platformCommentId = "x_reply_101",
                        postId = postId,
                        platform = SocialPlatform.X,
                        authorName = "Marcus Tech",
                        authorAvatarUrl = null,
                        content = "Is the LiteRT on-device inference fully offline?",
                        timestamp = System.currentTimeMillis() - 2500_000,
                        sentiment = Sentiment.NEUTRAL,
                        category = InteractionCategory.QUESTION,
                        priority = InteractionPriority.HIGH
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
                    platformReplyId = "x_reply_${System.currentTimeMillis()}"
                )
            )
        }

        val replyObj = JSONObject().apply {
            put("in_reply_to_tweet_id", commentId)
        }
        val payload = JSONObject().apply {
            put("text", text)
            put("reply", replyObj)
        }

        val request = Request.Builder()
            .url("https://api.twitter.com/2/tweets")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val data = json.getJSONObject("data")
            ReplyCommentResult(
                success = true,
                platformReplyId = data.getString("id")
            )
        }
    }

    override suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics> {
        val token = credentials.accessToken
        if (isSandboxToken(token)) {
            return Result.success(
                SocialAnalytics(
                    accountId = credentials.accountId,
                    platform = SocialPlatform.X,
                    followersCount = 3840,
                    followingCount = 192,
                    postsCount = 118,
                    totalLikes = 2450,
                    totalComments = 480,
                    totalShares = 630,
                    reach = 48200,
                    impressions = 89000,
                    engagementRate = 4.7f
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.twitter.com/2/users/me?user.fields=public_metrics")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request).mapCatching { jsonStr ->
            val json = JSONObject(jsonStr)
            val metrics = json.getJSONObject("data").getJSONObject("public_metrics")
            SocialAnalytics(
                accountId = credentials.accountId,
                platform = SocialPlatform.X,
                followersCount = metrics.getInt("followers_count"),
                followingCount = metrics.getInt("following_count"),
                postsCount = metrics.getInt("tweet_count")
            )
        }
    }

    override suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials> {
        return Result.success(credentials)
    }
}
