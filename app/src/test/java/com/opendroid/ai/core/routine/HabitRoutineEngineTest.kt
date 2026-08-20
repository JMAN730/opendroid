package com.opendroid.ai.core.routine

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.data.db.dao.*
import com.opendroid.ai.data.db.entities.*
import com.opendroid.ai.data.models.RoutineStatus
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class HabitRoutineEngineTest {

    private val context: Context = ContextWrapper(null)
    private lateinit var habitDao: FakeHabitDao
    private lateinit var macroDao: FakeMacroDao
    private lateinit var notificationDao: FakeNotificationDao
    private lateinit var memoryDao: FakeMemoryDao
    private lateinit var personalGrowthEngine: PersonalGrowthEngine
    private lateinit var executedActions: MutableList<String>
    private lateinit var actionSequenceExecutor: ActionSequenceExecutor
    private lateinit var engine: HabitRoutineEngine

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
        executedActions = mutableListOf()
        actionSequenceExecutor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                executedActions.add(action)
                ActionResult(true, "Step $action complete", null)
            },
            hasAction = { true }
        )

        engine = HabitRoutineEngine(
            context = context,
            habitDao = habitDao,
            macroDao = macroDao,
            notificationDao = notificationDao,
            personalGrowthEngine = dagger.Lazy { personalGrowthEngine },
            actionSequenceExecutor = dagger.Lazy { actionSequenceExecutor }
        )
    }

    @Test
    fun `friendly app name resolution works for known packages`() {
        assertEquals("Gmail", engine.resolveFriendlyAppName("com.google.android.gm"))
        assertEquals("Calendar", engine.resolveFriendlyAppName("com.google.android.calendar"))
        assertEquals("Slack", engine.resolveFriendlyAppName("com.Slack"))
        assertEquals("Chrome", engine.resolveFriendlyAppName("com.android.chrome"))
        assertEquals("Spotify", engine.resolveFriendlyAppName("com.spotify.music"))
    }

    @Test
    fun `detects repeated weekday morning pattern for Gmail, Calendar, and Slack`() = runBlocking {
        // Simulate 4 weekday mornings around 9:00 AM where user opens Gmail -> Calendar -> Slack -> Chrome
        val baseCal = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 17, 9, 0, 0) // Monday Aug 17 2026
        }

        for (dayOffset in 0..3) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = baseCal.timeInMillis + (dayOffset * 24 * 60 * 60 * 1000L)
            }
            val time = cal.timeInMillis

            habitDao.insertEvent(
                HabitEventEntity(
                    id = "e_${dayOffset}_1",
                    eventType = "APP_OPEN",
                    packageName = "com.google.android.gm",
                    actionName = "Gmail",
                    timestamp = time,
                    dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                    hourOfDay = 9,
                    minuteOfHour = 0
                )
            )
            habitDao.insertEvent(
                HabitEventEntity(
                    id = "e_${dayOffset}_2",
                    eventType = "APP_OPEN",
                    packageName = "com.google.android.calendar",
                    actionName = "Calendar",
                    timestamp = time + 60_000,
                    dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                    hourOfDay = 9,
                    minuteOfHour = 1
                )
            )
            habitDao.insertEvent(
                HabitEventEntity(
                    id = "e_${dayOffset}_3",
                    eventType = "APP_OPEN",
                    packageName = "com.Slack",
                    actionName = "Slack",
                    timestamp = time + 120_000,
                    dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                    hourOfDay = 9,
                    minuteOfHour = 2
                )
            )
        }

        val routines = engine.detectRoutines(lookbackDays = 14, minRepetitions = 3)
        assertFalse("Should have detected recurring routine", routines.isEmpty())

        val morningRoutine = routines.firstOrNull { it.name == "Morning Routine" }
        assertNotNull("Morning Routine must be detected", morningRoutine)
        assertEquals("Every weekday at 9:00 AM", morningRoutine!!.triggerLabel)
        assertEquals("0 9 * * 1-5", morningRoutine.triggerCron)
        assertEquals(RoutineStatus.SUGGESTED, morningRoutine.status)
        assertTrue(morningRoutine.suggestionMessage.contains("usually do these tasks every weekday morning"))

        // Check proposed steps include calendar, meetings summary, notifications, task list, briefing
        val steps = morningRoutine.suggestedSteps
        assertTrue("Must have synthesized automation steps", steps.size >= 5)
        assertTrue(steps.any { it.description.contains("Read calendar", ignoreCase = true) })
        assertTrue(steps.any { it.description.contains("Summarize today's meetings", ignoreCase = true) })
        assertTrue(steps.any { it.description.contains("notifications", ignoreCase = true) })
        assertTrue(steps.any { it.description.contains("task list", ignoreCase = true) })
        assertTrue(steps.any { it.description.contains("morning briefing", ignoreCase = true) })
    }

    @Test
    fun `approving a routine creates a macro and updates status`() = runBlocking {
        val routineEntity = HabitRoutineEntity(
            id = "routine_weekday_morning",
            name = "Morning Routine",
            description = "Weekday morning briefing",
            triggerLabel = "Every weekday at 9:00 AM",
            triggerCron = "0 9 * * 1-5",
            detectedActionsJson = "[\"Gmail\", \"Calendar\", \"Slack\"]",
            suggestedStepsJson = "[{\"stepId\":\"s1\",\"order\":1,\"description\":\"Read calendar\",\"action\":\"LIST_CALENDAR_TODAY\"}]",
            repetitionCount = 4,
            confidence = 0.85f,
            status = "SUGGESTED",
            suggestionMessage = "I noticed you usually do these tasks every weekday morning.",
            createdAt = System.currentTimeMillis(),
            lastDetectedAt = System.currentTimeMillis()
        )
        habitDao.insertRoutine(routineEntity)

        val approvedMacro = engine.approveRoutine("routine_weekday_morning")
        assertNotNull("Macro must be created on approval", approvedMacro)
        assertEquals("Morning Routine", approvedMacro!!.name)
        assertEquals("cron:0 9 * * 1-5", approvedMacro.trigger)

        val updatedEntity = habitDao.getRoutineById("routine_weekday_morning")
        assertEquals(RoutineStatus.APPROVED.name, updatedEntity!!.status)
        assertNotNull(updatedEntity.macroId)
    }

    @Test
    fun `dismissing a routine updates its status to DISMISSED`() = runBlocking {
        val routineEntity = HabitRoutineEntity(
            id = "routine_test",
            name = "Test Routine",
            description = "Test description",
            triggerLabel = "Daily at 9:00 AM",
            triggerCron = "0 9 * * *",
            detectedActionsJson = "[]",
            suggestedStepsJson = "[]",
            repetitionCount = 3,
            confidence = 0.7f,
            status = "SUGGESTED",
            suggestionMessage = "Automate?",
            createdAt = System.currentTimeMillis(),
            lastDetectedAt = System.currentTimeMillis()
        )
        habitDao.insertRoutine(routineEntity)

        engine.dismissRoutine("routine_test")
        val updated = habitDao.getRoutineById("routine_test")
        assertEquals(RoutineStatus.DISMISSED.name, updated!!.status)
    }

    @Test
    fun `executing a routine runs its plan steps via action executor`() = runBlocking {
        val routineEntity = HabitRoutineEntity(
            id = "routine_exec",
            name = "Morning Flow",
            description = "Executes briefing",
            triggerLabel = "Every weekday at 9:00 AM",
            triggerCron = "0 9 * * 1-5",
            detectedActionsJson = "[]",
            suggestedStepsJson = "[{\"stepId\":\"s1\",\"order\":1,\"description\":\"Calendar\",\"action\":\"CALENDAR_TODAY\"},{\"stepId\":\"s2\",\"order\":2,\"description\":\"Briefing\",\"action\":\"GET_MORNING_BRIEFING\"}]",
            repetitionCount = 3,
            confidence = 0.8f,
            status = "APPROVED",
            suggestionMessage = "Automate?",
            createdAt = System.currentTimeMillis(),
            lastDetectedAt = System.currentTimeMillis()
        )
        habitDao.insertRoutine(routineEntity)

        val result = engine.executeRoutine("routine_exec", context)
        assertTrue(result.success)
        assertEquals(listOf("CALENDAR_TODAY", "GET_MORNING_BRIEFING"), executedActions)

        val updated = habitDao.getRoutineById("routine_exec")
        assertNotNull(updated!!.lastExecutedAt)
    }

    @Test
    fun `generateMorningBriefing composes formatted greeting and notifications`() = runBlocking {
        notificationDao.insertNotification(
            NotificationEntity(
                id = 1,
                packageName = "com.Slack",
                appName = "Slack",
                title = "Design Sync",
                text = "Meeting in 15 minutes",
                timestamp = System.currentTimeMillis()
            )
        )

        val briefing = engine.generateMorningBriefing("full")
        assertTrue(briefing.contains("briefing", ignoreCase = true))
        assertTrue(briefing.contains("Slack", ignoreCase = true))
        assertTrue(briefing.contains("Design Sync", ignoreCase = true))
    }
}

