package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity): Long

    // Filters run in SQL, before the limit, so an older row still appears under its filter.
    @Query("""
        SELECT * FROM notifications
        WHERE (:category IS NULL OR category = :category)
          AND (:autoRepliedOnly = 0 OR isAutoReplied = 1)
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getNotificationHistoryFlow(
        category: String?,
        autoRepliedOnly: Boolean,
        limit: Int
    ): Flow<List<NotificationEntity>>

    @Query("""
        SELECT COUNT(*) AS total,
               COUNT(CASE WHEN category = 'MESSAGE' THEN 1 END) AS messages,
               COUNT(CASE WHEN isAutoReplied = 1 THEN 1 END) AS replied
        FROM notifications
    """)
    fun getNotificationHistoryStatsFlow(): Flow<NotificationHistoryStats>

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentNotifications(limit: Int = 50): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getNotificationsByApp(packageName: String, limit: Int = 50): List<NotificationEntity>

    // contactName is a free-form notification title, so it only names a contact within one app.
    @Query("""
        SELECT * FROM notifications
        WHERE packageName = :packageName AND contactName = :contactName
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getNotificationsForContactInApp(
        packageName: String,
        contactName: String,
        limit: Int = 20
    ): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE timestamp > :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getNotificationsSince(since: Long, limit: Int = 100): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE category = 'MESSAGE' AND timestamp > :since ORDER BY timestamp DESC")
    suspend fun getMessageNotificationsSince(since: Long): List<NotificationEntity>

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("UPDATE notifications SET isAutoReplied = 1, autoReplyText = :replyText WHERE id = :id")
    suspend fun markAsAutoReplied(id: Long, replyText: String)

    @Query("SELECT COUNT(*) FROM notifications WHERE packageName = :packageName AND contactName = :contactName AND isAutoReplied = 1 AND timestamp > :since")
    suspend fun getAutoReplyCountForContact(packageName: String, contactName: String, since: Long): Int

    @Query("""
        SELECT packageName, COUNT(*) as count 
        FROM notifications 
        WHERE timestamp > :since 
        GROUP BY packageName 
        ORDER BY count DESC
    """)
    suspend fun getNotificationCountByApp(since: Long): List<AppNotificationCount>

    // Only message and email rows name a sender; other apps' titles are headlines or promos.
    @Query("""
        SELECT contactName, COUNT(*) as count
        FROM notifications
        WHERE contactName IS NOT NULL AND category IN ('MESSAGE', 'EMAIL') AND timestamp > :since
        GROUP BY contactName 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getMostActiveContacts(since: Long, limit: Int = 10): List<ContactNotificationCount>

    @Query("DELETE FROM notifications WHERE timestamp < :olderThan")
    suspend fun deleteOldNotifications(olderThan: Long)

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE isAutoReplied = 1")
    suspend fun getAutoRepliedCount(): Int

    @Delete
    suspend fun deleteNotification(notification: NotificationEntity)

    @Query("DELETE FROM notifications")
    suspend fun clearAll(): Int

    @Query("DELETE FROM notifications WHERE packageName = :packageName")
    suspend fun deleteNotificationsByApp(packageName: String): Int

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteNotificationById(id: Long): Int
}

data class NotificationHistoryStats(
    val total: Int,
    val messages: Int,
    val replied: Int
)

data class AppNotificationCount(
    val packageName: String,
    val count: Int
)

data class ContactNotificationCount(
    val contactName: String?,
    val count: Int
)
