package com.opendroid.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class LegacyPoisonedMemoryCleanupTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var cleanup: LegacyPoisonedMemoryCleanup

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao())
        cleanup = LegacyPoisonedMemoryCleanup(
            repository,
            context.getSharedPreferences("legacy_cleanup_test", Context.MODE_PRIVATE).also {
                it.edit().clear().commit()
            }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `user facts that mention a poison phrase survive the cleanup`() = runBlocking {
        save("pkg_node_explicit_car_1", "Car is not registered until March")
        save("user_note_work_email", "My email is whitelisted at work")
        save("contact_pref_mom", "Mom has an unknown action figure collection||+15550100")

        cleanup.cleanPoisonedMemories()

        assertEquals("Car is not registered until March", value("pkg_node_explicit_car_1"))
        assertEquals("My email is whitelisted at work", value("user_note_work_email"))
        assertEquals("Mom has an unknown action figure collection||+15550100", value("contact_pref_mom"))
    }

    @Test
    fun `legacy invalid action rows are removed`() = runBlocking {
        save(
            "invalid_action_FLY_DRONE",
            "The action 'FLY_DRONE' is not registered in OpenDroid. Do not use this action name."
        )

        cleanup.cleanPoisonedMemories()

        assertNull(value("invalid_action_FLY_DRONE"))
    }

    @Test
    fun `the legacy sweep runs only once`() = runBlocking {
        cleanup.cleanPoisonedMemories()
        save("invalid_action_SAVED_LATER", "kept, the one-time sweep already ran")

        cleanup.cleanPoisonedMemories()

        assertEquals("kept, the one-time sweep already ran", value("invalid_action_SAVED_LATER"))
    }

    private suspend fun save(key: String, value: String) {
        repository.saveMemory(Memory(key = key, value = value, type = MemoryType.SEMANTIC))
    }

    private suspend fun value(key: String): String? =
        repository.getMemoriesByType(MemoryType.SEMANTIC).firstOrNull { it.key == key }?.value
}
