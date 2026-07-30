package com.opendroid.ai.data.db

import android.app.Application
import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
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

    // Consumes the exported schemas in app/schemas (wired into test assets in
    // app/build.gradle) and validates migrated databases against them.
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenDroidDatabase::class.java
    )

    // Room 2.7+ compares the open helper's configured name against the fully
    // resolved database path, so the helper must be addressed by absolute path.
    private val databasePath: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DATABASE).absolutePath

    @Test
    fun `migration 6 to 7 preserves existing data and creates the exact crash log schema`() {
        // Creates a real version-6 database from the exported 6.json schema.
        helper.createDatabase(databasePath, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`)
                VALUES ('preferred-model', 'claude-sonnet', 'PREFERENCE', 1234, -1, 'FACT')
                """.trimIndent()
            )
        }

        // Validates the migrated schema (crash_logs columns and index included)
        // against the exported 7.json, table by table.
        val migrated = helper.runMigrationsAndValidate(
            databasePath, 7, true, OpenDroidDatabase.MIGRATION_6_7
        )

        migrated.query(
            "SELECT `value`, `timestamp` FROM memories WHERE `key` = 'preferred-model'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("claude-sonnet", cursor.getString(0))
            assertEquals(1234L, cursor.getLong(1))
        }
    }

    @Test
    fun `full migration chain from 1 to 7 produces the current schema and keeps data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)

        // Version 1 predates schema export (app/schemas only contains 6.json and
        // 7.json), so the v1 database is created by hand: every table that no
        // MIGRATION_* creates must already have existed at version 1.
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databasePath)
                .callback(V1SchemaCallback())
                .build()
        ).writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (`id`, `text`, `sender`, `timestamp`, `modelBadge`)
                VALUES ('msg-1', 'hello', 'USER', 42, NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO memories (`key`, `value`, `type`, `timestamp`, `ttlHours`, `category`)
                VALUES ('preferred-model', 'claude-sonnet', 'PREFERENCE', 1234, -1, 'FACT')
                """.trimIndent()
            )
        }

        // Runs every migration in sequence and validates the final schema
        // against the exported 7.json.
        val migrated = helper.runMigrationsAndValidate(
            databasePath, 7, true,
            OpenDroidDatabase.MIGRATION_1_2,
            OpenDroidDatabase.MIGRATION_2_3,
            OpenDroidDatabase.MIGRATION_3_4,
            OpenDroidDatabase.MIGRATION_4_5,
            OpenDroidDatabase.MIGRATION_5_6,
            OpenDroidDatabase.MIGRATION_6_7
        )

        // Pre-existing chat history survives and is attached to the session
        // backfilled by MIGRATION_5_6.
        migrated.query(
            "SELECT `text`, `sessionId` FROM conversations WHERE `id` = 'msg-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertEquals("default_session", cursor.getString(1))
        }
        migrated.query(
            "SELECT `value` FROM memories WHERE `key` = 'preferred-model'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("claude-sonnet", cursor.getString(0))
        }
    }

    private class V1SchemaCallback : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            V1_CREATE_STATEMENTS.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TEST_DATABASE = "migration-test"

        // The version-1 schema: the current schema minus everything the
        // migrations add (contactPickerData 1->2, notifications 2->3 with
        // senderEmail 3->4, models 4->5, chat_sessions + sessionId 5->6,
        // crash_logs 6->7).
        val V1_CREATE_STATEMENTS = listOf(
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `sender` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `modelBadge` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `plans` (`planId` TEXT NOT NULL, `goal` TEXT NOT NULL, `estimatedDuration` TEXT NOT NULL, `estimatedSteps` INTEGER NOT NULL, `stepsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`planId`))",
            "CREATE TABLE IF NOT EXISTS `memories` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `ttlHours` INTEGER NOT NULL, `category` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `task_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stepId` TEXT NOT NULL, `planId` TEXT NOT NULL, `description` TEXT NOT NULL, `actionType` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `success` INTEGER NOT NULL, `resultData` TEXT, `errorMessage` TEXT, `timestamp` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `macros` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `trigger` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `isSystem` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `unknown_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attemptedAction` TEXT NOT NULL, `goal` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `fixStatus` TEXT NOT NULL, `wasAutoFixed` INTEGER NOT NULL, `fixedWith` TEXT)"
        )
    }
}
