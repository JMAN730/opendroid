package com.opendroid.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.routine.FakeSensitiveMemoryStore
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.UserProfile
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.dao.ContactNotificationCount
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
class NotificationPatternRetentionTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var dao: NotificationDao
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var intelligence: NotificationIntelligence
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.notificationDao()
        memoryRepository = MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao())
        intelligence = NotificationIntelligence(dao, memoryRepository)
        val workingMemory = WorkingMemory(context, DeviceStateProvider(context))
        val memoryExtractor = MemoryExtractor(memoryRepository)
        val conversationRepository = ConversationRepository(database, database.conversationDao(), database.chatSessionDao())
        memoryManager = MemoryManager(
            workingMemory = workingMemory,
            episodicMemory = EpisodicMemory(conversationRepository),
            semanticMemory = SemanticMemory(memoryRepository, memoryExtractor),
            proceduralMemory = ProceduralMemory(memoryRepository),
            personalGrowthEngine = PersonalGrowthEngine(
                memoryRepository = memoryRepository,
                sensitiveMemoryStore = FakeSensitiveMemoryStore(),
                workingMemory = workingMemory,
                notificationIntelligence = intelligence
            ),
            memoryExtractor = memoryExtractor,
            conversationRepository = conversationRepository,
            memoryRepository = memoryRepository,
            notificationIntelligence = intelligence,
            userProfileStore = EmptyProfileStore(),
            context = context
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `contact times live in one row that each run replaces`() = runBlocking {
        FIRST_SENDERS.forEachIndexed { i, name -> insert(name, minutesAgo = 120 - i) }
        intelligence.analyzePatterns()
        LATER_SENDERS.forEachIndexed { i, name -> insert(name, minutesAgo = 60 - i) }
        intelligence.analyzePatterns()

        val rows = contactTimeRows()

        assertEquals(rows.map { it.key }.toString(), 1, rows.size)
        LATER_SENDERS.forEach { assertTrue("missing $it: ${rows[0].value}", rows[0].value.contains(it)) }
        FIRST_SENDERS.forEach { assertFalse("stale $it: ${rows[0].value}", rows[0].value.contains(it)) }
    }

    @Test
    fun `analysis purges the per-title rows earlier versions left behind`() = runBlocking {
        saveLegacyRow("Breaking: storm warning")
        insert("Avery", minutesAgo = 5)

        intelligence.analyzePatterns()

        assertTrue(patternRows().none { it.key.contains("Breaking") || it.value.contains("Breaking") })
    }

    @Test
    fun `titles from non-message apps never become contacts`() = runBlocking {
        repeat(50) { insert("Headline $it", minutesAgo = 100 - it, category = "OTHER") }

        intelligence.analyzePatterns()

        val leaked = patternRows().filter { it.value.contains("Headline") }
        assertTrue(leaked.joinToString { "${it.key}=${it.value}" }, leaked.isEmpty())
    }

    @Test
    fun `an empty window drops the summaries the last run wrote`() = runBlocking {
        insert("Avery", minutesAgo = 5)
        intelligence.analyzePatterns()
        dao.clearAll()

        intelligence.analyzePatterns()

        val keys = patternRows().map { it.key }
        listOf("top_contacts", "top_apps", "peak_activity", "contact_times").forEach {
            assertFalse("$it survived: $keys", keys.contains("notif_pattern_$it"))
        }
    }

    @Test
    fun `patterns are relearned on the next notification after they are forgotten`() = runBlocking {
        insert("Avery", minutesAgo = 5)
        intelligence.analyzeIfNeeded()
        intelligence.clearLearnedPatterns()

        // A listener rebind forgets the patterns; stored history should be relearned right away,
        // not after the 6-hour analysis interval.
        intelligence.analyzeIfNeeded()

        assertTrue(patternRows().any { it.key == "notif_pattern_top_contacts" })
    }

    @Test
    fun `clearing the history also forgets what was learned from it`() = runBlocking {
        (FIRST_SENDERS + LATER_SENDERS).forEachIndexed { i, name -> insert(name, minutesAgo = 100 - i) }
        intelligence.analyzePatterns()
        saveLegacyRow("Breaking: storm warning")

        // What History "Clear" and an unfiltered DISMISS_NOTIFICATION call.
        NotificationHistoryCleaner(intelligence).clearHistory()

        val prompt = memoryManager.getRelevantContext("")
        assertTrue(patternRows().isEmpty())
        assertFalse(prompt, prompt.contains("Most contacted"))
        assertFalse(prompt, prompt.contains("Breaking"))
        (FIRST_SENDERS + LATER_SENDERS).forEach { assertFalse("$it in:\n$prompt", prompt.contains(it)) }
    }

    @Test
    fun `analysis already in flight cannot rewrite patterns after the history is cleared`() = runBlocking {
        FIRST_SENDERS.forEachIndexed { i, name -> insert(name, minutesAgo = 100 - i) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : NotificationDao by dao {
            override suspend fun getMostActiveContacts(since: Long, limit: Int): List<ContactNotificationCount> {
                val rows = dao.getMostActiveContacts(since, limit)
                entered.complete(Unit)
                release.await()
                return rows
            }
        }
        val gatedIntelligence = NotificationIntelligence(gated, memoryRepository)

        val analysis = async(Dispatchers.Default) { gatedIntelligence.analyzePatterns() }
        entered.await()
        val clearing = async(Dispatchers.Default) { gatedIntelligence.clearHistory() }
        delay(200)
        release.complete(Unit)
        analysis.await()
        clearing.await()

        val leaked = patternRows().joinToString { "${it.key}=${it.value}" }
        assertTrue(leaked, patternRows().isEmpty())
    }

    @Test
    fun `patterns reach the prompt once, not again among the facts`() = runBlocking {
        insert("Avery", minutesAgo = 5)
        intelligence.analyzePatterns()

        val prompt = memoryManager.getRelevantContext("")

        assertEquals(prompt, 1, Regex("Most contacted").findAll(prompt).count())
        assertFalse(prompt, prompt.contains("notif_pattern_"))
    }

    @Test
    fun `the patterns section is capped`() = runBlocking {
        repeat(20) { saveLegacyRow("Title $it") }

        val lines = intelligence.getLearnedPatterns().lines().filter { it.isNotBlank() }

        assertTrue(lines.toString(), lines.size <= 10)
    }

    @Test
    fun `the prompt stays bounded however many senders come and go`() = runBlocking {
        var firstLength = 0
        repeat(50) { pass ->
            repeat(5) { insert("Sender $pass-$it", minutesAgo = 0) }
            intelligence.analyzePatterns()
            val length = memoryManager.getRelevantContext("").length
            if (pass == 0) firstLength = length
            assertTrue("pass $pass: $length vs $firstLength", length < firstLength + 400)
        }
    }

    private suspend fun insert(name: String, minutesAgo: Int, category: String = "MESSAGE") {
        dao.insertNotification(
            NotificationEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = name,
                text = "hi",
                timestamp = System.currentTimeMillis() - minutesAgo * 60_000L,
                category = category,
                contactName = name
            )
        )
    }

    private suspend fun saveLegacyRow(title: String) {
        memoryRepository.saveMemory(
            Memory(
                key = "notif_pattern_contact_time_$title",
                value = "$title usually messages in the morning (1 messages this week)",
                type = MemoryType.SEMANTIC
            )
        )
    }

    private suspend fun patternRows() = memoryRepository.getMemoriesByKeyPrefix("notif_pattern_")

    private suspend fun contactTimeRows() =
        patternRows().filter { it.key.startsWith("notif_pattern_contact_time") }

    private class EmptyProfileStore : UserProfileStore {
        override fun read() = ProfileStoreResult.Success<UserProfile?>(null)
        override fun write(profile: UserProfile) = ProfileStoreResult.Success(Unit)
        override fun migrateLegacyProfile() = ProfileStoreResult.Success(Unit)
        override fun resetForReentry() = ProfileStoreResult.Success(Unit)
    }

    private companion object {
        val FIRST_SENDERS = listOf("Avery", "Blake", "Casey", "Devon", "Ellis")
        val LATER_SENDERS = listOf("Frankie", "Gray", "Harper", "Indigo", "Jules")
    }
}
