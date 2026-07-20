package com.opendroid.ai.core.util

/**
 * Parses natural-language and numeric duration strings into seconds.
 * Examples: "5", "5s", "5 seconds", "2 minute", "1 hour", "5 minutes 30 seconds"
 */
object DurationParser {

    fun parseToSeconds(input: String): Int? {
        val normalized = input.trim().lowercase()
        if (normalized.isEmpty()) return null

        normalized.toIntOrNull()?.let { return it.coerceAtLeast(1) }

        var totalSeconds = 0
        var matched = false

        // Allow compact forms like "1h30m" by treating another digit as a valid unit boundary.
        val hourPattern = Regex("""(\d+)\s*(?:hours|hour|hrs|hr|h)(?=\d|\b)""")
        val minutePattern = Regex("""(\d+)\s*(?:minutes|minute|mins|min|m)(?=\d|\b)""")
        val secondPattern = Regex("""(\d+)\s*(?:seconds|second|secs|sec|s)(?=\d|\b)""")

        hourPattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toInt() * 3600
            matched = true
        }
        minutePattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toInt() * 60
            matched = true
        }
        secondPattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toInt()
            matched = true
        }

        if (matched) return totalSeconds.coerceAtLeast(1)

        // Only accept a bare number — avoid hijacking unrelated digits (e.g. "2pm").
        Regex("""^(\d+)$""").find(normalized)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceAtLeast(1)
        }

        return null
    }
}
