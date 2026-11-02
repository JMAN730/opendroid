package com.opendroid.ai.core.routine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.HabitEventEntity
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.RoutineStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class PausedRoutineTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var engine: HabitRoutineEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = HabitRoutineEngine(
            context = context,
            habitDao = database.habitDao(),
            macroDao = database.macroDao(),
            notificationDao = database.notificationDao(),
            personalGrowthEngine = dagger.Lazy { error("not used") },
            actionSequenceExecutor = dagger.Lazy { error("not used") }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `switching a routine off keeps it in the automated routines list`() = runBlocking {
        insertApprovedRoutine()

        engine.toggleRoutine(ROUTINE_ID, false)

        val listed = engine.activeRoutinesFlow.first().singleOrNull { it.id == ROUTINE_ID }
        assertNotNull("Paused routine must stay on the Routines screen", listed)
        assertEquals(RoutineStatus.PAUSED, listed!!.status)
        assertFalse(database.macroDao().getMacroById(MACRO_ID)!!.isEnabled)
    }

    @Test
    fun `switching a paused routine back on makes it active again`() = runBlocking {
        insertApprovedRoutine()
        engine.toggleRoutine(ROUTINE_ID, false)

        engine.toggleRoutine(ROUTINE_ID, true)

        val listed = engine.activeRoutinesFlow.first().single { it.id == ROUTINE_ID }
        assertEquals(RoutineStatus.ACTIVE, listed.status)
        assertTrue(database.macroDao().getMacroById(MACRO_ID)!!.isEnabled)
    }

    @Test
    fun `suggested and dismissed routines stay off the automated routines list`() = runBlocking {
        insertApprovedRoutine()
        val dao = database.habitDao()
        dao.insertRoutine(routine("routine_suggested", RoutineStatus.SUGGESTED.name))
        dao.insertRoutine(routine("routine_dismissed", RoutineStatus.DISMISSED.name))

        val ids = engine.activeRoutinesFlow.first().map { it.id }

        assertEquals(listOf(ROUTINE_ID), ids)
    }

    @Test
    fun `re-detection leaves a paused routine's name and steps alone`() = runBlocking {
        insertApprovedRoutine()
        engine.toggleRoutine(ROUTINE_ID, false)
        insertWeekdayMorningEvents()

        engine.detectRoutines(lookbackDays = 14, minRepetitions = 3)

        val stored = database.habitDao().getRoutineById(ROUTINE_ID)!!
        assertEquals(RoutineStatus.PAUSED.name, stored.status)
        assertEquals("My Morning", stored.name)
        assertEquals(STEPS_JSON, stored.suggestedStepsJson)
    }

    private suspend fun insertApprovedRoutine() {
        database.macroDao().insertMacro(
            MacroEntity(
                id = MACRO_ID,
                name = "My Morning",
                trigger = "cron:0 9 * * 1-5",
                stepsJson = STEPS_JSON,
                isSystem = false,
                isEnabled = true
            )
        )
        database.habitDao().insertRoutine(routine(ROUTINE_ID, RoutineStatus.APPROVED.name, MACRO_ID))
    }

    private fun routine(id: String, status: String, macroId: String? = null) = HabitRoutineEntity(
        id = id,
        name = "My Morning",
        description = "Weekday morning briefing",
        triggerLabel = "Every weekday at 9:00 AM",
        triggerCron = "0 9 * * 1-5",
        detectedActionsJson = "[\"Gmail\", \"Calendar\", \"Slack\"]",
        suggestedStepsJson = STEPS_JSON,
        repetitionCount = 4,
        confidence = 0.85f,
        status = status,
        suggestionMessage = "Automate?",
        createdAt = System.currentTimeMillis(),
        lastDetectedAt = System.currentTimeMillis(),
        macroId = macroId
    )

    // Four weekday mornings of Gmail -> Calendar -> Slack, which detection turns into
    // routine_weekday_morning (the ID used above).
    private suspend fun insertWeekdayMorningEvents() {
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var added = 0
        while (added < 4) {
            day.add(Calendar.DAY_OF_YEAR, -1)
            val dow = day.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue
            listOf("Gmail", "Calendar", "Slack").forEachIndexed { i, app ->
                database.habitDao().insertEvent(
                    HabitEventEntity(
                        id = "e_${added}_$i",
                        eventType = "APP_OPEN",
                        packageName = "pkg.$app",
                        actionName = app,
                        timestamp = day.timeInMillis + i * 60_000L,
                        dayOfWeek = dow,
                        hourOfDay = 9,
                        minuteOfHour = i
                    )
                )
            }
            added++
        }
    }

    private companion object {
        const val ROUTINE_ID = "routine_weekday_morning"
        const val MACRO_ID = "macro_morning"
        const val STEPS_JSON =
            "[{\"stepId\":\"s1\",\"order\":1,\"description\":\"Read calendar\",\"action\":\"LIST_CALENDAR_TODAY\"}]"
    }
}
