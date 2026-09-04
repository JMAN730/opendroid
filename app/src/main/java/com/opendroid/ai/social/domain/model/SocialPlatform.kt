package com.opendroid.ai.social.domain.model

enum class SocialPlatform(val id: String, val displayName: String, val iconName: String) {
    INSTAGRAM("INSTAGRAM", "Instagram", "instagram"),
    FACEBOOK("FACEBOOK", "Facebook", "facebook"),
    X("X", "X (Twitter)", "x"),
    LINKEDIN("LINKEDIN", "LinkedIn", "linkedin"),
    YOUTUBE("YOUTUBE", "YouTube", "youtube"),
    TELEGRAM("TELEGRAM", "Telegram", "telegram"),
    DISCORD("DISCORD", "Discord", "discord");

    companion object {
        fun fromId(id: String): SocialPlatform =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown platform: $id")

        fun fromIdOrNull(id: String?): SocialPlatform? =
            id?.let { nonNullId -> entries.firstOrNull { it.id.equals(nonNullId, ignoreCase = true) } }
    }
}

enum class AccountStatus {
    CONNECTED,
    DISCONNECTED,
    EXPIRED,
    REAUTH_REQUIRED,
    PERMISSION_DENIED,
    ERROR
}

enum class SocialPermission(val title: String, val description: String) {
    READ_PROFILE("Read Profile", "View public profile information and avatar"),
    READ_POSTS("Read Posts", "Read your published posts and status updates"),
    READ_COMMENTS("Read Comments", "Read incoming comments on your content"),
    READ_MENTIONS("Read Mentions", "Monitor mentions and notifications"),
    REPLY_TO_COMMENTS("Reply to Comments", "Draft and post replies to comments"),
    PUBLISH_POSTS("Publish Posts", "Publish approved posts to your timeline/channel"),
    SCHEDULE_POSTS("Schedule Posts", "Schedule content to be published at future times"),
    READ_ANALYTICS("Read Analytics", "Read engagement, follower, and reach metrics"),
    MANAGE_MESSAGES("Manage Messages", "Read and respond to direct messages / community chats")
}

enum class PostStatus {
    DRAFT,
    SCHEDULED,
    PUBLISHED,
    FAILED,
    CANCELLED
}

enum class AutomationLevel(val displayName: String) {
    SAFE("Safe Mode"),
    APPROVAL("Approval Required"),
    AUTONOMOUS("Autonomous Agent")
}

enum class InteractionType {
    MENTION,
    COMMENT,
    DIRECT_MESSAGE,
    REACTION,
    SHARE
}

enum class InteractionCategory {
    QUESTION,
    POSITIVE_FEEDBACK,
    NEGATIVE_FEEDBACK,
    SUPPORT_REQUEST,
    FEATURE_REQUEST,
    BUG_REPORT,
    LEAD,
    COLLABORATION,
    SPAM,
    GENERAL
}

enum class InteractionPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

enum class CommentReplyStatus {
    NONE,
    SUGGESTED,
    APPROVED,
    REPLIED,
    IGNORED
}

enum class Sentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE,
    MIXED
}

enum class ContentType {
    ANNOUNCEMENT,
    THREAD,
    PRODUCT_UPDATE,
    BEHIND_THE_SCENES,
    COMMUNITY,
    TUTORIAL
}

enum class CampaignStatus {
    PLANNING,
    ACTIVE,
    COMPLETED,
    PAUSED
}