// ── In-Memory Test Fakes ────────────────────────────────────────────────

class FakeHabitDao : HabitDao {
    private val events = mutableListOf<HabitEventEntity>()
    private val routines = mutableMapOf<String, HabitRoutineEntity>()
    private val routinesFlow = MutableStateFlow<List<HabitRoutineEntity>>(emptyList())
    private val eventsFlow = MutableStateFlow<List<HabitEventEntity>>(emptyList())

    override suspend fun insertEvent(event: HabitEventEntity) {
        events.add(event)
        eventsFlow.value = events.toList()
    }

    override suspend fun insertEvents(events: List<HabitEventEntity>) {
        this.events.addAll(events)
        eventsFlow.value = this.events.toList()
    }

    override suspend fun getRecentEvents(limit: Int): List<HabitEventEntity> =
        events.takeLast(limit).reversed()

    override suspend fun getEventsSince(sinceTimestamp: Long): List<HabitEventEntity> =
        events.filter { it.timestamp >= sinceTimestamp }

    override fun getRecentEventsFlow(): Flow<List<HabitEventEntity>> = eventsFlow

    override suspend fun getEventCount(): Int = events.size

    override suspend fun clearEventsBefore(beforeTimestamp: Long) {
        events.removeAll { it.timestamp < beforeTimestamp }
    }

