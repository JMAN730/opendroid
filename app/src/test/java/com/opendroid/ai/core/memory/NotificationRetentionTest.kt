package com.opendroid.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.dao.NotificationHistoryStats
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class NotificationRetentionTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var dao: NotificationDao
    private lateinit var intelligence: NotificationIntelligence

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.notificationDao()
        intelligence = NotificationIntelligence(
            dao,
            MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `scheduled analysis prunes only rows past the 30 day retention`() = runBlocking {
        insert(title = "expired", ageDays = 40)
        insert(title = "kept", ageDays = 5)

        // The same entry point OpenDroidNotificationListener calls after every insert.
        intelligence.analyzeIfNeeded()

        assertEquals(listOf("kept"), dao.getRecentNotifications(10).map { it.title })
    }

    @Test
    fun `scheduled analysis keeps every retained row of a conversation`() = runBlocking {
        // Auto-reply history reads one row per message, so pruning must not collapse a contact.
        insert(title = "first", ageDays = 5, contactName = "Sam")
        insert(title = "second", ageDays = 4, contactName = "Sam")

        intelligence.analyzeIfNeeded()

        assertEquals(
            listOf("second", "first"),
            dao.getNotificationsForContactInApp("com.example.app", "Sam").map { it.title }
        )
    }

    @Test
    fun `history returns only the newest rows up to the limit`() = runBlocking {
        insert(title = "oldest", ageDays = 3)
        insert(title = "middle", ageDays = 2)
        insert(title = "newest", ageDays = 1)

        val history = dao.getNotificationHistoryFlow(null, false, 2).first()

        assertEquals(listOf("newest", "middle"), history.map { it.title })
    }

    @Test
    fun `history filters apply before the limit`() = runBlocking {
        // A filter applied to the capped list in composition would show neither older row.
        insert(title = "old message", ageDays = 3, category = "MESSAGE")
        insert(title = "old reply", ageDays = 3, category = "EMAIL", isAutoReplied = true)
        insert(title = "new other", ageDays = 2)
        insert(title = "newer other", ageDays = 1)

        assertEquals(
            listOf("old message"),
            dao.getNotificationHistoryFlow("MESSAGE", false, 1).first().map { it.title }
        )
        assertEquals(
            listOf("old reply"),
            dao.getNotificationHistoryFlow(null, true, 1).first().map { it.title }
        )
    }

    @Test
    fun `history stats count the whole table rather than the capped list`() = runBlocking {
        insert(title = "message", ageDays = 3, category = "MESSAGE")
        insert(title = "reply", ageDays = 2, category = "MESSAGE", isAutoReplied = true)
        insert(title = "other", ageDays = 1)

        assertEquals(
            NotificationHistoryStats(total = 3, messages = 2, replied = 1),
            dao.getNotificationHistoryStatsFlow().first()
        )
    }

    @Test
    fun `history stats are zero for an empty table`() = runBlocking {
        assertEquals(
            NotificationHistoryStats(total = 0, messages = 0, replied = 0),
            dao.getNotificationHistoryStatsFlow().first()
        )
    }

    private suspend fun insert(
        title: String,
        ageDays: Long,
        category: String = "OTHER",
        isAutoReplied: Boolean = false,
        contactName: String? = null
    ) {
        dao.insertNotification(
            NotificationEntity(
                packageName = "com.example.app",
                appName = "Example",
                title = title,
                text = "body",
                timestamp = System.currentTimeMillis() - ageDays * DAY_MS,
                category = category,
                isAutoReplied = isAutoReplied,
                contactName = contactName
            )
        )
    }

    private companion object {
        const val DAY_MS = 24 * 3600 * 1000L
    }
}
