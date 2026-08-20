package com.opendroid.ai.core.routine

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.data.db.dao.HabitDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.HabitEventEntity
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutineTimeBucket(val startHour: Int, val endHour: Int, val label: String, val cronHour: Int) {
    EARLY_MORNING(5, 7, "6:30 AM", 6),
    MORNING(8, 11, "9:00 AM", 9),
    MIDDAY(12, 14, "12:30 PM", 12),
    AFTERNOON(15, 17, "4:00 PM", 16),
    EVENING(18, 20, "7:00 PM", 19),
    NIGHT(21, 23, "10:00 PM", 22)
}

data class SessionCluster(
    val dayOfWeek: Int,
    val avgHour: Int,
    val actions: List<String>,
    val isWeekday: Boolean
)

@Singleton
class HabitRoutineEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitDao: HabitDao,
    private val macroDao: MacroDao,
    private val notificationDao: NotificationDao,
    private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>,
    private val actionSequenceExecutor: dagger.Lazy<ActionSequenceExecutor>
) {
    companion object {
        private const val TAG = "HabitRoutineEngine"
        private const val DEBOUNCE_MS = 5000L // 5s debounce for identical app switches
        private const val SESSION_WINDOW_MS = 30 * 60 * 1000L // 30 minutes session clustering
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastRecordedPackage: String? = null
    private var lastRecordedTimestamp: Long = 0L

    val suggestedRoutinesFlow: Flow<List<HabitRoutine>> = habitDao.getSuggestedRoutinesFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    val activeRoutinesFlow: Flow<List<HabitRoutine>> = habitDao.getActiveRoutinesFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    val allRoutinesFlow: Flow<List<HabitRoutine>> = habitDao.getAllRoutinesFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    val recentEventsFlow: Flow<List<HabitEvent>> = habitDao.getRecentEventsFlow().map { entities ->
        entities.map { it.toDomainModel() }
    }

    /**
     * Records a foreground application launch event from AccessibilityService.
     */
    fun recordAppOpen(packageName: String, appName: String? = null, metadata: Map<String, String> = emptyMap()) {
        val now = System.currentTimeMillis()
        if (packageName == lastRecordedPackage && (now - lastRecordedTimestamp) < DEBOUNCE_MS) {
            return // Skip rapid duplicate focus events
        }

        lastRecordedPackage = packageName
        lastRecordedTimestamp = now

        val resolvedName = appName ?: resolveFriendlyAppName(packageName)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfHour = cal.get(Calendar.MINUTE)

        engineScope.launch {
            val entity = HabitEventEntity(
                id = UUID.randomUUID().toString(),
                eventType = HabitEventType.APP_OPEN.name,
                packageName = packageName,
                actionName = resolvedName,
                timestamp = now,
                dayOfWeek = dayOfWeek,
                hourOfDay = hourOfDay,
                minuteOfHour = minuteOfHour,
                metadataJson = json.encodeToString(metadata)
            )
            habitDao.insertEvent(entity)

            // Trigger pattern detection every 10 events
            val count = habitDao.getEventCount()
            if (count % 10 == 0) {
                detectRoutines()
            }
        }
    }

    /**
     * Records a direct user action/agent execution.
     */
    fun recordAction(actionName: String, params: Map<String, String> = emptyMap()) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val minuteOfHour = cal.get(Calendar.MINUTE)

        engineScope.launch {
            val entity = HabitEventEntity(
                id = UUID.randomUUID().toString(),
                eventType = HabitEventType.AGENT_ACTION.name,
                packageName = context.packageName,
                actionName = actionName,
                timestamp = now,
                dayOfWeek = dayOfWeek,
                hourOfDay = hourOfDay,
                minuteOfHour = minuteOfHour,
                metadataJson = json.encodeToString(params)
            )
            habitDao.insertEvent(entity)
        }
    }

    /**
     * Analyzes historical event clusters and extracts recurring routine patterns.
     * When a sequence of actions repeats >= minRepetitions in a similar time window,
     * it generates a suggested routine for the user.
     */
    suspend fun detectRoutines(lookbackDays: Int = 14, minRepetitions: Int = 3): List<HabitRoutine> {
        val since = System.currentTimeMillis() - (lookbackDays.toLong() * 24 * 60 * 60 * 1000L)
        val events = habitDao.getEventsSince(since)
        if (events.isEmpty()) return emptyList()

        // 1. Group events by calendar day: (year * 1000 + dayOfYear)
        val eventsByDay = events.groupBy { event ->
            val cal = Calendar.getInstance().apply { timeInMillis = event.timestamp }
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }

        // 2. Cluster each day's events into 30-minute sessions
        val allSessions = mutableListOf<SessionCluster>()
        for ((_, dayEvents) in eventsByDay) {
            var currentCluster = mutableListOf<HabitEventEntity>()
            for (event in dayEvents) {
                if (currentCluster.isEmpty()) {
                    currentCluster.add(event)
                } else {
                    val lastEvent = currentCluster.last()
                    if (event.timestamp - lastEvent.timestamp <= SESSION_WINDOW_MS) {
                        currentCluster.add(event)
                    } else {
                        // End previous cluster
                        if (currentCluster.size >= 2) {
                            val distinctActions = currentCluster.map { it.actionName }.distinct()
                            val avgHour = currentCluster.map { it.hourOfDay }.average().toInt()
                            val dow = currentCluster.first().dayOfWeek
                            val isWkday = dow in Calendar.MONDAY..Calendar.FRIDAY
                            allSessions.add(SessionCluster(dow, avgHour, distinctActions, isWkday))
                        }
                        currentCluster = mutableListOf(event)
                    }
                }
            }
            if (currentCluster.size >= 2) {
                val distinctActions = currentCluster.map { it.actionName }.distinct()
                val avgHour = currentCluster.map { it.hourOfDay }.average().toInt()
                val dow = currentCluster.first().dayOfWeek
                val isWkday = dow in Calendar.MONDAY..Calendar.FRIDAY
                allSessions.add(SessionCluster(dow, avgHour, distinctActions, isWkday))
            }
        }

        // 3. Find repeating patterns across sessions
        val discoveredRoutines = mutableListOf<HabitRoutineEntity>()
        val existingRoutines = habitDao.getAllRoutines().associateBy { it.id }

        for (bucket in RoutineTimeBucket.values()) {
            val weekdaySessionsInBucket = allSessions.filter { it.isWeekday && it.avgHour in bucket.startHour..bucket.endHour }
            val dailySessionsInBucket = allSessions.filter { it.avgHour in bucket.startHour..bucket.endHour }

            // Check weekday patterns
            checkAndSynthesizeRoutine(
                sessions = weekdaySessionsInBucket,
                bucket = bucket,
                isWeekdayOnly = true,
                minRepetitions = minRepetitions,
                existingRoutines = existingRoutines,
                discoveredRoutines = discoveredRoutines
            )

            // Check daily patterns
            if (dailySessionsInBucket.size >= minRepetitions && weekdaySessionsInBucket.size < minRepetitions) {
                checkAndSynthesizeRoutine(
                    sessions = dailySessionsInBucket,
                    bucket = bucket,
                    isWeekdayOnly = false,
                    minRepetitions = minRepetitions,
                    existingRoutines = existingRoutines,
                    discoveredRoutines = discoveredRoutines
                )
            }
        }

        // Save newly discovered routines to DAO and Personal Knowledge Graph
        for (routine in discoveredRoutines) {
            habitDao.insertRoutine(routine)

            // Feed pattern into PKG
            try {
                personalGrowthEngine.get().recordExplicitMemory(
                    label = routine.name,
                    summary = "Detected routine: ${routine.name} (${routine.triggerLabel}) - ${routine.description}",
                    category = KnowledgeCategory.TASK_ROUTINE,
                    properties = mapOf("routineId" to routine.id, "confidence" to routine.confidence.toString())
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update PKG for routine ${routine.id}: ${e.message}")
            }
        }

        return habitDao.getAllRoutines().map { it.toDomainModel() }
    }

    private fun checkAndSynthesizeRoutine(
        sessions: List<SessionCluster>,
        bucket: RoutineTimeBucket,
        isWeekdayOnly: Boolean,
        minRepetitions: Int,
        existingRoutines: Map<String, HabitRoutineEntity>,
        discoveredRoutines: MutableList<HabitRoutineEntity>
    ) {
        if (sessions.isEmpty()) return

        val bucketName = bucket.name
        val bucketLabel = bucket.label
        val cronHour = bucket.cronHour

        // Count action frequency across sessions
        val actionFrequency = mutableMapOf<String, Int>()
        for (s in sessions) {
            for (action in s.actions) {
                actionFrequency[action] = (actionFrequency[action] ?: 0) + 1
            }
        }

        // Filter frequent actions appearing in at least minRepetitions sessions
        val frequentActions = actionFrequency.filter { it.value >= minRepetitions }.keys.toList()
        if (frequentActions.size < 2) return

        val repetitionCount = actionFrequency.values.maxOrNull() ?: minRepetitions
        val confidence = (repetitionCount.toFloat() / 14f).coerceIn(0.6f, 0.95f)

        val triggerLabel = if (isWeekdayOnly) "Every weekday at $bucketLabel" else "Daily at $bucketLabel"
        val triggerCron = if (isWeekdayOnly) "0 $cronHour * * 1-5" else "0 $cronHour * * *"

        val isMorning = bucket == RoutineTimeBucket.MORNING || bucket == RoutineTimeBucket.EARLY_MORNING
        val hasEmail = frequentActions.any { it.contains("Gmail", ignoreCase = true) || it.contains("Email", ignoreCase = true) }
        val hasCalendar = frequentActions.any { it.contains("Calendar", ignoreCase = true) }
        val hasSlack = frequentActions.any { it.contains("Slack", ignoreCase = true) || it.contains("Teams", ignoreCase = true) }

        val routineId = "routine_${if (isWeekdayOnly) "weekday" else "daily"}_${bucketName.lowercase()}"
        val existing = existingRoutines[routineId]
        if (existing != null && (existing.status == RoutineStatus.DISMISSED.name || existing.status == RoutineStatus.APPROVED.name || existing.status == RoutineStatus.ACTIVE.name)) {
            return // Respect user's past decision
        }

        val name: String
        val description: String
        val suggestionMessage: String
        val suggestedSteps: List<PlanStep>

        if (isMorning && (hasEmail || hasCalendar || hasSlack)) {
            name = "Morning Routine"
            description = "Weekday morning briefing and task preparation (${frequentActions.joinToString(", ")})"
            suggestionMessage = "I noticed you usually do these tasks every weekday morning. Would you like me to automate them?"
            suggestedSteps = listOf(
                PlanStep(
                    stepId = "step_1",
                    order = 1,
                    description = "Read calendar",
                    action = "LIST_CALENDAR_TODAY",
                    params = emptyMap()
                ),
                PlanStep(
                    stepId = "step_2",
                    order = 2,
                    description = "Summarize today's meetings",
                    action = "GET_MORNING_BRIEFING",
                    params = mapOf("section" to "schedule")
                ),
                PlanStep(
                    stepId = "step_3",
                    order = 3,
                    description = "Check important notifications",
                    action = "READ_NOTIFICATIONS",
                    params = mapOf("count" to "5")
                ),
                PlanStep(
                    stepId = "step_4",
                    order = 4,
                    description = "Prepare task list",
                    action = "READ_NOTES",
                    params = emptyMap()
                ),
                PlanStep(
                    stepId = "step_5",
                    order = 5,
                    description = "Read selected messages",
                    action = "READ_NOTIFICATIONS",
                    params = mapOf("count" to "3")
                ),
                PlanStep(
                    stepId = "step_6",
                    order = 6,
                    description = "Give morning briefing",
                    action = "GET_MORNING_BRIEFING",
                    params = mapOf("section" to "full", "speak" to "true")
                )
            )
        } else if (bucket == RoutineTimeBucket.EVENING || bucket == RoutineTimeBucket.NIGHT) {
            name = "Evening Wrap-up"
            description = "Evening review and next day preparation (${frequentActions.joinToString(", ")})"
            suggestionMessage = "I noticed you usually wrap up your tasks in the evening. Would you like me to automate this routine?"
            suggestedSteps = listOf(
                PlanStep(
                    stepId = "step_1",
                    order = 1,
                    description = "Check tomorrow's calendar",
                    action = "LIST_CALENDAR_WEEK",
                    params = emptyMap()
                ),
                PlanStep(
                    stepId = "step_2",
                    order = 2,
                    description = "Check pending notifications",
                    action = "READ_NOTIFICATIONS",
                    params = mapOf("count" to "5")
                ),
                PlanStep(
                    stepId = "step_3",
                    order = 3,
                    description = "Evening summary",
                    action = "GET_MORNING_BRIEFING",
                    params = mapOf("section" to "evening")
                )
            )
        } else {
            name = "${frequentActions.first()} & Workflow"
            description = "Automated sequence: ${frequentActions.joinToString(" → ")}"
            suggestionMessage = "I noticed you usually do these tasks around $bucketLabel. Would you like me to automate them?"
            suggestedSteps = frequentActions.mapIndexed { idx, action ->
                PlanStep(
                    stepId = "step_${idx + 1}",
                    order = idx + 1,
                    description = "Open $action",
                    action = "OPEN_APP",
                    params = mapOf("appName" to action)
                )
            }
        }

        val routineEntity = HabitRoutineEntity(
            id = routineId,
            name = name,
            description = description,
            triggerLabel = triggerLabel,
            triggerCron = triggerCron,
            detectedActionsJson = json.encodeToString(frequentActions),
            suggestedStepsJson = json.encodeToString(suggestedSteps),
            repetitionCount = repetitionCount,
            confidence = confidence,
            status = existing?.status ?: RoutineStatus.SUGGESTED.name,
            suggestionMessage = suggestionMessage,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastDetectedAt = System.currentTimeMillis(),
            lastExecutedAt = existing?.lastExecutedAt,
            macroId = existing?.macroId
        )

        discoveredRoutines.add(routineEntity)
    }

    /**
     * User approves a suggested routine. Converts the routine into an executable Macro
     * and activates it in the automated routines list and Personal Knowledge Graph.
     */
    suspend fun approveRoutine(routineId: String): Macro? {
        val entity = habitDao.getRoutineById(routineId) ?: return null
        val macroId = entity.macroId ?: UUID.randomUUID().toString()

        val macroEntity = MacroEntity(
            id = macroId,
            name = entity.name,
            trigger = if (entity.triggerCron.isNotBlank()) "cron:${entity.triggerCron}" else "manual",
            stepsJson = entity.suggestedStepsJson,
            isSystem = false,
            isEnabled = true
        )
        macroDao.insertMacro(macroEntity)

        habitDao.updateRoutineStatus(routineId, RoutineStatus.APPROVED.name, macroId)

        // Store explicit fact in Personal Knowledge Graph
        try {
            personalGrowthEngine.get().recordExplicitMemory(
                label = entity.name,
                summary = "User approved automated routine '${entity.name}' scheduled for ${entity.triggerLabel}",
                category = KnowledgeCategory.TASK_ROUTINE,
                properties = mapOf("macroId" to macroId, "trigger" to entity.triggerLabel)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record approved routine in PKG: ${e.message}")
        }

        return try {
            val steps = json.decodeFromString<List<PlanStep>>(entity.suggestedStepsJson)
            Macro(
                id = macroId,
                name = entity.name,
                trigger = macroEntity.trigger,
                steps = steps,
                isSystem = false,
                isEnabled = true
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * User dismisses a suggested routine.
     */
    suspend fun dismissRoutine(routineId: String) {
        habitDao.updateRoutineStatus(routineId, RoutineStatus.DISMISSED.name)
    }

    /**
     * Toggles an active routine on/off.
     */
    suspend fun toggleRoutine(routineId: String, isEnabled: Boolean) {
        val entity = habitDao.getRoutineById(routineId) ?: return
        val newStatus = if (isEnabled) RoutineStatus.ACTIVE.name else RoutineStatus.PAUSED.name
        habitDao.updateRoutineStatus(routineId, newStatus, entity.macroId)

        entity.macroId?.let { macroId ->
            val macro = macroDao.getMacroById(macroId)
            if (macro != null) {
                macroDao.insertMacro(macro.copy(isEnabled = isEnabled))
            }
        }
    }

    /**
     * Manually executes an approved routine.
     */
    suspend fun executeRoutine(routineId: String, context: Context): ActionResult {
        val entity = habitDao.getRoutineById(routineId)
            ?: return ActionResult.Failure("Routine with ID '$routineId' not found.")

        val steps = try {
            json.decodeFromString<List<PlanStep>>(entity.suggestedStepsJson)
        } catch (e: Exception) {
            return ActionResult.Failure("Invalid routine steps configuration.")
        }

        val result = actionSequenceExecutor.get().execute(steps, context)
        if (result.success) {
            habitDao.updateLastExecuted(routineId, System.currentTimeMillis())
        }
        return result
    }

    /**
     * Builds a comprehensive Morning Briefing summarizing calendar, notifications, and notes.
     */
    suspend fun generateMorningBriefing(section: String = "full"): String {
        val sb = StringBuilder()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }

        sb.append("🌅 $greeting! Here is your briefing:\n\n")

        // 1. Notifications
        val recentNotifs = notificationDao.getRecentNotifications(5)
        if (recentNotifs.isNotEmpty()) {
            sb.append("🔔 Important Notifications (${recentNotifs.size}):\n")
            recentNotifs.take(3).forEach { notif ->
                sb.append("• ${notif.appName}: ${notif.title} - ${notif.text.take(60)}\n")
            }
            sb.append("\n")
        }

        // 2. Schedule summary placeholder
        sb.append("📅 Schedule: Check calendar for today's upcoming meetings.\n\n")

        // 3. Motivation / Productivity
        sb.append("✨ Have a focused and productive day!")
        return sb.toString()
    }

    /**
     * Resolves known Android package names to friendly names.
     */
    fun resolveFriendlyAppName(packageName: String): String = when (packageName) {
        "com.google.android.gm" -> "Gmail"
        "com.google.android.calendar" -> "Calendar"
        "com.Slack", "com.slack" -> "Slack"
        "com.android.chrome", "com.google.android.apps.chrome" -> "Chrome"
        "com.spotify.music" -> "Spotify"
        "com.google.android.apps.maps" -> "Google Maps"
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "nekox.messenger" -> "Telegram"
        "com.google.android.apps.messaging" -> "Messages"
        "com.google.android.youtube" -> "YouTube"
        "com.google.android.keep" -> "Keep Notes"
        "com.microsoft.teams" -> "Teams"
        "com.twitter.android" -> "X (Twitter)"
        "com.instagram.android" -> "Instagram"
        "com.linkedin.android" -> "LinkedIn"
        "com.google.android.deskclock", "com.android.deskclock" -> "Clock"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun HabitEventEntity.toDomainModel(): HabitEvent = HabitEvent(
        id = id,
        eventType = try { HabitEventType.valueOf(eventType) } catch (e: Exception) { HabitEventType.APP_OPEN },
        packageName = packageName,
        actionName = actionName,
        timestamp = timestamp,
        dayOfWeek = dayOfWeek,
        hourOfDay = hourOfDay,
        minuteOfHour = minuteOfHour,
        metadata = try { json.decodeFromString(metadataJson) } catch (e: Exception) { emptyMap() }
    )

    private fun HabitRoutineEntity.toDomainModel(): HabitRoutine = HabitRoutine(
        id = id,
        name = name,
        description = description,
        triggerLabel = triggerLabel,
        triggerCron = triggerCron,
        detectedActions = try { json.decodeFromString(detectedActionsJson) } catch (e: Exception) { emptyList() },
        suggestedSteps = try { json.decodeFromString(suggestedStepsJson) } catch (e: Exception) { emptyList() },
        repetitionCount = repetitionCount,
        confidence = confidence,
        status = try { RoutineStatus.valueOf(status) } catch (e: Exception) { RoutineStatus.SUGGESTED },
        suggestionMessage = suggestionMessage,
        createdAt = createdAt,
        lastDetectedAt = lastDetectedAt,
        lastExecutedAt = lastExecutedAt,
        macroId = macroId
    )
}
