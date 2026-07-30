package com.opendroid.ai.core.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders crash records as the plain text a user shares into a bug report.
 *
 * Timestamp formatting is injected so the output is deterministic under test -
 * a [SimpleDateFormat] built here would render differently per device timezone.
 */
object CrashReportExporter {

    const val RECORD_SEPARATOR = "\n\n----------------------------------------\n\n"
    const val EMPTY_LOG_TEXT = "No crashes recorded."

    /**
     * Budget for an aggregate export, in characters.
     *
     * "Share all" hands its result to `Intent.EXTRA_TEXT`, which is marshalled
     * through a Binder transaction buffer of roughly 1 MB that is shared with
     * every other transaction in flight. The stored log holds up to
     * [CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES] records, each carrying a
     * stack trace of up to [CrashReportFormatter.MAX_STACK_TRACE_CHARS]
     * characters, so an unbudgeted export can approach 800,000 characters and
     * throw `TransactionTooLargeException` instead of opening the chooser.
     *
     * Records arrive newest-first, so spending the budget from the front keeps
     * the crashes most likely to be the ones being reported.
     */
    const val MAX_EXPORT_CHARS = 120_000

    val defaultTimeFormatter: (Long) -> String = { timestamp ->
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
    }

    /**
     * Renders the newest records that fit inside [maxChars] and states how many
     * were left out, rather than returning a payload the share intent cannot
     * carry. The omission notice is appended on top of the budget so that a
     * caller sizing against a hard limit should pass a slightly smaller figure.
     */
    fun export(
        records: List<CrashLogRecord>,
        formatTimestamp: (Long) -> String = defaultTimeFormatter,
        maxChars: Int = MAX_EXPORT_CHARS
    ): String {
        require(maxChars > 0) { "maxChars must be positive, was $maxChars" }
        if (records.isEmpty()) return EMPTY_LOG_TEXT

        val included = mutableListOf<String>()
        var used = 0
        for (record in records) {
            val rendered = exportOne(record, formatTimestamp)
            val cost = rendered.length + if (included.isEmpty()) 0 else RECORD_SEPARATOR.length
            // The newest record is always carried, truncated if it alone
            // overruns the budget - an export with no crash in it is useless.
            if (included.isEmpty()) {
                included += CrashReportFormatter.truncate(rendered, maxChars)
                used = minOf(rendered.length, maxChars)
                continue
            }
            if (used + cost > maxChars) break
            included += rendered
            used += cost
        }

        val omitted = records.size - included.size
        val body = included.joinToString(RECORD_SEPARATOR)
        if (omitted == 0) return body
        return body + RECORD_SEPARATOR + omissionNotice(omitted, maxChars)
    }

    internal fun omissionNotice(omitted: Int, maxChars: Int): String =
        "[$omitted older crash ${if (omitted == 1) "report" else "reports"} omitted to keep " +
            "this export under $maxChars characters. Share a single report to see one in full.]"

    fun exportOne(
        record: CrashLogRecord,
        formatTimestamp: (Long) -> String = defaultTimeFormatter
    ): String = buildString {
        appendLine("Crash:   ${record.summary}")
        appendLine("Time:    ${formatTimestamp(record.timestamp)}")
        appendLine("App:     ${record.appVersionName} (${record.appVersionCode})")
        appendLine("Android: ${record.androidRelease} (SDK ${record.androidSdkInt})")
        appendLine("Device:  ${record.deviceManufacturer} ${record.deviceModel}")
        appendLine("Thread:  ${record.threadName}")
        appendLine()
        append(record.stackTrace)
    }
}
