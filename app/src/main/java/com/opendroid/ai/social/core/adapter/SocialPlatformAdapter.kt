package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*

data class SocialAccountInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val grantedPermissions: List<SocialPermission>
)

interface SocialPlatformAdapter {
    val platform: SocialPlatform
    val displayName: String
    val supportedPermissions: List<SocialPermission>

    suspend fun validateCredentials(credentials: SocialCredentials): Result<SocialAccountInfo>
    suspend fun publishPost(credentials: SocialCredentials, post: SocialPost): Result<PublishPostResult>
    suspend fun fetchInbox(credentials: SocialCredentials, since: Long?): Result<List<SocialInteraction>>
    // Called once the last fetchInbox batch is saved, so a platform that confirms what it has
    // delivered (Telegram's getUpdates offset) only does so after nothing can be lost.
    fun acknowledgeInbox(credentials: SocialCredentials) {}
    suspend fun fetchComments(credentials: SocialCredentials, postId: String?): Result<List<SocialComment>>
    suspend fun replyToComment(credentials: SocialCredentials, commentId: String, text: String): Result<ReplyCommentResult>
    // Inbox items (DMs, mentions) aren't comments, so each platform answers them its own way.
    suspend fun replyToInteraction(credentials: SocialCredentials, interaction: SocialInteraction, text: String): Result<ReplyCommentResult>
    suspend fun fetchAnalytics(credentials: SocialCredentials): Result<SocialAnalytics>
    suspend fun refreshToken(credentials: SocialCredentials): Result<SocialCredentials>
}
