package com.opendroid.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.routine.FakeHabitDao
import com.opendroid.ai.core.routine.FakeMacroDao
import com.opendroid.ai.core.routine.FakeNotificationDao
import com.opendroid.ai.core.routine.FakeSensitiveMemoryStore
import com.opendroid.ai.core.routine.HabitRoutineEngine
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.UserProfile
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.HabitEventEntity
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Routine detection runs on its own every few app opens, so whatever it writes to memory
 * must not grow the prompt context that every chat, plan and auto-reply carries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class RoutineMemoryPromptTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var habitDao: FakeHabitDao
    private lateinit var engine: HabitRoutineEngine
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val memoryRepository = MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao())
        val conversationRepository = ConversationRepository(database, database.conversationDao(), database.chatSessionDao())
        val workingMemory = WorkingMemory(context, DeviceStateProvider(context))
        val notificationIntelligence = NotificationIntelligence(FakeNotificationDao(), memoryRepository)
        val memoryExtractor = MemoryExtractor(memoryRepository)
        val personalGrowthEngine = PersonalGrowthEngine(
            memoryRepository = memoryRepository,
            sensitiveMemoryStore = FakeSensitiveMemoryStore(),
            workingMemory = workingMemory,
            notificationIntelligence = notificationIntelligence
        )
        memoryManager = MemoryManager(
            workingMemory = workingMemory,
            episodicMemory = EpisodicMemory(conversationRepository),
            semanticMemory = SemanticMemory(memoryRepository, memoryExtractor),
            proceduralMemory = ProceduralMemory(memoryRepository),
            personalGrowthEngine = personalGrowthEngine,
            memoryExtractor = memoryExtractor,
            conversationRepository = conversationRepository,
            memoryRepository = memoryRepository,
            notificationIntelligence = notificationIntelligence,
            userProfileStore = NoProfileStore,
            context = context
        )
        habitDao = FakeHabitDao()
        engine = HabitRoutineEngine(
            context = context,
            habitDao = habitDao,
            macroDao = FakeMacroDao(),
            notificationDao = FakeNotificationDao(),
            personalGrowthEngine = dagger.Lazy { personalGrowthEngine },
            actionSequenceExecutor = dagger.Lazy { throw UnsupportedOperationException() }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `prompt context does not grow with repeated routine detection`() = runBlocking {
        seedWeekdayMornings()

        engine.detectRoutines(lookbackDays = 14, minRepetitions = 3)
        val afterOneRun = contextWithoutClock()
        repeat(9) {
            Thread.sleep(20) // a fresh clock tick per run, as on a device (Windows ticks are ~16 ms)
            engine.detectRoutines(lookbackDays = 14, minRepetitions = 3)
        }
        val afterTenRuns = contextWithoutClock()

        assertEquals(afterOneRun.length, afterTenRuns.length)
        assertFalse(afterTenRuns.contains("pkg_node_"))
    }

    // The clock line can roll over between the two reads; nothing else in the context should change.
    private suspend fun contextWithoutClock(): String =
        memoryManager.getRelevantContext("").lines().filterNot { it.startsWith("Time:") }.joinToString("\n")

    private suspend fun seedWeekdayMornings() {
        val monday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_YEAR, -1)
        }
        for (day in 0..3) {
            val start = monday.timeInMillis + day * 24 * 60 * 60 * 1000L
            val dayOfWeek = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.DAY_OF_WEEK)
            listOf("Gmail", "Calendar", "Slack").forEachIndexed { i, app ->
                habitDao.insertEvent(
                    HabitEventEntity(
                        id = "e_${day}_$i",
                        eventType = "APP_OPEN",
                        packageName = "pkg.$app",
                        actionName = app,
                        timestamp = start + i * 60_000L,
                        dayOfWeek = dayOfWeek,
                        hourOfDay = 9,
                        minuteOfHour = i
                    )
                )
            }
        }
    }

    private object NoProfileStore : UserProfileStore {
        override fun read(): ProfileStoreResult<UserProfile?> = ProfileStoreResult.Success(null)
        override fun write(profile: UserProfile): ProfileStoreResult<Unit> = ProfileStoreResult.Success(Unit)
        override fun migrateLegacyProfile(): ProfileStoreResult<Unit> = ProfileStoreResult.Success(Unit)
        override fun resetForReentry(): ProfileStoreResult<Unit> = ProfileStoreResult.Success(Unit)
    }
}
