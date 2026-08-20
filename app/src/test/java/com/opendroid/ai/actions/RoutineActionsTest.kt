package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.routine.*
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoutineActionsTest {

    private val context: Context = ContextWrapper(null)
    private lateinit var habitDao: FakeHabitDao
    private lateinit var macroDao: FakeMacroDao
    private lateinit var notificationDao: FakeNotificationDao
    private lateinit var memoryDao: FakeMemoryDao
    private lateinit var personalGrowthEngine: PersonalGrowthEngine
    private lateinit var actionSequenceExecutor: ActionSequenceExecutor
    private lateinit var habitRoutineEngine: HabitRoutineEngine
    private lateinit var routineActions: RoutineActions
    private val executedActions = mutableListOf<String>()

    @Before
    fun setUp() {
        habitDao = FakeHabitDao()
        macroDao = FakeMacroDao()
        notificationDao = FakeNotificationDao()
        memoryDao = FakeMemoryDao()
        val memoryRepository = MemoryRepository(
            memoryDao = memoryDao,
            taskHistoryDao = FakeTaskHistoryDao(),
            macroDao = macroDao
        )
        personalGrowthEngine = PersonalGrowthEngine(
            memoryRepository = memoryRepository,
            sensitiveMemoryStore = FakeSensitiveMemoryStore(),
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            notificationIntelligence = NotificationIntelligence(notificationDao, memoryRepository)
        )
        executedActions.clear()

        actionSequenceExecutor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                executedActions.add(action)
                ActionResult(true, "Executed $action", null)
            },
            hasAction = { true }
        )

        habitRoutineEngine = HabitRoutineEngine(
            context = context,
            habitDao = habitDao,
            macroDao = macroDao,
            notificationDao = notificationDao,
            personalGrowthEngine = dagger.Lazy { personalGrowthEngine },
            actionSequenceExecutor = dagger.Lazy { actionSequenceExecutor }
        )

        routineActions = RoutineActions(
            habitRoutineEngine = habitRoutineEngine,
            habitDao = habitDao
        )
    }

    @Test
    fun `schema contains routine actions`() {
        assertTrue(ActionSchema.isValid("RUN_ROUTINE"))
        assertTrue(ActionSchema.isValid("GET_MORNING_BRIEFING"))
        assertTrue(ActionSchema.isValid("DETECT_ROUTINES"))
        assertTrue(ActionSchema.isValid("APPROVE_ROUTINE"))
    }

    @Test
    fun `RUN_ROUTINE executes routine by name`() = runBlocking {
        val routine = HabitRoutineEntity(
            id = "routine_morning",
            name = "Morning Routine",
            description = "Morning tasks",
            triggerLabel = "Every weekday at 9:00 AM",
            triggerCron = "0 9 * * 1-5",
            detectedActionsJson = "[]",
            suggestedStepsJson = "[{\"stepId\":\"s1\",\"order\":1,\"description\":\"Calendar\",\"action\":\"LIST_CALENDAR_TODAY\"}]",
            repetitionCount = 3,
            confidence = 0.9f,
            status = "APPROVED",
            suggestionMessage = "Automate?",
            createdAt = System.currentTimeMillis(),
            lastDetectedAt = System.currentTimeMillis()
        )
        habitDao.insertRoutine(routine)

        val runAction = routineActions.getActions().first { it.name == "RUN_ROUTINE" }
        val result = runAction.execute(mapOf("routineName" to "Morning Routine"), context)

        assertTrue(result.success)
        assertEquals(listOf("LIST_CALENDAR_TODAY"), executedActions)
    }

    @Test
    fun `GET_MORNING_BRIEFING returns structured briefing text`() = runBlocking {
        val briefingAction = routineActions.getActions().first { it.name == "GET_MORNING_BRIEFING" }
        val result = briefingAction.execute(mapOf("section" to "full"), context)

        assertTrue(result.success)
        assertNotNull(result.data)
        assertTrue(result.data!!.contains("briefing", ignoreCase = true))
    }

    @Test
    fun `APPROVE_ROUTINE approves suggested routine and creates macro`() = runBlocking {
        val routine = HabitRoutineEntity(
            id = "routine_to_approve",
            name = "Morning Workflow",
            description = "Email and Slack",
            triggerLabel = "Every weekday at 9:00 AM",
            triggerCron = "0 9 * * 1-5",
            detectedActionsJson = "[\"Gmail\", \"Slack\"]",
            suggestedStepsJson = "[{\"stepId\":\"s1\",\"order\":1,\"description\":\"Step 1\",\"action\":\"STEP_1\"}]",
            repetitionCount = 3,
            confidence = 0.8f,
            status = "SUGGESTED",
            suggestionMessage = "Automate?",
            createdAt = System.currentTimeMillis(),
            lastDetectedAt = System.currentTimeMillis()
        )
        habitDao.insertRoutine(routine)

        val approveAction = routineActions.getActions().first { it.name == "APPROVE_ROUTINE" }
        val result = approveAction.execute(mapOf("routineId" to "routine_to_approve"), context)

        assertTrue(result.success)
        assertTrue(result.data!!.contains("approved and automated"))

        val updated = habitDao.getRoutineById("routine_to_approve")
        assertEquals("APPROVED", updated!!.status)
        assertNotNull(updated.macroId)
    }

    @Test
    fun `ActionAutoMapper resolves routine variations`() {
        val mapper = ActionAutoMapper()
        val registered = setOf("RUN_ROUTINE", "GET_MORNING_BRIEFING", "DETECT_ROUTINES", "APPROVE_ROUTINE")

        val res1 = mapper.mapAction("give me morning briefing", emptyMap(), registered)
        assertEquals("GET_MORNING_BRIEFING", res1.mappedAction)

        val res2 = mapper.mapAction("detect routines", emptyMap(), registered)
        assertEquals("DETECT_ROUTINES", res2.mappedAction)

        val res3 = mapper.mapAction("approve routine", emptyMap(), registered)
        assertEquals("APPROVE_ROUTINE", res3.mappedAction)
    }
}
