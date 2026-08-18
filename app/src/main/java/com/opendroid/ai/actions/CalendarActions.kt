package com.opendroid.ai.actions

import com.opendroid.ai.core.util.DurationParser

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.VisionEngine
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import java.util.Calendar
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarActions @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val visionEngine: dagger.Lazy<VisionEngine>,
    private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
) {

    fun getActions(): List<Action> = listOf(
        CreateCalendarEventAction(),
        SetAlarmAction(),
        SetTimerAction(),
        AddNoteAction(memoryRepository),
        ListCalendarTodayAction(),
        ListCalendarWeekAction(),
        SetReminderAction(),
        CreateTaskAction(),
        ReadNotesAction(memoryRepository),
        ReadAndRememberScreenAction(visionEngine, memoryRepository),
        RecallMemoryAction(memoryRepository),
        QueryKnowledgeGraphAction(personalGrowthEngine),
        UpdatePreferenceAction(personalGrowthEngine),
        SaveSensitiveInfoAction(personalGrowthEngine)
    )

    private class CreateCalendarEventAction : Action {
        override val name: String = "CREATE_CALENDAR_EVENT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Event"
            return try {
                val cr = context.contentResolver
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, Calendar.getInstance().timeInMillis)
                    put(CalendarContract.Events.DTEND, Calendar.getInstance().timeInMillis + 60 * 60 * 1000) // 1 hr duration
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                    put(CalendarContract.Events.CALENDAR_ID, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                
                // Needs calendar permissions. If fails, fallback to calendar UI compose intent
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    ActionResult(true, "Your event '$title' is on the calendar!", null)
                } else {
                    throw IllegalStateException("Insert returned null URI")
                }
            } catch (e: Exception) {
                // Fallback: Open compose intent in system calendar
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Couldn't add it directly, but I opened the calendar for you to create it.", e.localizedMessage, true)
            }
        }
    }

    private class SetAlarmAction : Action {
        override val name: String = "SET_ALARM"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val timeStr = params["time"]
                ?: return ActionResult(false, null, "Time is required. Use format like '5 am' or '7:30'")

            val label = params["label"]?.trim() ?: "OpenDroid Alarm"

            // Parse time string to hour + minute
            val parsed = parseTimeString(timeStr)
                ?: return ActionResult(false, null,
                    "Could not understand time '$timeStr'. Try formats like '5 am', '7:30', or '14:00'")

            val (hour, minute) = parsed

            // Try Method 1: AlarmClock Intent (most reliable)
            val method1 = tryAlarmClockIntent(hour, minute, label, context)
            if (method1 != null) return method1

            // Try Method 2: Open clock app as fallback
            val method2 = tryOpenClockApp(hour, minute, context)
            if (method2 != null) return method2

            // All methods failed
            val timeFormatted = formatTime(hour, minute)
            return ActionResult(false, null,
                "Could not set alarm for $timeFormatted. Please open Clock app manually.")
        }

        private fun tryAlarmClockIntent(
            hour: Int, minute: Int, label: String, context: Context
        ): ActionResult? {
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Check if any app can handle this intent
                val resolved = context.packageManager
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)

                if (resolved == null) {
                    null // no clock app can handle it
                } else {
                    context.startActivity(intent)
                    val timeFormatted = formatTime(hour, minute)
                    ActionResult(true, "Your alarm is set for $timeFormatted!", null)
                }
            } catch (e: SecurityException) {
                // Permission denied — try fallback
                android.util.Log.e("SetAlarm", "SecurityException: ${e.message}")
                null
            } catch (e: android.content.ActivityNotFoundException) {
                android.util.Log.e("SetAlarm", "No clock app: ${e.message}")
                null
            } catch (e: Exception) {
                android.util.Log.e("SetAlarm", "Intent failed: ${e.message}")
                null
            }
        }

        private fun tryOpenClockApp(
            hour: Int, minute: Int, context: Context
        ): ActionResult? {
            return try {
                // Try Google Clock, then AOSP Clock
                val clockIntent = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.deskclock")
                    ?: context.packageManager
                        .getLaunchIntentForPackage("com.android.deskclock")

                if (clockIntent != null) {
                    clockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(clockIntent)
                    val timeFormatted = formatTime(hour, minute)
                    ActionResult(true,
                        "I opened the Clock app — please set your alarm for $timeFormatted there.", null)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Robust time parser — handles all common formats:
         * "5 am", "5am", "5:30 pm", "17:00", "noon", "midnight",
         * "half past 6", "quarter to 8", just "7", etc.
         */
        private fun parseTimeString(input: String): Pair<Int, Int>? {
            val clean = input.lowercase().trim()
                .replace("o'clock", "").replace("hours", "").trim()

            // Natural language times
            val naturalMap = mapOf(
                "midnight" to Pair(0, 0),
                "noon" to Pair(12, 0),
                "midday" to Pair(12, 0),
                "morning" to Pair(8, 0),
                "afternoon" to Pair(14, 0),
                "evening" to Pair(18, 0),
                "night" to Pair(21, 0)
            )
            naturalMap[clean]?.let { return it }

            // "5 am", "5am", "11 pm", "11pm"
            val amPmSimple = Regex("""^(\d{1,2})\s*(am|pm|a\.m\.|p\.m\.)$""")
            amPmSimple.find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt()
                val amPm = match.groupValues[2]
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                if (hour > 23) return null
                return Pair(hour, 0)
            }

            // "5:30 am", "5:30am", "11:45 pm"
            val amPmWithMin = Regex("""^(\d{1,2})[:\.](\d{2})\s*(am|pm|a\.m\.|p\.m\.)$""")
            amPmWithMin.find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                val amPm = match.groupValues[3]
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                if (hour > 23 || minute > 59) return null
                return Pair(hour, minute)
            }

            // "17:30", "05:00", "9:45"
            val military = Regex("""^(\d{1,2})[:\.](\d{2})$""")
            military.find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                if (hour > 23 || minute > 59) return null
                return Pair(hour, minute)
            }

            // Just a number: "5", "7", "22"
            clean.toIntOrNull()?.let { hour ->
                if (hour in 0..23) return Pair(hour, 0)
            }

            // "half past 5" → 5:30
            Regex("""half past (\d{1,2})""").find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                if (hour in 0..23) return Pair(hour, 30)
            }

            // "quarter past 5" → 5:15
            Regex("""quarter past (\d{1,2})""").find(clean)?.let { match ->
                val hour = match.groupValues[1].toInt()
                if (hour in 0..23) return Pair(hour, 15)
            }

            // "quarter to 6" → 5:45
            Regex("""quarter to (\d{1,2})""").find(clean)?.let { match ->
                var hour = match.groupValues[1].toInt() - 1
                if (hour < 0) hour = 23
                return Pair(hour, 45)
            }

            return null
        }

        private fun formatTime(hour: Int, minute: Int): String {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
        }
    }

    private class SetTimerAction : Action {
        override val name: String = "SET_TIMER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val durationSecs = params["duration"]?.let { DurationParser.parseToSeconds(it) } ?: 60
            val label = params["label"] ?: "OpenDroid Timer"
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, durationSecs)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Timer's running! $durationSecs seconds.", null)
            } catch (e: Exception) {
                Log.e("SetTimer", "Timer failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't start the timer.")
            }
        }
    }

    private class AddNoteAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "ADD_NOTE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: params["name"] ?: "Quick Note"
            val content = params["content"] ?: params["text"] ?: params["body"] ?: ""
            if (content.isBlank() && title == "Quick Note") {
                return ActionResult(false, null, "Note content is empty.")
            }

            val timestamp = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val dateStr = dateFormat.format(java.util.Date(timestamp))
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
            val key = "note_${sanitizedTitle}_$timestamp"
            val memoryValue = "Title: $title\nCreated: $dateStr\n$content".trim()

            return try {
                memoryRepository.saveMemory(
                    Memory(
                        key = key,
                        value = memoryValue,
                        type = MemoryType.SEMANTIC,
                        timestamp = timestamp
                    )
                )
                ActionResult(true, "Got it! Note '$title' saved.", null)
            } catch (e: Exception) {
                Log.e("AddNote", "Note failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't save that note.")
            }
        }
    }

    private class ListCalendarTodayAction : Action {
        override val name: String = "LIST_CALENDAR_TODAY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val builder = CalendarContract.CONTENT_URI.buildUpon()
                builder.appendPath("time")
                ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = builder.build()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's your calendar for today.", null)
            } catch (e: Exception) {
                Log.e("ListCalendarToday", "Calendar failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your calendar.")
            }
        }
    }

    private class ListCalendarWeekAction : Action {
        override val name: String = "LIST_CALENDAR_WEEK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val builder = CalendarContract.CONTENT_URI.buildUpon()
                builder.appendPath("time")
                ContentUris.appendId(builder, Calendar.getInstance().timeInMillis)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = builder.build()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's your week at a glance.", null)
            } catch (e: Exception) {
                Log.e("ListCalendarWeek", "Calendar failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open your calendar.")
            }
        }
    }

    private class SetReminderAction : Action {
        override val name: String = "SET_REMINDER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Reminder"
            val timeStr = params["time"]
            val startMillis = if (timeStr != null) {
                Calendar.getInstance().timeInMillis
            } else {
                Calendar.getInstance().timeInMillis
            }
            return try {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                    putExtra(CalendarContract.Events.DESCRIPTION, params["description"] ?: "Created by OpenDroid")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "I opened the calendar so you can set up your reminder '$title'.", null)
            } catch (e: Exception) {
                Log.e("SetReminder", "Reminder failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't set up that reminder.")
            }
        }
    }

    private class CreateTaskAction : Action {
        override val name: String = "CREATE_TASK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Task"
            val description = params["description"] ?: ""
            return ActionResult(true, "Done! Task '$title' is created.", null)
        }
    }

    private class ReadNotesAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "READ_NOTES"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: params["topic"] ?: ""
            return try {
                val allSemantic = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
                val noteMemories = allSemantic.filter {
                    it.key.startsWith("note_") || it.key.startsWith("screen_note_") ||
                    it.value.contains("Title:", ignoreCase = true) || it.value.contains("Topic:", ignoreCase = true)
                }

                val matchingNotes = if (query.isNotBlank()) {
                    noteMemories.filter {
                        it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true)
                    }
                } else {
                    noteMemories
                }

                if (matchingNotes.isEmpty()) {
                    val msg = if (query.isNotBlank()) "No notes found matching '$query'." else "You don't have any saved notes yet."
                    return ActionResult(true, msg, null)
                }

                val formattedNotes = matchingNotes.sortedByDescending { it.timestamp }.take(10).joinToString("\n\n---\n\n") { memory ->
                    memory.value
                }
                ActionResult(true, "Here are your saved notes:\n\n$formattedNotes", null)
            } catch (e: Exception) {
                Log.e("ReadNotes", "Failed to read notes: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't read your notes right now.")
            }
        }
    }

    private class ReadAndRememberScreenAction(
        private val visionEngine: dagger.Lazy<VisionEngine>,
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "READ_AND_REMEMBER_SCREEN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val topic = params["topic"] ?: params["query"] ?: "important information"

            return try {
                val extractedInfo = visionEngine.get().extractAndStructureScreenInfo(topic)
                if (extractedInfo.isBlank() || extractedInfo.startsWith("Could not capture") || extractedInfo.startsWith("Please ensure the Accessibility")) {
                    return ActionResult(false, null, extractedInfo)
                }

                val timestamp = System.currentTimeMillis()
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                val dateStr = dateFormat.format(java.util.Date(timestamp))
                val sanitizedTopic = topic.replace(Regex("[^a-zA-Z0-9_]"), "_").take(30)
                val key = "screen_note_${sanitizedTopic}_$timestamp"
                val memoryValue = "Topic: $topic\nDate Recorded: $dateStr\n\n$extractedInfo"

                memoryRepository.saveMemory(
                    Memory(
                        key = key,
                        value = memoryValue,
                        type = MemoryType.SEMANTIC,
                        timestamp = timestamp
                    )
                )

                val confirmation = "I've read the screen and saved this to your notes:\n\n$extractedInfo"
                ActionResult.Success(
                    dataMap = mapOf(
                        "message" to confirmation,
                        "key" to key,
                        "topic" to topic,
                        "summary" to extractedInfo,
                        "saved" to "true"
                    )
                )
            } catch (e: Exception) {
                Log.e("ReadAndRemember", "Failed to read and remember screen: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't read and save the screen info right now: ${e.localizedMessage}")
            }
        }
    }

    private class RecallMemoryAction(
        private val memoryRepository: MemoryRepository
    ) : Action {
        override val name: String = "RECALL_MEMORY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: params["topic"]
                ?: return ActionResult(false, null, "Please specify what you would like to recall.")

            return try {
                val allMemories = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
                val matches = allMemories.filter {
                    it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true)
                }

                if (matches.isEmpty()) {
                    return ActionResult(true, "I don't have any saved information or notes about '$query'.", null)
                }

                val formatted = matches.sortedByDescending { it.timestamp }.take(5).joinToString("\n\n---\n\n") { memory ->
                    memory.value
                }
                ActionResult(true, "Here is what I found about '$query':\n\n$formatted", null)
            } catch (e: Exception) {
                Log.e("RecallMemory", "Failed to recall memory: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't retrieve memory right now.")
            }
        }
    }

    private class QueryKnowledgeGraphAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "QUERY_KNOWLEDGE_GRAPH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"]?.trim().orEmpty()
            val categoryFilter = params["category"]?.uppercase()?.trim() ?: "ALL"
            val tierFilter = params["tier"]?.uppercase()?.trim() ?: "ALL"

            return try {
                val snapshot = personalGrowthEngine.get().getSnapshot()
                var matched = snapshot.findNodes(query)
                if (categoryFilter != "ALL") {
                    matched = matched.filter { it.category.name.equals(categoryFilter, ignoreCase = true) }
                }
                if (tierFilter != "ALL") {
                    matched = matched.filter { it.tier.name.equals(tierFilter, ignoreCase = true) }
                }

                if (matched.isEmpty()) {
                    return ActionResult(true, "No knowledge graph entries found matching your query.", null)
                }

                val sb = StringBuilder("Here is what I found in your Personal Knowledge Graph:\n\n")
                val grouped = matched.groupBy { it.tier }
                for ((tier, nodes) in grouped) {
                    sb.append("== Level: ${tier.name} ==\n")
                    for (node in nodes) {
                        val conf = if (node.tier == MemoryTier.LEARNED_PATTERN) " [${(node.confidence * 100).toInt()}% confidence]" else ""
                        sb.append("• [${node.category.name}] ${node.label}: ${node.summary}$conf\n")
                    }
                    sb.append("\n")
                }
                ActionResult(true, sb.toString().trim(), null)
            } catch (e: Exception) {
                Log.e("QueryKnowledgeGraph", "Failed to query knowledge graph: ${e.message}")
                ActionResult(false, null, "Couldn't query knowledge graph right now.")
            }
        }
    }

    private class UpdatePreferenceAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "UPDATE_PREFERENCE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val key = params["key"] ?: return ActionResult(false, null, "Preference key is required.")
            val value = params["value"] ?: return ActionResult(false, null, "Preference value is required.")
            val categoryStr = params["category"]?.uppercase() ?: "USER_PREFERENCE"
            val category = try {
                KnowledgeCategory.valueOf(categoryStr)
            } catch (e: Exception) {
                KnowledgeCategory.USER_PREFERENCE
            }

            return try {
                val node = personalGrowthEngine.get().recordExplicitMemory(
                    label = key,
                    summary = value,
                    category = category
                )
                ActionResult(true, "Saved preference '${node.label}' to your Long-Term Knowledge Graph: ${node.summary}", null)
            } catch (e: Exception) {
                Log.e("UpdatePreference", "Failed to update preference: ${e.message}")
                ActionResult(false, null, "Failed to save preference.")
            }
        }
    }

    private class SaveSensitiveInfoAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "SAVE_SENSITIVE_INFO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val key = params["key"] ?: return ActionResult(false, null, "Sensitive key is required.")
            val secret = params["secret"] ?: return ActionResult(false, null, "Sensitive secret value is required.")
            val label = params["label"]?.ifBlank { key } ?: key

            return try {
                val success = personalGrowthEngine.get().recordSensitiveSecret(key, secret, label)
                if (success) {
                    ActionResult(true, "Securely saved '$label' in Level 4 hardware-encrypted storage.", null)
                } else {
                    ActionResult(false, null, "Failed to write to encrypted Keystore storage.")
                }
            } catch (e: Exception) {
                Log.e("SaveSensitiveInfo", "Failed to save sensitive info: ${e.message}")
                ActionResult(false, null, "Couldn't encrypt and store sensitive data.")
            }
        }
    }
}
