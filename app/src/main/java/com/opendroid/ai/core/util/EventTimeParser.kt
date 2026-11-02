package com.opendroid.ai.core.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Parses the date and time strings the planner sends to calendar actions.
 * Anything it cannot read is null, so callers never fall back to a guessed time.
 */
object EventTimeParser {

    /** A resolved start. [hasTime] is false when only a date was given; [begin] is then local midnight. */
    data class EventMoment(val begin: ZonedDateTime, val hasTime: Boolean)

    /** A recognised repeat schedule. [rule] is null for "none" and similar words. */
    data class RepeatRule(val rule: String?)

    private val FILLER_WORDS = setOf("at", "on", "by", "this", "the", "of")
    private val NO_REPEAT_WORDS = setOf("none", "no", "never", "once", "no repeat", "does not repeat")
    private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val MONTH_FIRST = Regex("""^([a-z]{3,})\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s+(\d{4}))?$""")
    private val DAY_FIRST = Regex("""^(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,})(?:\s+(\d{4}))?$""")
    private const val DURATION_PART =
        """\d+(?:\.\d+)?\s*(?:hours|hour|hrs|hr|h|minutes|minute|mins|min|m|seconds|second|secs|sec|s)"""
    private val RELATIVE_DURATION = Regex("""^$DURATION_PART(?:\s*(?:and\s+)?$DURATION_PART)*$""")

    fun parseMoment(input: String, now: ZonedDateTime): EventMoment? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        runCatching { OffsetDateTime.parse(raw) }.getOrNull()?.let {
            return EventMoment(it.atZoneSameInstant(now.zone), hasTime = true)
        }

        val words = raw.lowercase()
            .replace(",", " ")
            .replace(Regex("""(?<=\d)t(?=\d)"""), " ")
            .replace(Regex("""(\d{1,2}:\d{2}):\d{2}(?:\.\d+)?"""), "$1")
            .replace("tonight", "today night")
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && it !in FILLER_WORDS }
        val clean = words.joinToString(" ")

        Regex("""^in\s+(\d.*[a-z])$""").find(clean)?.let { match ->
            // DurationParser skips words it cannot read, so "1 day and 2 hours" would come back as two hours.
            if (!RELATIVE_DURATION.matches(match.groupValues[1])) return null
            val seconds = DurationParser.parseToSeconds(match.groupValues[1]) ?: return null
            return EventMoment(now.plusSeconds(seconds.toLong()), hasTime = true)
        }

        val today = now.toLocalDate()
        parseDate(clean, today)?.let { return dateOnly(it, now) }

        // Date before time wins so "2 november 8" reads as 8:00 on the 2nd.
        for (split in 1 until words.size) {
            val first = words.take(split).joinToString(" ")
            val second = words.drop(split).joinToString(" ")
            combine(parseDate(first, today), parseClockTime(second), now)?.let { return it }
        }
        for (split in 1 until words.size) {
            val first = words.take(split).joinToString(" ")
            val second = words.drop(split).joinToString(" ")
            combine(parseDate(second, today), parseClockTime(first), now)?.let { return it }
        }

        return parseClockTime(clean)?.let { (hour, minute) ->
            val time = LocalTime.of(hour, minute)
            val date = if (time.isAfter(now.toLocalTime())) today else today.plusDays(1)
            EventMoment(date.atTime(time).atZone(now.zone), hasTime = true)
        }
    }

    /**
     * Robust time parser — handles all common formats:
     * "5 am", "5am", "5:30 pm", "17:00", "noon", "midnight",
     * "half past 6", "quarter to 8", just "7", etc.
     */
    fun parseClockTime(input: String): Pair<Int, Int>? {
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

        // "half past 5" → 5:30, "quarter past 5 pm" → 17:15, "quarter to 8 pm" → 19:45.
        // Anchored so an unconsumed qualifier ("tonight") rejects rather than being dropped.
        val relative = Regex("""^(half past|quarter past|quarter to) (\d{1,2})(?:\s*(am|pm|a\.m\.|p\.m\.))?$""")
        relative.find(clean)?.let { match ->
            var hour = match.groupValues[2].toInt()
            val amPm = match.groupValues[3]
            if (amPm.isNotEmpty()) {
                if (hour !in 1..12) return null
                val isPm = amPm.startsWith("p")
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
            }
            if (hour > 23) return null
            return when (match.groupValues[1]) {
                "half past" -> Pair(hour, 30)
                "quarter past" -> Pair(hour, 15)
                else -> Pair(if (hour == 0) 23 else hour - 1, 45)
            }
        }

        return null
    }

    /** Maps a repeat schedule to an iCalendar RRULE. Null means the schedule was not understood. */
    fun parseRepeatRule(input: String): RepeatRule? {
        val clean = input.trim().lowercase()
        if (clean in NO_REPEAT_WORDS) return RepeatRule(null)
        return when (clean) {
            "daily", "every day", "everyday" -> RepeatRule("FREQ=DAILY")
            "weekly", "every week" -> RepeatRule("FREQ=WEEKLY")
            "monthly", "every month" -> RepeatRule("FREQ=MONTHLY")
            "yearly", "annually", "every year" -> RepeatRule("FREQ=YEARLY")
            "weekdays", "every weekday" -> RepeatRule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
            else -> parseWeekday(clean.removePrefix("every ").removeSuffix("s"))
                ?.let { RepeatRule("FREQ=WEEKLY;BYDAY=${it.name.take(2)}") }
        }
    }

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        when (text) {
            "today" -> return today
            "tomorrow" -> return today.plusDays(1)
            "day after tomorrow" -> return today.plusDays(2)
        }
        if (ISO_DATE.matches(text)) return runCatching { LocalDate.parse(text) }.getOrNull()

        parseWeekday(text)?.let { return today.with(TemporalAdjusters.nextOrSame(it)) }
        if (text.startsWith("next ")) {
            parseWeekday(text.removePrefix("next "))?.let { return today.with(TemporalAdjusters.next(it)) }
        }

        MONTH_FIRST.find(text)?.let { match ->
            val (month, day, year) = match.destructured
            return monthDay(month, day, year, today)
        }
        DAY_FIRST.find(text)?.let { match ->
            val (day, month, year) = match.destructured
            return monthDay(month, day, year, today)
        }
        return null
    }

    private fun monthDay(monthWord: String, day: String, year: String, today: LocalDate): LocalDate? {
        val month = Month.values().firstOrNull { it.name.lowercase().startsWith(monthWord) } ?: return null
        return runCatching {
            if (year.isNotEmpty()) {
                LocalDate.of(year.toInt(), month, day.toInt())
            } else {
                val candidate = LocalDate.of(today.year, month, day.toInt())
                if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
            }
        }.getOrNull()
    }

    private fun parseWeekday(text: String): DayOfWeek? =
        DayOfWeek.values().firstOrNull { it.name.lowercase() == text }

    private fun combine(date: LocalDate?, time: Pair<Int, Int>?, now: ZonedDateTime): EventMoment? {
        if (date == null || time == null) return null
        return EventMoment(date.atTime(time.first, time.second).atZone(now.zone), hasTime = true)
    }

    private fun dateOnly(date: LocalDate, now: ZonedDateTime) =
        EventMoment(date.atStartOfDay(now.zone), hasTime = false)
}
