package com.opendroid.ai.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportExporterTest {

    private val record = CrashLogRecord(
        timestamp = 1_700_000_000_000L,
        exceptionClass = "java.lang.IllegalStateException",
        message = "boom",
        threadName = "main",
        stackTrace = "java.lang.IllegalStateException: boom\n\tat com.opendroid.ai.Thing.go(Thing.kt:12)",
        appVersionName = "1.2.3",
        appVersionCode = 42L,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8"
    )

    private val fixedTime: (Long) -> String = { "2023-11-14 22:13:20" }

    private fun export(vararg records: CrashLogRecord) =
        CrashReportExporter.export(records.toList(), fixedTime)

    @Test
    fun `includes the crash summary`() {
        val text = export(record)

        assertTrue(text, text.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `includes the formatted timestamp`() {
        val text = export(record)

        assertTrue(text, text.contains("2023-11-14 22:13:20"))
    }

    @Test
    fun `includes app version name and code`() {
        val text = export(record)

        assertTrue(text, text.contains("1.2.3"))
        assertTrue(text, text.contains("42"))
    }

    @Test
    fun `includes the android release and sdk level`() {
        val text = export(record)

        assertTrue(text, text.contains("14"))
        assertTrue(text, text.contains("34"))
    }

    @Test
    fun `includes the device manufacturer and model`() {
        val text = export(record)

        assertTrue(text, text.contains("Google"))
        assertTrue(text, text.contains("Pixel 8"))
    }

    @Test
    fun `includes the crashing thread`() {
        val text = export(record)

        assertTrue(text, text.contains("main"))
    }

    @Test
    fun `includes the stack trace`() {
        val text = export(record)

        assertTrue(text, text.contains("at com.opendroid.ai.Thing.go(Thing.kt:12)"))
    }

    @Test
    fun `exports every record`() {
        val other = record.copy(exceptionClass = "java.lang.NullPointerException", message = null)

        val text = export(record, other)

        assertTrue(text, text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text, text.contains("java.lang.NullPointerException"))
    }

    @Test
    fun `separates multiple records`() {
        val text = export(record, record)

        assertEquals(2, text.split(CrashReportExporter.RECORD_SEPARATOR).size)
    }

    @Test
    fun `an empty log exports a readable placeholder rather than an empty string`() {
        val text = export()

        assertEquals(CrashReportExporter.EMPTY_LOG_TEXT, text)
    }

    @Test
    fun `a record with no message still exports its exception class`() {
        val text = export(record.copy(message = null))

        assertTrue(text, text.contains("java.lang.IllegalStateException"))
    }

    // --- Aggregate share budget ----------------------------------------------

    private fun bulky(index: Int) = record.copy(
        message = "crash $index",
        stackTrace = "frame-$index " + "x".repeat(CrashReportFormatter.MAX_STACK_TRACE_CHARS)
    )

    @Test
    fun `a full log stays well under the binder transaction limit`() {
        val records = (1..CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES).map { bulky(it) }

        val text = CrashReportExporter.export(records, fixedTime)

        assertTrue(
            "export was ${text.length} chars",
            text.length < CrashReportExporter.MAX_EXPORT_CHARS * 2
        )
    }

    @Test
    fun `the newest records are the ones kept when the budget runs out`() {
        val records = (1..CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES).map { bulky(it) }

        val text = CrashReportExporter.export(records, fixedTime)

        assertTrue(text, text.contains("frame-1 "))
        assertTrue(text, !text.contains("frame-${CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES} "))
    }

    @Test
    fun `an over-budget export says how many records it dropped`() {
        val records = (1..CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES).map { bulky(it) }

        val text = CrashReportExporter.export(records, fixedTime)

        assertTrue(text, text.contains("omitted to keep"))
        assertTrue(text, text.contains("older crash reports"))
    }

    @Test
    fun `a log that fits is exported whole with no omission notice`() {
        val text = export(record, record.copy(message = "second"))

        assertTrue(text, text.contains("boom"))
        assertTrue(text, text.contains("second"))
        assertTrue(text, !text.contains("omitted to keep"))
    }

    @Test
    fun `a single record larger than the whole budget is truncated rather than dropped`() {
        val text = CrashReportExporter.export(listOf(bulky(1)), fixedTime, maxChars = 500)

        assertTrue(text, text.contains("frame-1 "))
        assertTrue("export was ${text.length} chars", text.length < 800)
    }
}
