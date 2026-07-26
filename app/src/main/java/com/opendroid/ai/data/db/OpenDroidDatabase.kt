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
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.db.entities.ModelEntity
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
        ModelEntity::class
    ],
    version = 6,
    exportSchema = false
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
    }
}
