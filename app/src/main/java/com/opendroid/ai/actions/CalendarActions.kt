package com.opendroid.ai.actions

import com.opendroid.ai.core.util.DurationParser
import com.opendroid.ai.core.util.EventTimeParser
import com.opendroid.ai.core.util.EventTimeParser.EventMoment

import android.content.ContentUris
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
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import com.opendroid.ai.core.memory.ExecutionHistoryPrivacy
import com.opendroid.ai.core.memory.graph.KnowledgeCategory
import com.opendroid.ai.core.memory.graph.MemoryTier
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
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

    private companion object {
        const val DEFAULT_EVENT_SECONDS = 60 * 60

        /**
         * Reads [text] as a start time. [dateOnly] is retried alone so a date param that
         * already holds a time ("2026-11-02 15:00") isn't lost to a repeated time param.
         */
        fun parseEventMoment(dateOnly: String?, text: String, clock: Clock): EventMoment? {
            val now = ZonedDateTime.now(clock)
            return EventTimeParser.parseMoment(text, now)
                ?: dateOnly?.let { EventTimeParser.parseMoment(it, now) }?.takeIf { it.hasTime }
        }

        fun unreadTimeNote(text: String, moment: EventMoment?): String? =
            if (text.isNotBlank() && moment == null) {
                "I couldn't read the time '$text', so set it in the editor."
            } else {
                null
            }

        /** Writing to the calendar directly needs WRITE_CALENDAR, which the app deliberately doesn't declare. */
        fun calendarInsertIntent(
            title: String,
            description: String,
            moment: EventMoment?,
            durationSeconds: Int
        ): Intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            if (moment != null) {
                // Calendar editors read all-day times as UTC, so local midnight east of UTC would land on the day before.
                val begin = if (moment.hasTime) moment.begin else moment.begin.toLocalDate().atStartOfDay(ZoneOffset.UTC)
                val end = if (moment.hasTime) {
                    begin.plusSeconds(durationSeconds.toLong())
                } else {
                    begin.plusDays(1)
                }
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.toInstant().toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toInstant().toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, !moment.hasTime)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** The editor only opens a draft: nothing is saved until the user confirms, so this is never success. */
        fun openCalendarEditor(
            context: Context,
            intent: Intent,
            message: String,
            notes: List<String>
        ): ActionResult = try {
            context.startActivity(intent)
            ActionResult.UserActionRequired((listOf(message) + notes).joinToString(" "))
        } catch (e: Exception) {
            Log.e("CalendarActions", "Calendar editor failed: ${e.localizedMessage}")
            ActionResult(false, null, "Couldn't open the calendar. Is a calendar app installed?")
        }
    }

    internal class CreateCalendarEventAction(private val clock: Clock = Clock.systemDefaultZone()) : Action {
        override val name: String = "CREATE_CALENDAR_EVENT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Event"
            val dateTimeText = listOfNotNull(params["date"], params["time"])
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val moment = parseEventMoment(params["date"], dateTimeText, clock)
            val durationText = params["duration"]?.takeIf { it.isNotBlank() }
            val durationSeconds = durationText?.let { EventTimeParser.parseDurationSeconds(it) }

            val intent = calendarInsertIntent(
                title = title,
                description = params["description"] ?: "Created by OpenDroid",
                moment = moment,
                durationSeconds = durationSeconds ?: DEFAULT_EVENT_SECONDS
            ).apply {
                params["location"]?.takeIf { it.isNotBlank() }?.let {
                    putExtra(CalendarContract.Events.EVENT_LOCATION, it)
                }
                params["attendees"]?.takeIf { it.isNotBlank() }?.let { attendees ->
                    // The calendar insert intent takes EXTRA_EMAIL as one comma-separated string.
                    putExtra(
                        Intent.EXTRA_EMAIL,
                        attendees.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                    )
                }
            }

            val notes = listOfNotNull(
                unreadTimeNote(dateTimeText, moment),
                durationText?.takeIf { durationSeconds == null && moment?.hasTime == true }
                    ?.let { "I couldn't read the duration '$it', so I used one hour." }
            )
            return openCalendarEditor(context, intent, "I opened the calendar with '$title'; please review it and save to add the event.", notes)
        }
    }

    internal class SetAlarmAction : Action {
        override val name: String = "SET_ALARM"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val timeStr = params["time"]
                ?: return ActionResult(false, null, "Time is required. Use format like '5 am' or '7:30'")

            val label = params["label"]?.trim() ?: "OpenDroid Alarm"

            // Parse time string to hour + minute
            val parsed = EventTimeParser.parseClockTime(timeStr)
                ?: return ActionResult(false, null,
                    "Could not understand time '$timeStr'. Try formats like '5 am', '7:30', or '14:00'")

            val (hour, minute) = parsed

            val repeat = params["repeat"].orEmpty()
            val days = EventTimeParser.parseAlarmDays(repeat)
                ?: return ActionResult(false, null,
                    "I couldn't read the repeat schedule '$repeat', so no alarm was set. Please use daily, weekdays, weekends, or day names.")

            // Try Method 1: AlarmClock Intent (most reliable)
            val method1 = tryAlarmClockIntent(hour, minute, days, label, context)
            if (method1 != null) return method1

            // Try Method 2: Open clock app as fallback
            val method2 = tryOpenClockApp(hour, minute, days, context)
            if (method2 != null) return method2

            // All methods failed
            val timeFormatted = formatTime(hour, minute, days)
            return ActionResult(false, null,
                "Could not set alarm for $timeFormatted. Please open Clock app manually.")
        }

        private fun tryAlarmClockIntent(
            hour: Int, minute: Int, days: Set<DayOfWeek>, label: String, context: Context
        ): ActionResult? {
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (days.isNotEmpty()) {
                        // EXTRA_DAYS takes Calendar day constants, where Sunday is 1.
                        putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it.value % 7 + Calendar.SUNDAY }))
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Check if any app can handle this intent
                val resolved = context.packageManager
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)

                if (resolved == null) {
                    null // no clock app can handle it
                } else {
                    context.startActivity(intent)
                    val timeFormatted = formatTime(hour, minute, days)
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
            hour: Int, minute: Int, days: Set<DayOfWeek>, context: Context
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
                    val timeFormatted = formatTime(hour, minute, days)
                    ActionResult(true,
                        "I opened the Clock app — please set your alarm for $timeFormatted there.", null)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun formatTime(hour: Int, minute: Int, days: Set<DayOfWeek>): String {
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val schedule = if (days.isEmpty()) {
                ""
            } else {
                " every " + days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
            }
            return "$displayHour:${minute.toString().padStart(2, '0')} $amPm$schedule"
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

    internal class SetReminderAction(private val clock: Clock = Clock.systemDefaultZone()) : Action {
        override val name: String = "SET_REMINDER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = (params["text"] ?: params["title"])?.takeIf { it.isNotBlank() } ?: "New Reminder"
            val dateTimeText = (params["datetime"] ?: params["time"]).orEmpty()
            val moment = parseEventMoment(null, dateTimeText, clock)
            val repeatText = params["repeat"]?.takeIf { it.isNotBlank() }
            val repeat = repeatText?.let { EventTimeParser.parseRepeatRule(it) }

            val intent = calendarInsertIntent(
                title = title,
                description = params["description"] ?: "Created by OpenDroid",
                moment = moment,
                durationSeconds = DEFAULT_EVENT_SECONDS
            ).apply {
                repeat?.rule?.let { putExtra(CalendarContract.Events.RRULE, it) }
            }

            val notes = listOfNotNull(
                unreadTimeNote(dateTimeText, moment),
                repeatText?.takeIf { repeat == null }
                    ?.let { "I couldn't read the repeat schedule '$it', so set it in the editor." }
            )
            return openCalendarEditor(context, intent, "I opened the calendar with your reminder '$title'; please review it and save to set it.", notes)
        }
    }

    internal class CreateTaskAction(private val clock: Clock = Clock.systemDefaultZone()) : Action {
        override val name: String = "CREATE_TASK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val title = params["title"] ?: "New Task"
            val dueText = params["dueDate"].orEmpty()
            val moment = parseEventMoment(null, dueText, clock)
            val description = listOfNotNull(
                params["description"],
                params["priority"]?.let { "Priority: $it" },
                params["list"]?.let { "List: $it" }
            ).filter { it.isNotBlank() }.joinToString("\n").ifEmpty { "Created by OpenDroid" }

            // There is no task store in the app, so the task lives in the calendar once the user saves it.
            val intent = calendarInsertIntent(title, description, moment, DEFAULT_EVENT_SECONDS)
            return openCalendarEditor(
                context,
                intent,
                "I opened the calendar with the task '$title'; please review it and save to add it.",
                listOfNotNull(unreadTimeNote(dueText, moment))
            )
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

    internal class SaveSensitiveInfoAction(
        private val personalGrowthEngine: dagger.Lazy<PersonalGrowthEngine>
    ) : Action {
        override val name: String = "SAVE_SENSITIVE_INFO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val key = params["key"] ?: return ActionResult(false, null, "Sensitive key is required.")
            val secret = params["secret"] ?: return ActionResult(false, null, "Sensitive secret value is required.")
            // A plan reloaded from history or replayed from a macro only has the placeholder,
            // and storing that would overwrite the real secret under this key.
            if (secret == ExecutionHistoryPrivacy.REDACTED) {
                return ActionResult(false, null, "This secret isn't kept in saved plans. Please provide it again.")
            }
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
