package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SocialAccountDao {
    @Query("SELECT * FROM social_accounts ORDER BY connectedAt DESC")
    fun getAllAccountsFlow(): Flow<List<SocialAccountEntity>>

    @Query("SELECT * FROM social_accounts ORDER BY connectedAt DESC")
    suspend fun getAllAccounts(): List<SocialAccountEntity>

    @Query("SELECT * FROM social_accounts WHERE status = 'CONNECTED'")
    fun getConnectedAccountsFlow(): Flow<List<SocialAccountEntity>>

    @Query("SELECT * FROM social_accounts WHERE status = 'CONNECTED'")
    suspend fun getConnectedAccounts(): List<SocialAccountEntity>

    @Query("SELECT * FROM social_accounts WHERE id = :id")
    suspend fun getAccountById(id: String): SocialAccountEntity?

    @Query("SELECT * FROM social_accounts WHERE platform = :platform LIMIT 1")
    suspend fun getAccountByPlatform(platform: String): SocialAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(account: SocialAccountEntity)

    @Query("UPDATE social_accounts SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String? = null)

    @Query("UPDATE social_accounts SET lastSyncAt = :timestamp WHERE id = :id")
    suspend fun updateLastSync(id: String, timestamp: Long)

    @Query("DELETE FROM social_accounts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SocialPostDao {
    @Query("SELECT * FROM social_posts ORDER BY createdAt DESC")
    fun getAllPostsFlow(): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE status = :status ORDER BY createdAt DESC")
    fun getPostsByStatusFlow(status: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE status = :status ORDER BY createdAt DESC")
    suspend fun getPostsByStatus(status: String): List<SocialPostEntity>

    @Query("SELECT * FROM social_posts WHERE status = 'SCHEDULED' AND scheduledPublishTime <= :currentTime ORDER BY scheduledPublishTime ASC")
    suspend fun getDueScheduledPosts(currentTime: Long): List<SocialPostEntity>

    @Query("SELECT * FROM social_posts WHERE platform = :platform ORDER BY createdAt DESC")
    fun getPostsByPlatformFlow(platform: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE campaignId = :campaignId ORDER BY createdAt DESC")
    fun getPostsByCampaignFlow(campaignId: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE id = :id")
    suspend fun getPostById(id: String): SocialPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(post: SocialPostEntity)

    @Query("UPDATE social_posts SET status = :status, publishedTime = :publishedTime, platformPostId = :platformPostId, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePublishResult(id: String, status: String, publishedTime: Long?, platformPostId: String?, errorMessage: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE social_posts SET status = :status, approvedBy = :approvedBy, approvedAt = :approvedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun approvePost(id: String, status: String, approvedBy: String, approvedAt: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE social_posts SET scheduledPublishTime = :newTime, status = 'SCHEDULED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun reschedulePost(id: String, newTime: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE social_posts SET status = 'CANCELLED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun cancelPost(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM social_posts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM social_posts WHERE status = 'PUBLISHED' ORDER BY (likesCount + commentsCount * 2 + sharesCount * 3) DESC LIMIT :limit")
    suspend fun getTopPerformingPosts(limit: Int): List<SocialPostEntity>
}

@Dao
interface SocialCommentDao {
    @Query("SELECT * FROM social_comments ORDER BY timestamp DESC")
    fun getAllCommentsFlow(): Flow<List<SocialCommentEntity>>

    @Query("SELECT * FROM social_comments WHERE isAnswered = 0 AND replyStatus != 'IGNORED' ORDER BY timestamp DESC")
    fun getUnansweredCommentsFlow(): Flow<List<SocialCommentEntity>>

    @Query("SELECT * FROM social_comments WHERE isAnswered = 0 AND replyStatus != 'IGNORED' ORDER BY timestamp DESC")
    suspend fun getUnansweredComments(): List<SocialCommentEntity>

    @Query("SELECT * FROM social_comments WHERE postId = :postId ORDER BY timestamp DESC")
    fun getCommentsForPostFlow(postId: String): Flow<List<SocialCommentEntity>>

    @Query("SELECT * FROM social_comments WHERE id = :id")
    suspend fun getCommentById(id: String): SocialCommentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(comment: SocialCommentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(comments: List<SocialCommentEntity>)

    @Query("UPDATE social_comments SET suggestedReply = :reply, replyStatus = 'SUGGESTED' WHERE id = :id")
    suspend fun updateSuggestedReply(id: String, reply: String)

    @Query("UPDATE social_comments SET actualReply = :reply, replyStatus = 'REPLIED', isAnswered = 1, replyTimestamp = :timestamp WHERE id = :id")
    suspend fun markReplied(id: String, reply: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE social_comments SET replyStatus = 'IGNORED' WHERE id = :id")
    suspend fun ignoreComment(id: String)

    @Query("DELETE FROM social_comments WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SocialInteractionDao {
    @Query("SELECT * FROM social_interactions ORDER BY timestamp DESC")
    fun getAllInteractionsFlow(): Flow<List<SocialInteractionEntity>>

    @Query("SELECT * FROM social_interactions WHERE status = 'UNREAD' ORDER BY timestamp DESC")
    fun getUnreadInteractionsFlow(): Flow<List<SocialInteractionEntity>>

    @Query("SELECT * FROM social_interactions WHERE category = :category ORDER BY timestamp DESC")
    fun getInteractionsByCategoryFlow(category: String): Flow<List<SocialInteractionEntity>>

    @Query("SELECT * FROM social_interactions WHERE id = :id")
    suspend fun getInteractionById(id: String): SocialInteractionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(interaction: SocialInteractionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(interactions: List<SocialInteractionEntity>)

    @Query("UPDATE social_interactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM social_interactions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SocialAnalyticsDao {
    @Query("SELECT * FROM social_analytics_snapshots ORDER BY timestamp DESC")
    fun getAllSnapshotsFlow(): Flow<List<SocialAnalyticsSnapshotEntity>>

    @Query("SELECT * FROM social_analytics_snapshots WHERE accountId = :accountId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshotForAccount(accountId: String): SocialAnalyticsSnapshotEntity?

    @Query("SELECT * FROM social_analytics_snapshots WHERE platform = :platform ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSnapshotForPlatform(platform: String): SocialAnalyticsSnapshotEntity?

    @Query("SELECT * FROM social_analytics_snapshots WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getSnapshotsSince(sinceTimestamp: Long): List<SocialAnalyticsSnapshotEntity>

    @Query("SELECT * FROM social_analytics_snapshots WHERE timestamp BETWEEN :fromTimestamp AND :toTimestamp ORDER BY timestamp ASC")
    suspend fun getSnapshotsBetween(fromTimestamp: Long, toTimestamp: Long): List<SocialAnalyticsSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: SocialAnalyticsSnapshotEntity)
}

@Dao
interface SocialCampaignDao {
    @Query("SELECT * FROM social_campaigns ORDER BY createdAt DESC")
    fun getAllCampaignsFlow(): Flow<List<SocialCampaignEntity>>

    @Query("SELECT * FROM social_campaigns WHERE status = 'ACTIVE' ORDER BY createdAt DESC")
    fun getActiveCampaignsFlow(): Flow<List<SocialCampaignEntity>>

    @Query("SELECT * FROM social_campaigns WHERE id = :id")
    suspend fun getCampaignById(id: String): SocialCampaignEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(campaign: SocialCampaignEntity)

    @Query("UPDATE social_campaigns SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM social_campaigns WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SocialAutomationRuleDao {
    @Query("SELECT * FROM social_automation_rules ORDER BY createdAt DESC")
    fun getAllRulesFlow(): Flow<List<SocialAutomationRuleEntity>>

    @Query("SELECT * FROM social_automation_rules WHERE isEnabled = 1 ORDER BY createdAt DESC")
    suspend fun getActiveRules(): List<SocialAutomationRuleEntity>

    @Query("SELECT * FROM social_automation_rules WHERE id = :id")
    suspend fun getRuleById(id: String): SocialAutomationRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(rule: SocialAutomationRuleEntity)

    @Query("UPDATE social_automation_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM social_automation_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SocialAuditLogDao {
    @Query("SELECT * FROM social_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentAuditLogsFlow(limit: Int = 100): Flow<List<SocialAuditLogEntity>>

    @Query("SELECT * FROM social_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentAuditLogs(limit: Int = 100): List<SocialAuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SocialAuditLogEntity)

    @Query("DELETE FROM social_audit_logs WHERE timestamp < :olderThanTimestamp")
    suspend fun purgeOlderThan(olderThanTimestamp: Long)
}
