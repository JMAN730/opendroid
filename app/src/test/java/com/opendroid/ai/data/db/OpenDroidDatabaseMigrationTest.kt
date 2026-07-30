package com.opendroid.ai.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Avoids [com.opendroid.ai.OpenDroidApp] so Robolectric does not need the
 * Android Keystore used by SecurePrefs during Application.onCreate.
 */
class MigrationTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class OpenDroidDatabaseMigrationTest {

    @Test
    fun `migration 6 to 7 preserves existing data and creates the exact crash log schema`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)

        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(V6SchemaCallback())
                .build()
        ).writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`)
                VALUES ('preferred-model', 'claude-sonnet', 'PREFERENCE', 1234, -1, 'FACT')
                """.trimIndent()
            )
        }

        val roomDb = Room.databaseBuilder(context, OpenDroidDatabase::class.java, TEST_DATABASE)
            .addMigrations(OpenDroidDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        try {
            roomDb.openHelper.readableDatabase.use { migrated ->
                migrated.query(
                    "SELECT `value`, `timestamp` FROM memories WHERE `key` = 'preferred-model'"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("claude-sonnet", cursor.getString(0))
                    assertEquals(1234L, cursor.getLong(1))
                }

                val actualColumns = migrated.query("PRAGMA table_info(`crash_logs`)").use { cursor ->
                    buildList {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        val typeIndex = cursor.getColumnIndexOrThrow("type")
                        val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                        val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
                        while (cursor.moveToNext()) {
                            add(
                                Column(
                                    name = cursor.getString(nameIndex),
                                    type = cursor.getString(typeIndex),
                                    notNull = cursor.getInt(notNullIndex) == 1,
                                    primaryKeyPosition = cursor.getInt(primaryKeyIndex)
                                )
                            )
                        }
                    }
                }
                assertEquals(EXPECTED_CRASH_LOG_COLUMNS, actualColumns)

                val crashLogIndices = migrated.query("PRAGMA index_list(`crash_logs`)").use { cursor ->
                    buildList {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }
                assertEquals(listOf("index_crash_logs_timestamp"), crashLogIndices)

                val indexedColumns = migrated.query(
                    "PRAGMA index_info(`index_crash_logs_timestamp`)"
                ).use { cursor ->
                    buildList {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }
                assertEquals(listOf("timestamp"), indexedColumns)
            }
        } finally {
            roomDb.close()
        }
    }

    private class V6SchemaCallback : SupportSQLiteOpenHelper.Callback(6) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            V6_CREATE_STATEMENTS.forEach(db::execSQL)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$V6_IDENTITY_HASH')"
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int
    )

    private companion object {
        const val TEST_DATABASE = "migration-6-to-7"
        const val V6_IDENTITY_HASH = "4e53326f14505d186783b4c7db2236e6"

        val V6_CREATE_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `sender` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `modelBadge` TEXT, `contactPickerData` TEXT, `sessionId` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_conversations_sessionId` ON `conversations` (`sessionId`)",
            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isCurrent` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `plans` (`planId` TEXT NOT NULL, `goal` TEXT NOT NULL, `estimatedDuration` TEXT NOT NULL, `estimatedSteps` INTEGER NOT NULL, `stepsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`planId`))",
            "CREATE TABLE IF NOT EXISTS `memories` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `ttlHours` INTEGER NOT NULL, `category` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `task_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stepId` TEXT NOT NULL, `planId` TEXT NOT NULL, `description` TEXT NOT NULL, `actionType` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `success` INTEGER NOT NULL, `resultData` TEXT, `errorMessage` TEXT, `timestamp` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `macros` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `trigger` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isSystem` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `unknown_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attemptedAction` TEXT NOT NULL, `goal` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `fixStatus` TEXT NOT NULL, `wasAutoFixed` INTEGER NOT NULL, `fixedWith` TEXT)",
            "CREATE TABLE IF NOT EXISTS `notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `category` TEXT NOT NULL, `isAutoReplied` INTEGER NOT NULL, `autoReplyText` TEXT, `contactName` TEXT, `senderEmail` TEXT, `isRead` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `models` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `size` INTEGER NOT NULL, `downloadUrl` TEXT NOT NULL, `localPath` TEXT NOT NULL, `status` TEXT NOT NULL, `downloadProgress` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL, `installedAt` INTEGER NOT NULL, `downloadedSize` INTEGER NOT NULL, `downloadSpeed` TEXT NOT NULL, `etaString` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )

        val EXPECTED_CRASH_LOG_COLUMNS = listOf(
            Column("id", "INTEGER", notNull = true, primaryKeyPosition = 1),
            Column("timestamp", "INTEGER", notNull = true, primaryKeyPosition = 0),
            Column("exceptionClass", "TEXT", notNull = true, primaryKeyPosition = 0),
            Column("message", "TEXT", notNull = false, primaryKeyPosition = 0),
            Column("threadName", "TEXT", notNull = true, primaryKeyPosition = 0),
            Column("stackTrace", "TEXT", notNull = true, primaryKeyPosition = 0),
            Column("appVersionName", "TEXT", notNull = true, primaryKeyPosition = 0),
            Column("appVersionCode", "INTEGER", notNull = true, primaryKeyPosition = 0),
            Column("androidRelease", "TEXT", notNull = true, primaryKeyPosition = 0),
            Column("androidSdkInt", "INTEGER", notNull = true, primaryKeyPosition = 0),
            Column("deviceManufacturer", "TEXT", notNull = true, primaryKeyPosition = 0),
            Column("deviceModel", "TEXT", notNull = true, primaryKeyPosition = 0)
        )
    }
}
