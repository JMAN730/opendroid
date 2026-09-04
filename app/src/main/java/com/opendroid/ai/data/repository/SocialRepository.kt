package com.opendroid.ai.data.repository

import com.opendroid.ai.data.db.dao.*
import com.opendroid.ai.data.db.entities.*
import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class SocialRepository @Inject constructor(
    private val accountDao: SocialAccountDao,
    private val postDao: SocialPostDao,
    private val commentDao: SocialCommentDao,
    private val interactionDao: SocialInteractionDao,
    private val analyticsDao: SocialAnalyticsDao,
    private val campaignDao: SocialCampaignDao,
    private val ruleDao: SocialAutomationRuleDao,
    private val auditDao: SocialAuditLogDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── ACCOUNTS ─────────────────────────────────────────────────────────────

    fun getAllAccountsFlow(): Flow<List<SocialAccount>> =
        accountDao.getAllAccountsFlow().map { list -> list.map { it.toDomain() } }

    fun getConnectedAccountsFlow(): Flow<List<SocialAccount>> =
        accountDao.getConnectedAccountsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getAccountById(id: String): SocialAccount? =
        accountDao.getAccountById(id)?.toDomain()

    suspend fun getAccountByPlatform(platform: SocialPlatform): SocialAccount? =
        accountDao.getAccountByPlatform(platform.id)?.toDomain()

    suspend fun saveAccount(account: SocialAccount) {
        accountDao.insertOrUpdate(account.toEntity())
    }

    suspend fun updateAccountStatus(id: String, status: AccountStatus, errorMessage: String? = null) {
        accountDao.updateStatus(id, status.name, errorMessage)
    }

    suspend fun updateLastSync(id: String, timestamp: Long) {
        accountDao.updateLastSync(id, timestamp)
    }

    suspend fun deleteAccount(id: String) {
        accountDao.deleteById(id)
    }

    // ── POSTS ────────────────────────────────────────────────────────────────

    fun getAllPostsFlow(): Flow<List<SocialPost>> =
        postDao.getAllPostsFlow().map { list -> list.map { it.toDomain() } }

    fun getPostsByStatusFlow(status: PostStatus): Flow<List<SocialPost>> =
        postDao.getPostsByStatusFlow(status.name).map { list -> list.map { it.toDomain() } }

    suspend fun getPostsByStatus(status: PostStatus): List<SocialPost> =
        postDao.getPostsByStatus(status.name).map { it.toDomain() }

    suspend fun getDueScheduledPosts(currentTime: Long): List<SocialPost> =
        postDao.getDueScheduledPosts(currentTime).map { it.toDomain() }

    fun getPostsByPlatformFlow(platform: SocialPlatform): Flow<List<SocialPost>> =
        postDao.getPostsByPlatformFlow(platform.id).map { list -> list.map { it.toDomain() } }

    suspend fun getPostById(id: String): SocialPost? =
        postDao.getPostById(id)?.toDomain()

    suspend fun savePost(post: SocialPost) {
        postDao.insertOrUpdate(post.toEntity())
    }

    suspend fun updatePublishResult(
        id: String,
        status: PostStatus,
        publishedTime: Long?,
        platformPostId: String?,
        errorMessage: String?
    ) {
        postDao.updatePublishResult(id, status.name, publishedTime, platformPostId, errorMessage)
    }

    suspend fun approvePost(id: String, approvedBy: String, immediatePublish: Boolean = false) {
        val nextStatus = if (immediatePublish) PostStatus.PUBLISHED else PostStatus.SCHEDULED
        postDao.approvePost(id, nextStatus.name, approvedBy, System.currentTimeMillis())
    }

    suspend fun reschedulePost(id: String, newTime: Long) {
        postDao.reschedulePost(id, newTime)
    }

    suspend fun cancelPost(id: String) {
        postDao.cancelPost(id)
    }

    suspend fun deletePost(id: String) {
        postDao.deleteById(id)
    }

    suspend fun getTopPerformingPosts(limit: Int = 5): List<SocialPost> =
        postDao.getTopPerformingPosts(limit).map { it.toDomain() }

    // ── COMMENTS ─────────────────────────────────────────────────────────────

    fun getAllCommentsFlow(): Flow<List<SocialComment>> =
        commentDao.getAllCommentsFlow().map { list -> list.map { it.toDomain() } }

    fun getUnansweredCommentsFlow(): Flow<List<SocialComment>> =
        commentDao.getUnansweredCommentsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getUnansweredComments(): List<SocialComment> =
        commentDao.getUnansweredComments().map { it.toDomain() }

    fun getCommentsForPostFlow(postId: String): Flow<List<SocialComment>> =
        commentDao.getCommentsForPostFlow(postId).map { list -> list.map { it.toDomain() } }

    suspend fun saveComment(comment: SocialComment) {
        commentDao.insertOrUpdate(comment.toEntity())
    }

    suspend fun saveComments(comments: List<SocialComment>) {
        commentDao.insertAll(comments.map { it.toEntity() })
    }

    suspend fun updateSuggestedReply(id: String, reply: String) {
        commentDao.updateSuggestedReply(id, reply)
    }

    suspend fun markCommentReplied(id: String, reply: String) {
        commentDao.markReplied(id, reply)
    }

    suspend fun ignoreComment(id: String) {
        commentDao.ignoreComment(id)
    }

    // ── INBOX / INTERACTIONS ─────────────────────────────────────────────────

    fun getAllInteractionsFlow(): Flow<List<SocialInteraction>> =
        interactionDao.getAllInteractionsFlow().map { list -> list.map { it.toDomain() } }

    fun getUnreadInteractionsFlow(): Flow<List<SocialInteraction>> =
        interactionDao.getUnreadInteractionsFlow().map { list -> list.map { it.toDomain() } }

    fun getInteractionsByCategoryFlow(category: InteractionCategory): Flow<List<SocialInteraction>> =
        interactionDao.getInteractionsByCategoryFlow(category.name).map { list -> list.map { it.toDomain() } }

    suspend fun saveInteraction(interaction: SocialInteraction) {
        interactionDao.insertOrUpdate(interaction.toEntity())
    }

    suspend fun saveInteractions(interactions: List<SocialInteraction>) {
        interactionDao.insertAll(interactions.map { it.toEntity() })
    }

    suspend fun updateInteractionStatus(id: String, status: String) {
        interactionDao.updateStatus(id, status)
    }

    // ── ANALYTICS ────────────────────────────────────────────────────────────

    fun getAllSnapshotsFlow(): Flow<List<SocialAnalytics>> =
        analyticsDao.getAllSnapshotsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getLatestSnapshotForPlatform(platform: SocialPlatform): SocialAnalytics? =
        analyticsDao.getLatestSnapshotForPlatform(platform.id)?.toDomain()

    suspend fun getSnapshotsSince(sinceTimestamp: Long): List<SocialAnalytics> =
        analyticsDao.getSnapshotsSince(sinceTimestamp).map { it.toDomain() }

    suspend fun getSnapshotsBetween(fromTimestamp: Long, toTimestamp: Long): List<SocialAnalytics> =
        analyticsDao.getSnapshotsBetween(fromTimestamp, toTimestamp).map { it.toDomain() }

    suspend fun recordAnalyticsSnapshot(snapshot: SocialAnalytics) {
        analyticsDao.insertSnapshot(snapshot.toEntity())
    }

    // ── CAMPAIGNS ────────────────────────────────────────────────────────────

    fun getAllCampaignsFlow(): Flow<List<SocialCampaign>> =
        campaignDao.getAllCampaignsFlow().map { list -> list.map { it.toDomain() } }

    fun getActiveCampaignsFlow(): Flow<List<SocialCampaign>> =
        campaignDao.getActiveCampaignsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getCampaignById(id: String): SocialCampaign? =
        campaignDao.getCampaignById(id)?.toDomain()

    suspend fun saveCampaign(campaign: SocialCampaign) {
        campaignDao.insertOrUpdate(campaign.toEntity())
    }

    suspend fun updateCampaignStatus(id: String, status: CampaignStatus) {
        campaignDao.updateStatus(id, status.name)
    }

    suspend fun deleteCampaign(id: String) {
        campaignDao.deleteById(id)
    }

    // ── AUTOMATION RULES ─────────────────────────────────────────────────────

    fun getAllRulesFlow(): Flow<List<SocialAutomationRule>> =
        ruleDao.getAllRulesFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getActiveRules(): List<SocialAutomationRule> =
        ruleDao.getActiveRules().map { it.toDomain() }

    suspend fun saveRule(rule: SocialAutomationRule) {
        ruleDao.insertOrUpdate(rule.toEntity())
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) {
        ruleDao.setEnabled(id, enabled)
    }

    suspend fun deleteRule(id: String) {
        ruleDao.deleteById(id)
    }

    // ── AUDIT LOGS ───────────────────────────────────────────────────────────

    fun getRecentAuditLogsFlow(limit: Int = 100): Flow<List<SocialAuditEntry>> =
        auditDao.getRecentAuditLogsFlow(limit).map { list -> list.map { it.toDomain() } }

    suspend fun getRecentAuditLogs(limit: Int = 100): List<SocialAuditEntry> =
        auditDao.getRecentAuditLogs(limit).map { it.toDomain() }

    suspend fun recordAuditLog(entry: SocialAuditEntry) {
        auditDao.insertLog(entry.toEntity())
    }

    // ── ENTITY MAPPING EXTENSIONS ───────────────────────────────────────────

    private fun SocialAccountEntity.toDomain(): SocialAccount = SocialAccount(
        id = id,
        platform = SocialPlatform.fromId(platform),
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        status = runCatching { AccountStatus.valueOf(status) }.getOrDefault(AccountStatus.CONNECTED),
        permissions = runCatching {
            json.decodeFromString<List<String>>(permissionsJson).mapNotNull { permStr ->
                runCatching { SocialPermission.valueOf(permStr) }.getOrNull()
            }
        }.getOrDefault(emptyList()),
        connectedAt = connectedAt,
        tokenExpiresAt = tokenExpiresAt,
        lastSyncAt = lastSyncAt,
        errorMessage = errorMessage
    )

    private fun SocialAccount.toEntity(): SocialAccountEntity = SocialAccountEntity(
        id = id,
        platform = platform.id,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        status = status.name,
        permissionsJson = json.encodeToString(permissions.map { it.name }),
        connectedAt = connectedAt,
        tokenExpiresAt = tokenExpiresAt,
        lastSyncAt = lastSyncAt,
        errorMessage = errorMessage
    )

    private fun SocialPostEntity.toDomain(): SocialPost = SocialPost(
        id = id,
        accountId = accountId,
        platform = SocialPlatform.fromId(platform),
        content = content,
        mediaUrls = runCatching { json.decodeFromString<List<String>>(mediaUrlsJson) }.getOrDefault(emptyList()),
        status = runCatching { PostStatus.valueOf(status) }.getOrDefault(PostStatus.DRAFT),
        scheduledPublishTime = scheduledPublishTime,
        publishedTime = publishedTime,
        platformPostId = platformPostId,
        campaignId = campaignId,
        contentType = runCatching { ContentType.valueOf(contentType) }.getOrDefault(ContentType.ANNOUNCEMENT),
        requiresApproval = requiresApproval,
        approvedBy = approvedBy,
        approvedAt = approvedAt,
        errorMessage = errorMessage,
        likesCount = likesCount,
        commentsCount = commentsCount,
        sharesCount = sharesCount,
        savesCount = savesCount,
        viewsCount = viewsCount,
        reach = reach,
        impressions = impressions,
        engagementRate = engagementRate,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun SocialPost.toEntity(): SocialPostEntity = SocialPostEntity(
        id = id,
        accountId = accountId,
        platform = platform.id,
        content = content,
        mediaUrlsJson = json.encodeToString(mediaUrls),
        status = status.name,
        scheduledPublishTime = scheduledPublishTime,
        publishedTime = publishedTime,
        platformPostId = platformPostId,
        campaignId = campaignId,
        contentType = contentType.name,
        requiresApproval = requiresApproval,
        approvedBy = approvedBy,
        approvedAt = approvedAt,
        errorMessage = errorMessage,
        likesCount = likesCount,
        commentsCount = commentsCount,
        sharesCount = sharesCount,
        savesCount = savesCount,
        viewsCount = viewsCount,
        reach = reach,
        impressions = impressions,
        engagementRate = engagementRate,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun SocialCommentEntity.toDomain(): SocialComment = SocialComment(
        id = id,
        platformCommentId = platformCommentId,
        postId = postId,
        platform = SocialPlatform.fromId(platform),
        authorName = authorName,
        authorAvatarUrl = authorAvatarUrl,
        content = content,
        timestamp = timestamp,
        isAnswered = isAnswered,
        suggestedReply = suggestedReply,
        actualReply = actualReply,
        replyStatus = runCatching { CommentReplyStatus.valueOf(replyStatus) }.getOrDefault(CommentReplyStatus.NONE),
        replyTimestamp = replyTimestamp,
        sentiment = runCatching { Sentiment.valueOf(sentiment) }.getOrDefault(Sentiment.NEUTRAL),
        category = runCatching { InteractionCategory.valueOf(category) }.getOrDefault(InteractionCategory.GENERAL),
        priority = runCatching { InteractionPriority.valueOf(priority) }.getOrDefault(InteractionPriority.NORMAL)
    )

    private fun SocialComment.toEntity(): SocialCommentEntity = SocialCommentEntity(
        id = id,
        platformCommentId = platformCommentId,
        postId = postId,
        platform = platform.id,
        authorName = authorName,
        authorAvatarUrl = authorAvatarUrl,
        content = content,
        timestamp = timestamp,
        isAnswered = isAnswered,
        suggestedReply = suggestedReply,
        actualReply = actualReply,
        replyStatus = replyStatus.name,
        replyTimestamp = replyTimestamp,
        sentiment = sentiment.name,
        category = category.name,
        priority = priority.name
    )

    private fun SocialInteractionEntity.toDomain(): SocialInteraction = SocialInteraction(
        id = id,
        platform = SocialPlatform.fromId(platform),
        accountId = accountId,
        authorName = authorName,
        authorHandle = authorHandle,
        type = runCatching { InteractionType.valueOf(type) }.getOrDefault(InteractionType.MENTION),
        category = runCatching { InteractionCategory.valueOf(category) }.getOrDefault(InteractionCategory.GENERAL),
        priority = runCatching { InteractionPriority.valueOf(priority) }.getOrDefault(InteractionPriority.NORMAL),
        content = content,
        timestamp = timestamp,
        status = status,
        suggestedAction = suggestedAction
    )

    private fun SocialInteraction.toEntity(): SocialInteractionEntity = SocialInteractionEntity(
        id = id,
        platform = platform.id,
        accountId = accountId,
        authorName = authorName,
        authorHandle = authorHandle,
        type = type.name,
        category = category.name,
        priority = priority.name,
        content = content,
        timestamp = timestamp,
        status = status,
        suggestedAction = suggestedAction
    )

    private fun SocialAnalyticsSnapshotEntity.toDomain(): SocialAnalytics = SocialAnalytics(
        accountId = accountId,
        platform = SocialPlatform.fromId(platform),
        timestamp = timestamp,
        followersCount = followersCount,
        followingCount = followingCount,
        postsCount = postsCount,
        totalLikes = totalLikes,
        totalComments = totalComments,
        totalShares = totalShares,
        totalViews = totalViews,
        reach = reach,
        impressions = impressions,
        engagementRate = engagementRate,
        subscribersCount = subscribersCount,
        profileVisits = profileVisits,
        linkClicks = linkClicks,
        periodLabel = periodLabel
    )

    private fun SocialAnalytics.toEntity(): SocialAnalyticsSnapshotEntity = SocialAnalyticsSnapshotEntity(
        id = "${accountId}_${timestamp}",
        accountId = accountId,
        platform = platform.id,
        timestamp = timestamp,
        followersCount = followersCount ?: 0,
        followingCount = followingCount ?: 0,
        postsCount = postsCount ?: 0,
        totalLikes = totalLikes ?: 0,
        totalComments = totalComments ?: 0,
        totalShares = totalShares ?: 0,
        totalViews = totalViews ?: 0,
        reach = reach ?: 0,
        impressions = impressions ?: 0,
        engagementRate = engagementRate ?: 0f,
        subscribersCount = subscribersCount ?: 0,
        profileVisits = profileVisits ?: 0,
        linkClicks = linkClicks ?: 0,
        periodLabel = periodLabel
    )

    private fun SocialCampaignEntity.toDomain(): SocialCampaign = SocialCampaign(
        id = id,
        name = name,
        objective = objective,
        startDate = startDate,
        endDate = endDate,
        platforms = runCatching {
            json.decodeFromString<List<String>>(platformsJson).map { SocialPlatform.fromId(it) }
        }.getOrDefault(emptyList()),
        targetAudience = targetAudience,
        strategySummary = strategySummary,
        status = runCatching { CampaignStatus.valueOf(status) }.getOrDefault(CampaignStatus.PLANNING),
        budget = budget,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun SocialCampaign.toEntity(): SocialCampaignEntity = SocialCampaignEntity(
        id = id,
        name = name,
        objective = objective,
        startDate = startDate,
        endDate = endDate,
        platformsJson = json.encodeToString(platforms.map { it.id }),
        targetAudience = targetAudience,
        strategySummary = strategySummary,
        status = status.name,
        budget = budget,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun SocialAutomationRuleEntity.toDomain(): SocialAutomationRule = SocialAutomationRule(
        id = id,
        name = name,
        isEnabled = isEnabled,
        platform = platform?.let { SocialPlatform.fromId(it) },
        triggerType = triggerType,
        keywords = runCatching { json.decodeFromString<List<String>>(keywordsJson) }.getOrDefault(emptyList()),
        actionType = actionType,
        replyTemplate = replyTemplate,
        confidenceThreshold = confidenceThreshold,
        requireHumanApproval = requireHumanApproval,
        createdAt = createdAt
    )

    private fun SocialAutomationRule.toEntity(): SocialAutomationRuleEntity = SocialAutomationRuleEntity(
        id = id,
        name = name,
        isEnabled = isEnabled,
        platform = platform?.id,
        triggerType = triggerType,
        keywordsJson = json.encodeToString(keywords),
        actionType = actionType,
        replyTemplate = replyTemplate,
        confidenceThreshold = confidenceThreshold,
        requireHumanApproval = requireHumanApproval,
        createdAt = createdAt
    )

    private fun SocialAuditLogEntity.toDomain(): SocialAuditEntry = SocialAuditEntry(
        id = id,
        timestamp = timestamp,
        actor = actor,
        action = action,
        platform = SocialPlatform.fromId(platform),
        targetId = targetId,
        details = details,
        status = status
    )

    private fun SocialAuditEntry.toEntity(): SocialAuditLogEntity = SocialAuditLogEntity(
        id = id,
        timestamp = timestamp,
        actor = actor,
        action = action,
        platform = platform.id,
        targetId = targetId,
        details = details,
        status = status
    )
}
