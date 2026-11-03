package com.opendroid.ai.core.memory

import android.util.Log
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analyzes notification history to extract patterns and knowledge.
 * Feeds learned patterns into the memory system so the agent grows smarter over time.
 */
@Singleton
class NotificationIntelligence @Inject constructor(
    private val notificationDao: NotificationDao,
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private const val TAG = "NotifIntelligence"
        internal const val PATTERN_PREFIX = "notif_pattern_"
        private const val CONTACT_TIMES_KEY = "contact_times"
        private const val LEGACY_CONTACT_TIME_PREFIX = "contact_time_"
        private const val MAX_PATTERN_LINES = 10
        private val CONTACT_CATEGORIES = setOf("MESSAGE", "EMAIL")
        private const val ANALYSIS_INTERVAL_MS = 6 * 3600 * 1000L // Re-analyze every 6 hours
    }

    private var lastAnalysisTimestamp = 0L

    // Analysis reads history and then writes patterns; clearing must not interleave with that.
    private val patternLock = Mutex()

    /**
     * Run periodic pattern analysis on notification history.
     * Call this after receiving notifications or on app startup.
     */
    suspend fun analyzeIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) return
        lastAnalysisTimestamp = now
        cleanupOldNotifications()
        analyzePatterns()
    }

    /**
     * Full pattern analysis — extracts communication patterns from notification history.
     */
    suspend fun analyzePatterns() = patternLock.withLock {
        try {
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 3600 * 1000L)

            // 1. Most active contacts
            val topContacts = notificationDao.getMostActiveContacts(sevenDaysAgo, 5)
                .filter { !it.contactName.isNullOrBlank() }
            if (topContacts.isNotEmpty()) {
                val contactsSummary = topContacts
                    .joinToString(", ") { "${it.contactName} (${it.count} messages)" }
                storePattern("top_contacts", "Most contacted people this week: $contactsSummary")
            } else {
                deletePattern("top_contacts")
            }

            // 2. Most active apps
            val topApps = notificationDao.getNotificationCountByApp(sevenDaysAgo)
            if (topApps.isNotEmpty()) {
                val appsSummary = topApps
                    .take(5)
                    .joinToString(", ") { "${it.packageName.substringAfterLast('.')} (${it.count})" }
                storePattern("top_apps", "Most active notification apps this week: $appsSummary")
            } else {
                deletePattern("top_apps")
            }

            // 3. Communication time patterns
            val recentNotifs = notificationDao.getNotificationsSince(sevenDaysAgo, 500)
            analyzeTimePatterns(recentNotifs)

            // 4. Message volume stats
            val totalCount = notificationDao.getTotalCount()
            val autoRepliedCount = notificationDao.getAutoRepliedCount()
            storePattern("notification_stats",
                "Total notifications captured: $totalCount. Auto-replied: $autoRepliedCount.")

            Log.d(TAG, "Pattern analysis complete: ${topContacts.size} contacts, ${topApps.size} apps")
        } catch (e: Exception) {
            Log.e(TAG, "Pattern analysis failed: ${e.message}")
        }
    }

    private suspend fun analyzeTimePatterns(notifications: List<NotificationEntity>) {
        // Earlier versions kept one permanent row per sender title; contact times now share one row.
        deletePatternsStartingWith(LEGACY_CONTACT_TIME_PREFIX)

        if (notifications.isEmpty()) {
            deletePattern("peak_activity")
            deletePattern(CONTACT_TIMES_KEY)
            return
        }

        val hourCounts = IntArray(24)
        for (notif in notifications) {
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = notif.timestamp }
            hourCounts[calendar.get(java.util.Calendar.HOUR_OF_DAY)]++
        }

        val peakHour = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: 12
        storePattern("peak_activity", "User receives most messages in the ${periodOf(peakHour)} (peak hour: $peakHour:00)")

        // Per-contact time analysis for the most recent senders. Only message and email rows
        // carry a sender; any other app's title is a headline or a promo, not a person.
        val contactGroups = notifications
            .filter { it.category in CONTACT_CATEGORIES && !it.contactName.isNullOrBlank() }
            .groupBy { it.contactName!! }

        val contactTimes = contactGroups.entries.take(5).map { (contact, notifs) ->
            val contactHours = IntArray(24)
            for (n in notifs) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = n.timestamp }
                contactHours[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
            }
            val contactPeak = contactHours.indices.maxByOrNull { contactHours[it] } ?: 12
            "$contact (${periodOf(contactPeak)}, ${notifs.size} messages)"
        }
        if (contactTimes.isNotEmpty()) {
            storePattern(CONTACT_TIMES_KEY,
                "When contacts usually message this week: ${contactTimes.joinToString(", ")}")
        } else {
            deletePattern(CONTACT_TIMES_KEY)
        }
    }

    private fun periodOf(hour: Int): String = when (hour) {
        in 6..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..20 -> "evening"
        else -> "night"
    }

    /**
     * Get a summary of recent notifications for LLM context.
     */
    suspend fun getRecentNotificationSummary(limit: Int = 10): String {
        return try {
            val recent = notificationDao.getRecentNotifications(limit)
            if (recent.isEmpty()) return "No recent notifications."

            val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            recent.joinToString("\n") { notif ->
                val time = dateFormat.format(java.util.Date(notif.timestamp))
                val replyPreview = notif.autoReplyText?.let { text ->
                    if (text.length > 30) "${text.take(30)}..." else text
                }
                val replied = if (notif.isAutoReplied) " [Auto-replied: $replyPreview]" else ""
                "• ${notif.appName} — ${notif.contactName ?: notif.title}: ${notif.text.take(80)}$replied ($time)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build notification summary: ${e.message}")
            "Unable to retrieve notifications."
        }
    }

    /**
     * Get conversation context for a specific contact in one app from notification history.
     * The name is a free-form title, so another app's rows may belong to someone else entirely.
     */
    suspend fun buildContactContext(packageName: String, contactName: String): String {
        return try {
            val messages = notificationDao.getNotificationsForContactInApp(packageName, contactName)
            if (messages.isEmpty()) return "No previous messages with $contactName."

            messages.sortedBy { it.timestamp }.takeLast(10).joinToString("\n") { msg ->
                val prefix = if (msg.isAutoReplied) "You" else contactName
                val text = if (msg.isAutoReplied) (msg.autoReplyText ?: msg.text) else msg.text
                "[$prefix]: $text"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build contact context: ${e.message}")
            ""
        }
    }

    /**
     * Get all learned patterns as a formatted string for LLM context.
     */
    suspend fun getLearnedPatterns(): String {
        return try {
            val patterns = memoryRepository.getMemoriesByKeyPrefix(PATTERN_PREFIX)
            if (patterns.isEmpty()) return ""

            patterns.take(MAX_PATTERN_LINES).joinToString("\n") { "• ${it.value}" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get learned patterns: ${e.message}")
            ""
        }
    }

    private suspend fun storePattern(key: String, value: String) {
        try {
            memoryRepository.saveMemory(
                Memory(
                    key = "$PATTERN_PREFIX$key",
                    value = value,
                    type = MemoryType.SEMANTIC
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store pattern $key: ${e.message}")
        }
    }

    private suspend fun deletePattern(key: String) {
        try {
            memoryRepository.deleteMemory("$PATTERN_PREFIX$key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete pattern $key: ${e.message}")
        }
    }

    private suspend fun deletePatternsStartingWith(keyPrefix: String) {
        val prefix = "$PATTERN_PREFIX$keyPrefix"
        try {
            // LIKE treats '_' as a wildcard, so recheck the prefix literally.
            memoryRepository.getMemoriesByKeyPrefix(prefix)
                .filter { it.key.startsWith(prefix) }
                .forEach { memoryRepository.deleteMemory(it.key) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete patterns $keyPrefix*: ${e.message}")
        }
    }

    /**
     * Forget everything learned from notifications. Call it whenever the history it was
     * learned from is cleared or the listener loses access.
     */
    suspend fun clearLearnedPatterns() = patternLock.withLock {
        deletePatternsStartingWith("")
        // Relearn from whatever history remains on the next notification, not in 6 hours.
        lastAnalysisTimestamp = 0L
    }

    /**
     * Clear the captured notification history along with the patterns learned from it.
     * Non-cancellable so it cannot stop between deleting the rows and the patterns.
     */
    suspend fun clearHistory(): Int = withContext(NonCancellable) {
        patternLock.withLock {
            val deleted = notificationDao.clearAll()
            deletePatternsStartingWith("")
            deleted
        }
    }

    /**
     * Cleanup old notifications (older than 30 days).
     */
    suspend fun cleanupOldNotifications() {
        try {
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 3600 * 1000)
            notificationDao.deleteOldNotifications(thirtyDaysAgo)
            Log.d(TAG, "Cleaned up old notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup notifications: ${e.message}")
        }
    }
}