    override suspend fun clearAllEvents() {
        events.clear()
    }

    override fun getAllRoutinesFlow(): Flow<List<HabitRoutineEntity>> = routinesFlow

    override suspend fun getAllRoutines(): List<HabitRoutineEntity> = routines.values.toList()

    override fun getSuggestedRoutinesFlow(): Flow<List<HabitRoutineEntity>> =
        MutableStateFlow(routines.values.filter { it.status == "SUGGESTED" })

    override fun getActiveRoutinesFlow(): Flow<List<HabitRoutineEntity>> =
        MutableStateFlow(routines.values.filter { it.status == "APPROVED" || it.status == "ACTIVE" })

    override suspend fun getRoutineById(id: String): HabitRoutineEntity? = routines[id]

    override suspend fun insertRoutine(routine: HabitRoutineEntity) {
        routines[routine.id] = routine
        routinesFlow.value = routines.values.toList()
    }

    override suspend fun updateRoutineStatus(id: String, status: String, macroId: String?) {
        val existing = routines[id] ?: return
        routines[id] = existing.copy(status = status, macroId = macroId ?: existing.macroId)
        routinesFlow.value = routines.values.toList()
    }

    override suspend fun updateLastExecuted(id: String, timestamp: Long) {
        val existing = routines[id] ?: return
        routines[id] = existing.copy(lastExecutedAt = timestamp)
        routinesFlow.value = routines.values.toList()
    }

    override suspend fun deleteRoutine(id: String) {
        routines.remove(id)
        routinesFlow.value = routines.values.toList()
    }

    override suspend fun clearAllRoutines() {
        routines.clear()
    }
}

class FakeMacroDao : MacroDao {
    private val macros = mutableMapOf<String, MacroEntity>()

    override suspend fun insertMacro(macro: MacroEntity) {
        macros[macro.id] = macro
    }

    override suspend fun getAllMacros(): List<MacroEntity> = macros.values.toList()

    override fun getAllMacrosFlow(): Flow<List<MacroEntity>> =
        MutableStateFlow(macros.values.toList())

    override suspend fun getMacroByName(name: String): MacroEntity? =
        macros.values.firstOrNull { it.name == name }

    override suspend fun getMacroById(id: String): MacroEntity? = macros[id]

    override suspend fun deleteMacro(id: String) {
        macros.remove(id)
    }

    override suspend fun clearAllMacros() {
        macros.clear()
    }
}

class FakeNotificationDao : NotificationDao {
    private val notifications = mutableListOf<NotificationEntity>()

    override suspend fun insertNotification(notification: NotificationEntity): Long {
        notifications.add(notification)
        return notification.id
    }

    override fun getAllNotificationsFlow(): Flow<List<NotificationEntity>> =
        MutableStateFlow(notifications.toList())

    override suspend fun getRecentNotifications(limit: Int): List<NotificationEntity> =
        notifications.takeLast(limit).reversed()

    override suspend fun getNotificationsByApp(packageName: String, limit: Int): List<NotificationEntity> =
        notifications.filter { it.packageName == packageName }.takeLast(limit).reversed()

