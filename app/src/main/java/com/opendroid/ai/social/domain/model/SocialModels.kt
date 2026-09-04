package com.opendroid.ai.social.domain.model

data class SocialCredentials(
    val accountId: String,
    val platform: SocialPlatform,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long? = null,
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val botToken: String? = null,
    val webhookUrl: String? = null,
    val channelOrTargetId: String? = null
) {
    override fun toString(): String =
        "SocialCredentials(accountId=$accountId, platform=$platform, [PROTECTED_CREDENTIALS])"
}

data class SocialAccount(
    val id: String,
    val platform: SocialPlatform,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val status: AccountStatus = AccountStatus.CONNECTED,
    val permissions: List<SocialPermission> = emptyList(),
    val connectedAt: Long = System.currentTimeMillis(),
    val tokenExpiresAt: Long? = null,
    val lastSyncAt: Long? = null,
    val errorMessage: String? = null
)

data class SocialPost(
    val id: String,
    val accountId: String,
    val platform: SocialPlatform,
    val content: String,
    val mediaUrls: List<String> = emptyList(),
    val status: PostStatus = PostStatus.DRAFT,
    val scheduledPublishTime: Long? = null,
    val publishedTime: Long? = null,
    val platformPostId: String? = null,
    val campaignId: String? = null,
    val contentType: ContentType = ContentType.ANNOUNCEMENT,
    val requiresApproval: Boolean = true,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val errorMessage: String? = null,
    val likesCount: Int = 0,
    val commentsCount: Int = 0,
    val sharesCount: Int = 0,
    val savesCount: Int = 0,
    val viewsCount: Int = 0,
    val reach: Int = 0,
    val impressions: Int = 0,
    val engagementRate: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SocialComment(
    val id: String,
    val platformCommentId: String,
    val postId: String?,
    val platform: SocialPlatform,
    val authorName: String,
    val authorAvatarUrl: String? = null,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isAnswered: Boolean = false,
    val suggestedReply: String? = null,
    val actualReply: String? = null,
    val replyStatus: CommentReplyStatus = CommentReplyStatus.NONE,
    val replyTimestamp: Long? = null,
    val sentiment: Sentiment = Sentiment.NEUTRAL,
    val category: InteractionCategory = InteractionCategory.GENERAL,
    val priority: InteractionPriority = InteractionPriority.NORMAL
)

data class SocialInteraction(
    val id: String,
    val platform: SocialPlatform,
    val accountId: String,
    val authorName: String,
    val authorHandle: String? = null,
    val type: InteractionType = InteractionType.MENTION,
    val category: InteractionCategory = InteractionCategory.GENERAL,
    val priority: InteractionPriority = InteractionPriority.NORMAL,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "UNREAD", // UNREAD, READ, RESOLVED, REPLIED
    val suggestedAction: String? = null
)

data class SocialAnalytics(
    val accountId: String,
    val platform: SocialPlatform,
    val timestamp: Long = System.currentTimeMillis(),
    val followersCount: Int? = null,
    val followingCount: Int? = null,
    val postsCount: Int? = null,
    val totalLikes: Int? = null,
    val totalComments: Int? = null,
    val totalShares: Int? = null,
    val totalViews: Int? = null,
    val reach: Int? = null,
    val impressions: Int? = null,
    val engagementRate: Float? = null,
    val subscribersCount: Int? = null,
    val profileVisits: Int? = null,
    val linkClicks: Int? = null,
    val periodLabel: String = "SNAPSHOT_DAILY"
)

data class SocialCampaign(
    val id: String,
    val name: String,
    val objective: String,
    val startDate: Long,
    val endDate: Long,
    val platforms: List<SocialPlatform>,
    val targetAudience: String,
    val strategySummary: String,
    val status: CampaignStatus = CampaignStatus.PLANNING,
    val budget: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SocialAutomationRule(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val platform: SocialPlatform? = null, // null for all platforms
    val triggerType: String, // KEYWORD_MATCH, INTENT_QUESTION, SENTIMENT_POSITIVE, HIGH_ENGAGEMENT
    val keywords: List<String> = emptyList(),
    val actionType: String, // AUTO_REPLY, DRAFT_REPLY_NOTIFY, ESCALATE_TO_HUMAN, AUTO_SCHEDULE
    val replyTemplate: String? = null,
    val confidenceThreshold: Float = 0.90f,
    val requireHumanApproval: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class SocialAuditEntry(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actor: String, // OPENDROID_AI, USER, AUTO_RULE
    val action: String, // DRAFTED_POST, APPROVED_POST, PUBLISHED_POST, SCHEDULED_POST, CLASSIFIED_INBOX, SUGGESTED_REPLY, SENT_REPLY, CONNECTED_ACCOUNT, REVOKED_ACCOUNT
    val platform: SocialPlatform,
    val targetId: String? = null,
    val details: String,
    val status: String // SUCCESS, FAILED, NEEDS_APPROVAL
)

data class SocialReport(
    val id: String,
    val periodTitle: String,
    val startDate: Long,
    val endDate: Long,
    val totalFollowersDelta: Int,
    val totalReach: Int,
    val totalImpressions: Int,
    val averageEngagementRate: Float,
    val totalPostsPublished: Int,
    val totalCommentsReceived: Int,
    val totalRepliesSent: Int,
    val topPlatform: SocialPlatform,
    val topPostSummary: String,
    val aiSummary: String,
    val recommendations: List<String>,
    val generatedAt: Long = System.currentTimeMillis()
)

data class PublishPostResult(
    val success: Boolean,
    val platformPostId: String? = null,
    val url: String? = null,
    val errorMessage: String? = null
)

data class ReplyCommentResult(
    val success: Boolean,
    val platformReplyId: String? = null,
    val errorMessage: String? = null
)

data class SocialInsight(
    val id: String,
    val observedData: String,
    val calculatedInsight: String,
    val aiRecommendation: String,
    val category: String,
    val confidence: Float = 0.85f,
    val timestamp: Long = System.currentTimeMillis()
)
