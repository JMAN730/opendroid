package com.opendroid.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opendroid.ai.data.db.dao.ChatSessionDao
import com.opendroid.ai.data.db.dao.ConversationDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.MemoryDao
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.ChatSessionEntity
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.db.entities.MemoryEntity
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.db.entities.TaskHistoryEntity

import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.dao.CrashLogDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.CrashLogEntity
import com.opendroid.ai.data.db.dao.HabitDao
import com.opendroid.ai.data.db.entities.HabitEventEntity
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import com.opendroid.ai.data.db.entities.SocialAccountEntity
import com.opendroid.ai.data.db.entities.SocialPostEntity
import com.opendroid.ai.data.db.entities.SocialCommentEntity
import com.opendroid.ai.data.db.entities.SocialInteractionEntity
import com.opendroid.ai.data.db.entities.SocialAnalyticsSnapshotEntity
import com.opendroid.ai.data.db.entities.SocialCampaignEntity
import com.opendroid.ai.data.db.entities.SocialAutomationRuleEntity
import com.opendroid.ai.data.db.entities.SocialAuditLogEntity
import com.opendroid.ai.data.db.dao.SocialAccountDao
import com.opendroid.ai.data.db.dao.SocialPostDao
import com.opendroid.ai.data.db.dao.SocialCommentDao
import com.opendroid.ai.data.db.dao.SocialInteractionDao
import com.opendroid.ai.data.db.dao.SocialAnalyticsDao
import com.opendroid.ai.data.db.dao.SocialCampaignDao
import com.opendroid.ai.data.db.dao.SocialAutomationRuleDao
import com.opendroid.ai.data.db.dao.SocialAuditLogDao
import androidx.room.TypeConverters

