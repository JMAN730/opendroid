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

    val defaultTimeFormatter: (Long) -> String = { timestamp ->
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
    }

    fun export(
        records: List<CrashLogRecord>,
        formatTimestamp: (Long) -> String = defaultTimeFormatter
    ): String {
        if (records.isEmpty()) return EMPTY_LOG_TEXT
        return records.joinToString(RECORD_SEPARATOR) { exportOne(it, formatTimestamp) }
    }

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
