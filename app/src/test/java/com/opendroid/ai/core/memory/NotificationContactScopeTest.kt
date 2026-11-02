package com.opendroid.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class NotificationContactScopeTest {

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
    fun `contact context holds only the messages from that app`() = runBlocking {
        insert(app = WHATSAPP, text = "Surgery at St Mary's 3pm")
        insert(app = SMS, text = "door code 4471")
        insert(app = TELEGRAM, text = "telegram hello")

        val context = intelligence.buildContactContext(TELEGRAM, "Mom")

        assertTrue(context, context.contains("telegram hello"))
        assertFalse(context, context.contains("Surgery at St Mary's 3pm"))
        assertFalse(context, context.contains("door code 4471"))
    }

    @Test
    fun `contact context treats a name known only to another app as a new contact`() = runBlocking {
        insert(app = WHATSAPP, text = "Surgery at St Mary's 3pm")

        assertEquals("No previous messages with Mom.", intelligence.buildContactContext(TELEGRAM, "Mom"))
    }

    @Test
    fun `replies to a same-name contact in another app do not use up this app's rate limit`() = runBlocking {
        insert(app = WHATSAPP, text = "hi", isAutoReplied = true)
        insert(app = WHATSAPP, text = "hi again", isAutoReplied = true)
        insert(app = TELEGRAM, text = "hello", isAutoReplied = true)

        assertEquals(2, dao.getAutoReplyCountForContact(WHATSAPP, "Mom", since = 0))
        assertEquals(1, dao.getAutoReplyCountForContact(TELEGRAM, "Mom", since = 0))
        assertEquals(0, dao.getAutoReplyCountForContact(SMS, "Mom", since = 0))
    }

    private suspend fun insert(app: String, text: String, isAutoReplied: Boolean = false) {
        dao.insertNotification(
            NotificationEntity(
                packageName = app,
                appName = app,
                title = "Mom",
                text = text,
                category = "MESSAGE",
                isAutoReplied = isAutoReplied,
                contactName = "Mom"
            )
        )
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val SMS = "com.google.android.apps.messaging"
        const val TELEGRAM = "org.telegram.messenger"
    }
}
