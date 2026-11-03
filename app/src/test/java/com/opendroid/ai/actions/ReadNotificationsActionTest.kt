package com.opendroid.ai.actions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.memory.MemoryManager
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
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

/** Issue #177: a READ_NOTIFICATIONS result can be forwarded, so it must not carry the learned profile. */
@RunWith(RobolectricTestRunner::class)
class ReadNotificationsActionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profile = "Most contacted people this week: Mom (41 messages)"

    private lateinit var database: OpenDroidDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val memoryRepository = MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao())
        val intelligence = NotificationIntelligence(database.notificationDao(), memoryRepository)
        memoryRepository.saveMemory(
            Memory(key = "notif_pattern_top_contacts", value = profile, type = MemoryType.SEMANTIC)
        )
        database.notificationDao().insertNotification(
            NotificationEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Sam",
                text = "Running ten minutes late",
                contactName = "Sam"
            )
        )
        // The profile has to be there to read, or the assertions below would pass for the wrong reason.
        assertTrue(intelligence.getLearnedPatterns().contains(profile))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the result holds the notification and not the learned profile`() = runBlocking {
        val data = read().data.orEmpty()

        assertTrue(data, data.contains("Running ten minutes late"))
        assertFalse(data, data.contains("What I've learned"))
        assertFalse(data, data.contains("Most contacted people"))
    }

    @Test
    fun `a forwarded step reference expands without the learned profile`() = runBlocking {
        val executor = ActionSequenceExecutor(
            executeAction = { _, _, _ -> ActionResult.Failure("unused") },
            hasAction = { true }
        )

        val resolved = executor.resolveParameters(
            params = mapOf("contact" to "Alex", "message" to "$" + "$" + "s1"),
            completedResults = mapOf("s1" to read().data.orEmpty())
        )

        assertTrue(resolved.getValue("message").contains("Running ten minutes late"))
        assertFalse(resolved.getValue("message").contains("Most contacted people"))
    }

    @Test
    fun `the action holds nothing that could read the learned profile`() {
        val forbidden = setOf(MemoryManager::class.java, NotificationIntelligence::class.java, MemoryRepository::class.java)
        val held = listOf(ReadNotificationsAction::class.java, NotificationActions::class.java)
            .flatMap { it.declaredConstructors.toList() }
            .flatMap { it.parameterTypes.toList() }

        assertEquals(emptyList<Class<*>>(), held.filter { it in forbidden })
    }

    private suspend fun read(): ActionResult =
        ReadNotificationsAction(database.notificationDao())
            .execute(mapOf("app" to "whatsapp", "count" to "1"), context)
}
