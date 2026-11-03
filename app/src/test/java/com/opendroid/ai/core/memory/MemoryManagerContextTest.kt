package com.opendroid.ai.core.memory

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.agent.Contact
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.core.security.UserProfile
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.ConversationRepository
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

/**
 * A contact pick is a lookup row for the resolver, not a fact about the user, so its
 * name and number stay out of the context sent with every prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class MemoryManagerContextTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: OpenDroidDatabase
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var manager: MemoryManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryRepository = MemoryRepository(
            memoryDao = database.memoryDao(),
            taskHistoryDao = database.taskHistoryDao(),
            macroDao = database.macroDao()
        )
        val conversationRepository = ConversationRepository(
            database,
            database.conversationDao(),
            database.chatSessionDao()
        )
        val extractor = MemoryExtractor(memoryRepository)
        val workingMemory = WorkingMemory(context, DeviceStateProvider(context))
        val notificationIntelligence = NotificationIntelligence(database.notificationDao(), memoryRepository)
        manager = MemoryManager(
            workingMemory = workingMemory,
            episodicMemory = EpisodicMemory(conversationRepository),
            semanticMemory = SemanticMemory(memoryRepository, extractor),
            proceduralMemory = ProceduralMemory(memoryRepository),
            personalGrowthEngine = PersonalGrowthEngine(
                memoryRepository = memoryRepository,
                sensitiveMemoryStore = NoSensitiveMemory,
                workingMemory = workingMemory,
                notificationIntelligence = notificationIntelligence
            ),
            memoryExtractor = extractor,
            conversationRepository = conversationRepository,
            memoryRepository = memoryRepository,
            notificationIntelligence = notificationIntelligence,
            userProfileStore = NoProfile,
            context = context
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a picked contact's number stays out of the prompt context`() = runBlocking {
        memoryRepository.saveMemory(Memory("wife_name", "Sara", MemoryType.SEMANTIC))
        manager.storeContactPreference("John", Contact(name = "John Doe", phoneNumber = PHONE))

        val relevant = manager.getRelevantContext("what's a good pasta recipe?")

        assertTrue(relevant, relevant.contains("wife_name: Sara"))
        assertFalse(relevant, relevant.contains(PHONE))
        assertFalse(relevant, relevant.contains("contact_pref_"))
        assertFalse(relevant, relevant.contains("John Doe"))
    }

    @Test
    fun `the pick is still recalled by the resolver`() = runBlocking {
        manager.storeContactPreference("John", Contact(name = "John Doe", phoneNumber = PHONE))
        manager.getRelevantContext("")

        val recalled = manager.recallContactPreference("John")

        assertEquals("John Doe", recalled?.name)
        assertEquals(PHONE, recalled?.phoneNumber)
        assertEquals(listOf("john" to "John Doe"), manager.getAllContactPreferences().map { it.first to it.second.name })
    }

    private object NoProfile : UserProfileStore {
        override fun read(): ProfileStoreResult<UserProfile?> = ProfileStoreResult.Success(null)
        override fun write(profile: UserProfile): ProfileStoreResult<Unit> = ProfileStoreResult.Success(Unit)
        override fun migrateLegacyProfile(): ProfileStoreResult<Unit> = ProfileStoreResult.Success(Unit)
        override fun resetForReentry(): ProfileStoreResult<Unit> = ProfileStoreResult.Success(Unit)
    }

    private object NoSensitiveMemory : SensitiveMemoryStore {
        override fun read(key: String): String? = null
        override fun write(key: String, value: String): Boolean = true
        override fun remove(key: String): Boolean = false
        override fun listKeys(): Set<String> = emptySet()
        override fun getAllDecrypted(): Map<String, String> = emptyMap()
        override fun clearAll(): Boolean = true
    }

    private companion object {
        const val PHONE = "+447700900123"
    }
}
