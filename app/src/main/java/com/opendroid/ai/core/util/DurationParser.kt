package com.opendroid.ai.core.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parses natural-language and numeric duration strings into seconds.
 * Examples: "5", "5s", "5 seconds", "2 minute", "1.5 hours", "2m59.56s"
 * Decimal unit amounts are summed before rounding to the nearest second (ties round up).
 * Results are clamped to at least one second and at most [Int.MAX_VALUE] seconds.
 */
object DurationParser {

    fun parseToSeconds(input: String): Int? {
        val normalized = input.trim().lowercase()
        if (normalized.isEmpty()) return null

        normalized.toIntOrNull()?.let { return it.coerceAtLeast(1) }

        var totalSeconds = BigDecimal.ZERO
        var matched = false

        // Allow compact forms like "1h30m" by treating another digit as a valid unit boundary.
        val hourPattern = Regex("""(\d+(?:\.\d+)?)\s*(?:hours|hour|hrs|hr|h)(?=\d|\b)""")
        val minutePattern = Regex("""(\d+(?:\.\d+)?)\s*(?:minutes|minute|mins|min|m)(?=\d|\b)""")
        val secondPattern = Regex("""(\d+(?:\.\d+)?)\s*(?:seconds|second|secs|sec|s)(?=\d|\b)""")

        hourPattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toBigDecimal() * 3600.toBigDecimal()
            matched = true
        }
        minutePattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toBigDecimal() * 60.toBigDecimal()
            matched = true
        }
        secondPattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toBigDecimal()
            matched = true
        }

        if (matched) {
            return totalSeconds.setScale(0, RoundingMode.HALF_UP)
                .min(Int.MAX_VALUE.toBigDecimal()).toInt().coerceAtLeast(1)
        }

        // Only accept a bare number — avoid hijacking unrelated digits (e.g. "2pm").
        Regex("""^(\d+)$""").find(normalized)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceAtLeast(1)
        }

        return null
    }
}
