package com.opendroid.ai.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.entities.CrashLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration tests for [OpenDroidDatabase], on the JVM via Robolectric.
 *
 * The dangerous failure mode these exist to catch: a migration that runs
 * without error but produces a schema that differs from what Room's generated
 * code expects. Room detects the mismatch when the database is opened and
 * throws - which in production is a crash on first launch after an upgrade,
 * for every existing user.
 *
 * Schema export (`room.schemaLocation`) only began at version 7, so there is
 * no exported 6.json for `MigrationTestHelper` to build a version-6 database
 * from. Instead the v6 schema is derived from the current Room-generated v7
 * schema: version 7 is exactly version 6 plus the `crash_logs` table and its
 * index, because MIGRATION_6_7 touches nothing else. Future migrations (7 ->
 * 8 onwards) should use `MigrationTestHelper` against the committed schema
 * JSONs instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenDroidDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(REFERENCE_DB)
        context.deleteDatabase(IDEMPOTENCY_DB)
    }

    @Test
    fun migration6To7_preservesDataAndPassesRoomSchemaValidation() {
        createVersion6Database()

        // No fallbackToDestructiveMigration here, unlike DatabaseModule: a
        // broken migration must fail this test, not silently wipe and rebuild.
        val db = Room.databaseBuilder(context, OpenDroidDatabase::class.java, TEST_DB)
            .addMigrations(
                OpenDroidDatabase.MIGRATION_1_2,
                OpenDroidDatabase.MIGRATION_2_3,
                OpenDroidDatabase.MIGRATION_3_4,
                OpenDroidDatabase.MIGRATION_4_5,
                OpenDroidDatabase.MIGRATION_5_6,
                OpenDroidDatabase.MIGRATION_6_7
            )
            .build()
        try {
            // Opening the database runs MIGRATION_6_7 and then Room's own
            // schema validation across every table. A schema mismatch throws
            // here.
            val support = db.openHelper.writableDatabase
            assertEquals(7, support.version)

            support.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_crash_logs_timestamp'"
            ).use { cursor ->
                assertTrue("index_crash_logs_timestamp missing after migration", cursor.moveToFirst())
            }

            // Rows that existed before the upgrade are still there.
            support.query("SELECT text, sessionId FROM conversations WHERE id = 'msg-1'").use { cursor ->
                assertTrue("pre-upgrade conversation row lost by migration", cursor.moveToFirst())
                assertEquals("hello", cursor.getString(0))
                assertEquals("default_session", cursor.getString(1))
            }

            // The migrated table round-trips through the real DAO.
            runBlocking {
                db.crashLogDao().insert(sampleCrash())
                val logs = db.crashLogDao().getAll()
                assertEquals(1, logs.size)
                assertEquals("boom", logs[0].message)
                assertEquals("java.lang.RuntimeException", logs[0].exceptionClass)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migration6To7_isSafeToRunAgainstAnExistingCrashLogTable() {
        // A partially-applied upgrade (process death mid-migration) can replay
        // a migration; every statement in MIGRATION_6_7 claims to be re-run
        // safe via IF NOT EXISTS. Hold it to that: run it against a database
        // where crash_logs already exists and holds data - it must neither
        // throw nor clobber the table.
        val db = Room.databaseBuilder(context, OpenDroidDatabase::class.java, IDEMPOTENCY_DB).build()
        try {
            val support = db.openHelper.writableDatabase
            runBlocking { db.crashLogDao().insert(sampleCrash()) }

            OpenDroidDatabase.MIGRATION_6_7.migrate(support)

            runBlocking {
                val logs = db.crashLogDao().getAll()
                assertEquals(1, logs.size)
                assertEquals("boom", logs[0].message)
            }
        } finally {
            db.close()
        }
    }

    /**
     * Builds a populated database exactly as a v6 install would have left it:
     * the Room-generated v7 schema minus `crash_logs` and its index (the only
     * things MIGRATION_6_7 adds), stamped `user_version = 6`.
     */
    private fun createVersion6Database() {
        val version6Ddl = mutableListOf<String>()
        val reference = Room.databaseBuilder(context, OpenDroidDatabase::class.java, REFERENCE_DB).build()
        try {
            reference.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' " +
                    "AND name != 'room_master_table' AND tbl_name != 'crash_logs'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    version6Ddl += cursor.getString(0)
                }
            }
        } finally {
            reference.close()
        }
        assertTrue("failed to derive any v6 DDL from the reference database", version6Ddl.isNotEmpty())

        context.deleteDatabase(TEST_DB)
        val file = context.getDatabasePath(TEST_DB)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v6 ->
            version6Ddl.forEach(v6::execSQL)
            v6.execSQL(
                "INSERT INTO chat_sessions (id, title, createdAt, updatedAt, isCurrent) " +
                    "VALUES ('default_session', 'Chat', 100, 100, 1)"
            )
            v6.execSQL(
                "INSERT INTO conversations (id, text, sender, timestamp, modelBadge, contactPickerData, sessionId) " +
                    "VALUES ('msg-1', 'hello', 'USER', 123, NULL, NULL, 'default_session')"
            )
            v6.version = 6
        }
    }

    private fun sampleCrash() = CrashLogEntity(
        timestamp = 1L,
        exceptionClass = "java.lang.RuntimeException",
        message = "boom",
        threadName = "main",
        stackTrace = "java.lang.RuntimeException: boom\n\tat com.opendroid.ai.Somewhere.kt:1",
        appVersionName = "1.0.2",
        appVersionCode = 3L,
        androidRelease = "15",
        androidSdkInt = 35,
        deviceManufacturer = "Robolectric",
        deviceModel = "JVM"
    )

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val REFERENCE_DB = "migration-reference.db"
        const val IDEMPOTENCY_DB = "migration-idempotency.db"
    }
}