    override suspend fun getNotificationsForContact(contactName: String, limit: Int): List<NotificationEntity> =
        notifications.filter { it.contactName == contactName }.takeLast(limit).reversed()

    override suspend fun getNotificationsSince(since: Long, limit: Int): List<NotificationEntity> =
        notifications.filter { it.timestamp > since }.takeLast(limit).reversed()

    override suspend fun getMessageNotificationsSince(since: Long): List<NotificationEntity> =
        notifications.filter { it.category == "MESSAGE" && it.timestamp > since }.reversed()

    override suspend fun markAsRead(id: Long) {
        val idx = notifications.indexOfFirst { it.id == id }
        if (idx >= 0) notifications[idx] = notifications[idx].copy(isRead = true)
    }

    override suspend fun markAsAutoReplied(id: Long, replyText: String) {
        val idx = notifications.indexOfFirst { it.id == id }
        if (idx >= 0) notifications[idx] = notifications[idx].copy(isAutoReplied = true, autoReplyText = replyText)
    }

    override suspend fun getAutoReplyCountForContact(contactName: String, since: Long): Int =
        notifications.count { it.contactName == contactName && it.isAutoReplied && it.timestamp > since }

    override suspend fun getNotificationCountByApp(since: Long): List<AppNotificationCount> = emptyList()

    override suspend fun getMostActiveContacts(since: Long, limit: Int): List<ContactNotificationCount> = emptyList()

    override suspend fun getTotalCount(): Int = notifications.size

    override suspend fun getAutoRepliedCount(): Int = notifications.count { it.isAutoReplied }

    override suspend fun deleteNotification(notification: NotificationEntity) {
        notifications.remove(notification)
    }

    override suspend fun deleteNotificationsByApp(packageName: String): Int {
        val before = notifications.size
        notifications.removeAll { it.packageName == packageName }
        return before - notifications.size
    }

    override suspend fun deleteNotificationById(id: Long): Int {
        val before = notifications.size
        notifications.removeAll { it.id == id }
        return before - notifications.size
    }

    override suspend fun deleteOldNotifications(olderThan: Long) {
        notifications.removeAll { it.timestamp < olderThan }
    }

    override suspend fun clearAll(): Int {
        val size = notifications.size
        notifications.clear()
        return size
    }
}

class FakeMemoryDao : MemoryDao {
    private val memories = mutableMapOf<String, MemoryEntity>()

    override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = MutableStateFlow(memories.values.toList())
    override suspend fun getAllMemories(): List<MemoryEntity> = memories.values.toList()
    override suspend fun getValidMemories(now: Long): List<MemoryEntity> = memories.values.toList()
    override suspend fun getValidMemoriesByType(type: String, now: Long): List<MemoryEntity> = memories.values.filter { it.type == type }
    override suspend fun getMemoryByKey(key: String): MemoryEntity? = memories[key]
    override suspend fun getMemoriesByType(type: String): List<MemoryEntity> = memories.values.filter { it.type == type }
    override suspend fun insertOrUpdateMemory(memory: MemoryEntity) { memories[memory.key] = memory }
    override suspend fun deleteMemory(key: String) { memories.remove(key) }
    override suspend fun clearMemoryByType(type: String) { memories.values.removeAll { it.type == type } }
    override suspend fun deleteExpiredMemories(now: Long) {}
    override suspend fun deleteMemoryEntity(memory: MemoryEntity) { memories.remove(memory.key) }
    override suspend fun getMemoriesByKeyPrefix(prefix: String): List<MemoryEntity> = memories.values.filter { it.key.startsWith(prefix) }
}

class FakeTaskHistoryDao : TaskHistoryDao {
    private val history = mutableListOf<TaskHistoryEntity>()
    override fun getTaskHistoryFlow(): Flow<List<TaskHistoryEntity>> = MutableStateFlow(history.toList())
    override suspend fun getTaskHistoryForPlan(planId: String): List<TaskHistoryEntity> = history.filter { it.planId == planId }
    override suspend fun insertHistory(task: TaskHistoryEntity) { history.add(task) }
    override suspend fun clearAll() { history.clear() }
}

class FakeSensitiveMemoryStore : SensitiveMemoryStore {
    private val data = mutableMapOf<String, String>()
    override fun read(key: String): String? = data[key]
    override fun write(key: String, value: String): Boolean { data[key] = value; return true }
    override fun remove(key: String): Boolean = data.remove(key) != null
    override fun listKeys(): Set<String> = data.keys
    override fun getAllDecrypted(): Map<String, String> = data.toMap()
    override fun clearAll(): Boolean { data.clear(); return true }
}
