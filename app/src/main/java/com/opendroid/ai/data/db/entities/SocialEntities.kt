package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "social_accounts",
    indices = [
        Index("platform"),
        Index("status")
    ]
)
data class SocialAccountEntity(
    @PrimaryKey
    val id: String,
    val platform: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val status: String,
    val permissionsJson: String,
    val connectedAt: Long,
    val tokenExpiresAt: Long?,
    val lastSyncAt: Long?,
    val errorMessage: String?
)

@Entity(
    tableName = "social_posts",
    indices = [
        Index("accountId"),
        Index("platform"),
        Index("status"),
        Index("scheduledPublishTime"),
        Index("campaignId")
    ]
)
data class SocialPostEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val platform: String,
    val content: String,
    val mediaUrlsJson: String,
    val status: String,
    val scheduledPublishTime: Long?,
    val publishedTime: Long?,
    val platformPostId: String?,
    val campaignId: String?,
    val contentType: String,
    val requiresApproval: Boolean,
    val approvedBy: String?,
    val approvedAt: Long?,
    val errorMessage: String?,
    val likesCount: Int,
    val commentsCount: Int,
    val sharesCount: Int,
    val savesCount: Int,
    val viewsCount: Int,
    val reach: Int,
    val impressions: Int,
    val engagementRate: Float,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "social_comments",
    indices = [
        Index("postId"),
        Index("platform"),
        Index("replyStatus"),
        Index("timestamp")
    ]
)
data class SocialCommentEntity(
    @PrimaryKey
    val id: String,
    val platformCommentId: String,
    val postId: String?,
    val platform: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val content: String,
    val timestamp: Long,
    val isAnswered: Boolean,
    val suggestedReply: String?,
    val actualReply: String?,
    val replyStatus: String,
    val replyTimestamp: Long?,
    val sentiment: String,
    val category: String,
    val priority: String
)

@Entity(
    tableName = "social_interactions",
    indices = [
        Index("accountId"),
        Index("platform"),
        Index("status"),
        Index("priority"),
        Index("timestamp")
    ]
)
data class SocialInteractionEntity(
    @PrimaryKey
    val id: String,
    val platform: String,
    val accountId: String,
    val authorName: String,
    val authorHandle: String?,
    val type: String,
    val category: String,
    val priority: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val suggestedAction: String?
)

@Entity(
    tableName = "social_analytics_snapshots",
    indices = [
        Index("accountId"),
        Index("platform"),
        Index("timestamp")
    ]
)
data class SocialAnalyticsSnapshotEntity(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val platform: String,
    val timestamp: Long,
    val followersCount: Int,
    val followingCount: Int,
    val postsCount: Int,
    val totalLikes: Int,
    val totalComments: Int,
    val totalShares: Int,
    val totalViews: Int,
    val reach: Int,
    val impressions: Int,
    val engagementRate: Float,
    val subscribersCount: Int,
    val profileVisits: Int,
    val linkClicks: Int,
    val periodLabel: String
)

@Entity(
    tableName = "social_campaigns",
    indices = [
        Index("status"),
        Index("startDate"),
        Index("endDate")
    ]
)
data class SocialCampaignEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val objective: String,
    val startDate: Long,
    val endDate: Long,
    val platformsJson: String,
    val targetAudience: String,
    val strategySummary: String,
    val status: String,
    val budget: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "social_automation_rules",
    indices = [
        Index("isEnabled"),
        Index("triggerType")
    ]
)
data class SocialAutomationRuleEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val isEnabled: Boolean,
    val platform: String?,
    val triggerType: String,
    val keywordsJson: String,
    val actionType: String,
    val replyTemplate: String?,
    val confidenceThreshold: Float,
    val requireHumanApproval: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "social_audit_logs",
    indices = [
        Index("timestamp"),
        Index("actor"),
        Index("platform"),
        Index("action")
    ]
)
data class SocialAuditLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val platform: String,
    val targetId: String?,
    val details: String,
    val status: String
)
