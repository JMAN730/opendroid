package com.opendroid.ai.core.util

/**
 * Parses an alarm time string into (hour, minute). Shared by SET_ALARM and the
 * alarm shortcut, so the shortcut only fires for times SET_ALARM can actually set.
 */
object AlarmTimeParser {

    /**
     * Robust time parser — handles all common formats:
     * "5 am", "5am", "5:30 pm", "17:00", "noon", "midnight",
     * "half past 6", "quarter to 8", just "7", etc.
     */
    fun parse(input: String): Pair<Int, Int>? {
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
}