@Database(
    entities = [
        ConversationEntity::class,
        ChatSessionEntity::class,
        PlanEntity::class,
        MemoryEntity::class,
        TaskHistoryEntity::class,
        MacroEntity::class,
        UnknownActionEntity::class,
        NotificationEntity::class,
        ModelEntity::class,
        CrashLogEntity::class,
        HabitEventEntity::class,
        HabitRoutineEntity::class,
        SocialAccountEntity::class,
        SocialPostEntity::class,
        SocialCommentEntity::class,
        SocialInteractionEntity::class,
        SocialAnalyticsSnapshotEntity::class,
        SocialCampaignEntity::class,
        SocialAutomationRuleEntity::class,
        SocialAuditLogEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class OpenDroidDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun planDao(): PlanDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun macroDao(): MacroDao
    abstract fun unknownActionDao(): UnknownActionDao
    abstract fun notificationDao(): NotificationDao
    abstract fun modelDao(): ModelDao
    abstract fun crashLogDao(): CrashLogDao
    abstract fun habitDao(): HabitDao
    abstract fun socialAccountDao(): SocialAccountDao
    abstract fun socialPostDao(): SocialPostDao
    abstract fun socialCommentDao(): SocialCommentDao
    abstract fun socialInteractionDao(): SocialInteractionDao
    abstract fun socialAnalyticsDao(): SocialAnalyticsDao
    abstract fun socialCampaignDao(): SocialCampaignDao
    abstract fun socialAutomationRuleDao(): SocialAutomationRuleDao
    abstract fun socialAuditLogDao(): SocialAuditLogDao

    companion object {
        // Id of the single session that pre-existing conversation rows are
        // backfilled onto by MIGRATION_5_6. Not used post-migration - new
        // sessions get randomly generated ids (see ConversationRepository).
        private const val DEFAULT_SESSION_ID = "default_session"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE conversations ADD COLUMN contactPickerData TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        category TEXT NOT NULL DEFAULT 'OTHER',
                        isAutoReplied INTEGER NOT NULL DEFAULT 0,
                        autoReplyText TEXT,
                        contactName TEXT,
                        isRead INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notifications ADD COLUMN senderEmail TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS models (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        version TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        downloadUrl TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        status TEXT NOT NULL,
                        downloadProgress INTEGER NOT NULL,
                        lastUsed INTEGER NOT NULL,
                        installedAt INTEGER NOT NULL,
                        downloadedSize INTEGER NOT NULL DEFAULT 0,
                        downloadSpeed TEXT NOT NULL DEFAULT '',
                        etaString TEXT NOT NULL DEFAULT ''
                    )
                """)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. New table backing multiple chat histories.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isCurrent INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // 2. Seed exactly one session that existing chat history will be
                // attached to. INSERT OR IGNORE makes this safe to re-run.
                val now = System.currentTimeMillis()
                database.execSQL(
                    "INSERT OR IGNORE INTO chat_sessions (id, title, createdAt, updatedAt, isCurrent) " +
                        "VALUES ('$DEFAULT_SESSION_ID', 'Chat', $now, $now, 1)"
                )

                // 3. Add the session pointer to conversations. SQLite backfills the
                // DEFAULT value into every pre-existing row as part of this ALTER,
                // and the explicit UPDATE below makes that backfill unmistakable -
                // no existing chat history is lost by this migration.
                database.execSQL(
                    "ALTER TABLE conversations ADD COLUMN sessionId TEXT NOT NULL DEFAULT '$DEFAULT_SESSION_ID'"
                )
                database.execSQL(
                    "UPDATE conversations SET sessionId = '$DEFAULT_SESSION_ID'"
                )

                // 4. Matches the @Index Room expects on ConversationEntity.sessionId.
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_sessionId ON conversations(sessionId)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Crash log. Purely additive - nothing existing is touched, so an
                // upgrade can never lose user data here.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS crash_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        exceptionClass TEXT NOT NULL,
                        message TEXT,
                        threadName TEXT NOT NULL,
                        stackTrace TEXT NOT NULL,
                        appVersionName TEXT NOT NULL,
                        appVersionCode INTEGER NOT NULL,
                        androidRelease TEXT NOT NULL,
                        androidSdkInt INTEGER NOT NULL,
                        deviceManufacturer TEXT NOT NULL,
                        deviceModel TEXT NOT NULL
                    )
                """)

                // Matches the @Index on CrashLogEntity.timestamp - every read path
                // orders by it.
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_crash_logs_timestamp ON crash_logs(timestamp)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS habit_events (
                        id TEXT PRIMARY KEY NOT NULL,
                        eventType TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        actionName TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        dayOfWeek INTEGER NOT NULL,
                        hourOfDay INTEGER NOT NULL,
                        minuteOfHour INTEGER NOT NULL,
                        metadataJson TEXT NOT NULL DEFAULT '{}'
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_events_timestamp ON habit_events(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_events_dayOfWeek ON habit_events(dayOfWeek)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_events_hourOfDay ON habit_events(hourOfDay)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS habit_routines (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        triggerLabel TEXT NOT NULL,
                        triggerCron TEXT NOT NULL,
                        detectedActionsJson TEXT NOT NULL,
                        suggestedStepsJson TEXT NOT NULL,
                        repetitionCount INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        status TEXT NOT NULL,
                        suggestionMessage TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastDetectedAt INTEGER NOT NULL,
                        lastExecutedAt INTEGER,
                        macroId TEXT
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_routines_status ON habit_routines(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_habit_routines_lastDetectedAt ON habit_routines(lastDetectedAt)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_accounts (
                        id TEXT PRIMARY KEY NOT NULL,
                        platform TEXT NOT NULL,
                        username TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        avatarUrl TEXT,
                        status TEXT NOT NULL,
                        permissionsJson TEXT NOT NULL,
                        connectedAt INTEGER NOT NULL,
                        tokenExpiresAt INTEGER,
                        lastSyncAt INTEGER,
                        errorMessage TEXT
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_accounts_platform ON social_accounts(platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_accounts_status ON social_accounts(status)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_posts (
                        id TEXT PRIMARY KEY NOT NULL,
                        accountId TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        content TEXT NOT NULL,
                        mediaUrlsJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        scheduledPublishTime INTEGER,
                        publishedTime INTEGER,
                        platformPostId TEXT,
                        campaignId TEXT,
                        contentType TEXT NOT NULL,
                        requiresApproval INTEGER NOT NULL,
                        approvedBy TEXT,
                        approvedAt INTEGER,
                        errorMessage TEXT,
                        likesCount INTEGER NOT NULL,
                        commentsCount INTEGER NOT NULL,
                        sharesCount INTEGER NOT NULL,
                        savesCount INTEGER NOT NULL,
                        viewsCount INTEGER NOT NULL,
                        reach INTEGER NOT NULL,
                        impressions INTEGER NOT NULL,
                        engagementRate REAL NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_posts_accountId ON social_posts(accountId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_posts_platform ON social_posts(platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_posts_status ON social_posts(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_posts_scheduledPublishTime ON social_posts(scheduledPublishTime)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_posts_campaignId ON social_posts(campaignId)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_comments (
                        id TEXT PRIMARY KEY NOT NULL,
                        platformCommentId TEXT NOT NULL,
                        postId TEXT,
                        platform TEXT NOT NULL,
                        authorName TEXT NOT NULL,
                        authorAvatarUrl TEXT,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isAnswered INTEGER NOT NULL,
                        suggestedReply TEXT,
                        actualReply TEXT,
                        replyStatus TEXT NOT NULL,
                        replyTimestamp INTEGER,
                        sentiment TEXT NOT NULL,
                        category TEXT NOT NULL,
                        priority TEXT NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_comments_postId ON social_comments(postId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_comments_platform ON social_comments(platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_comments_replyStatus ON social_comments(replyStatus)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_comments_timestamp ON social_comments(timestamp)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_interactions (
                        id TEXT PRIMARY KEY NOT NULL,
                        platform TEXT NOT NULL,
                        accountId TEXT NOT NULL,
                        authorName TEXT NOT NULL,
                        authorHandle TEXT,
                        type TEXT NOT NULL,
                        category TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        suggestedAction TEXT
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_interactions_accountId ON social_interactions(accountId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_interactions_platform ON social_interactions(platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_interactions_status ON social_interactions(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_interactions_priority ON social_interactions(priority)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_interactions_timestamp ON social_interactions(timestamp)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_analytics_snapshots (
                        id TEXT PRIMARY KEY NOT NULL,
                        accountId TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        followersCount INTEGER NOT NULL,
                        followingCount INTEGER NOT NULL,
                        postsCount INTEGER NOT NULL,
                        totalLikes INTEGER NOT NULL,
                        totalComments INTEGER NOT NULL,
                        totalShares INTEGER NOT NULL,
                        totalViews INTEGER NOT NULL,
                        reach INTEGER NOT NULL,
                        impressions INTEGER NOT NULL,
                        engagementRate REAL NOT NULL,
                        subscribersCount INTEGER NOT NULL,
                        profileVisits INTEGER NOT NULL,
                        linkClicks INTEGER NOT NULL,
                        periodLabel TEXT NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_analytics_snapshots_accountId ON social_analytics_snapshots(accountId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_analytics_snapshots_platform ON social_analytics_snapshots(platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_analytics_snapshots_timestamp ON social_analytics_snapshots(timestamp)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_campaigns (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        objective TEXT NOT NULL,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        platformsJson TEXT NOT NULL,
                        targetAudience TEXT NOT NULL,
                        strategySummary TEXT NOT NULL,
                        status TEXT NOT NULL,
                        budget TEXT,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_campaigns_status ON social_campaigns(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_campaigns_startDate ON social_campaigns(startDate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_campaigns_endDate ON social_campaigns(endDate)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_automation_rules (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        platform TEXT,
                        triggerType TEXT NOT NULL,
                        keywordsJson TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        replyTemplate TEXT,
                        confidenceThreshold REAL NOT NULL,
                        requireHumanApproval INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_automation_rules_isEnabled ON social_automation_rules(isEnabled)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_automation_rules_triggerType ON social_automation_rules(triggerType)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS social_audit_logs (
                        id TEXT PRIMARY KEY NOT NULL,
                        timestamp INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        targetId TEXT,
                        details TEXT NOT NULL,
                        status TEXT NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_audit_logs_timestamp ON social_audit_logs(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_audit_logs_actor ON social_audit_logs(actor)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_audit_logs_platform ON social_audit_logs(platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_social_audit_logs_action ON social_audit_logs(action)")
            }
        }
    }
}
