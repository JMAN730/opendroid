package com.opendroid.ai.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogRecorderTest {

    private val metadata = DeviceMetadata(
        appVersionName = "1.2.3",
        appVersionCode = 42L,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8"
    )

    private class FakeSink : CrashLogSink {
        val recorded = mutableListOf<CrashLogRecord>()
        val pruneCalls = mutableListOf<Int>()
        var failOnRecord: Throwable? = null
        var failOnPrune: Throwable? = null

        override fun record(record: CrashLogRecord) {
            failOnRecord?.let { throw it }
            recorded += record
        }

        override fun prune(keepMostRecent: Int) {
            failOnPrune?.let { throw it }
            pruneCalls += keepMostRecent
        }
    }

    private val reportedFailures = mutableListOf<Throwable>()

    private fun recorder(
        sink: CrashLogSink,
        maxStoredCrashes: Int = CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES,
        now: Long = 1_700_000_000_000L
    ) = CrashLogRecorder(
        sink = sink,
        metadata = metadata,
        maxStoredCrashes = maxStoredCrashes,
        clock = { now },
        onRecordingFailed = { reportedFailures += it }
    )

    @Test
    fun `records the exception class and message`() {
        val sink = FakeSink()

        recorder(sink).record(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(1, sink.recorded.size)
        assertEquals("java.lang.IllegalStateException", sink.recorded[0].exceptionClass)
        assertEquals("boom", sink.recorded[0].message)
    }

    @Test
    fun `records a null message when the throwable has none`() {
        val sink = FakeSink()

        recorder(sink).record(Thread.currentThread(), RuntimeException())

        assertNull(sink.recorded[0].message)
    }

    @Test
    fun `records the stack trace`() {
        val sink = FakeSink()

        recorder(sink).record(Thread.currentThread(), IllegalStateException("boom"))

        assertTrue(sink.recorded[0].stackTrace.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `records the crashing thread name`() {
        val sink = FakeSink()
        val thread = Thread(null, {}, "worker-7")

        recorder(sink).record(thread, RuntimeException("boom"))

        assertEquals("worker-7", sink.recorded[0].threadName)
    }

    @Test
    fun `records the supplied timestamp rather than reading the wall clock`() {
        val sink = FakeSink()

        recorder(sink, now = 999L).record(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(999L, sink.recorded[0].timestamp)
    }

    @Test
    fun `records the app and device metadata`() {
        val sink = FakeSink()

        recorder(sink).record(Thread.currentThread(), RuntimeException("boom"))

        val record = sink.recorded[0]
        assertEquals("1.2.3", record.appVersionName)
        assertEquals(42L, record.appVersionCode)
        assertEquals("14", record.androidRelease)
        assertEquals(34, record.androidSdkInt)
        assertEquals("Google", record.deviceManufacturer)
        assertEquals("Pixel 8", record.deviceModel)
    }

    @Test
    fun `prunes to the retention cap after every recorded crash`() {
        val sink = FakeSink()

        recorder(sink, maxStoredCrashes = 50).record(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(listOf(50), sink.pruneCalls)
    }

    @Test
    fun `prunes after inserting so the newest crash is never the one dropped`() {
        val sink = FakeSink()
        val order = mutableListOf<String>()
        val orderedSink = object : CrashLogSink {
            override fun record(record: CrashLogRecord) { order += "record" }
            override fun prune(keepMostRecent: Int) { order += "prune" }
        }

        recorder(orderedSink).record(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(listOf("record", "prune"), order)
        assertTrue(sink.recorded.isEmpty())
    }

    @Test
    fun `a failing sink never propagates out of the crash path`() {
        val sink = FakeSink().apply { failOnRecord = IllegalStateException("db is gone") }

        // No exception expected - the crash handler must still reach the system handler.
        recorder(sink).record(Thread.currentThread(), RuntimeException("boom"))
    }

    @Test
    fun `a failing prune never propagates out of the crash path`() {
        val sink = FakeSink().apply { failOnPrune = IllegalStateException("db is gone") }

        recorder(sink).record(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, sink.recorded.size)
    }

    @Test
    fun `an OutOfMemoryError from the sink is also swallowed`() {
        val sink = FakeSink().apply { failOnRecord = OutOfMemoryError("no heap left") }

        recorder(sink).record(Thread.currentThread(), RuntimeException("boom"))
    }

    @Test
    fun `a sink failure is reported to the failure hook`() {
        val failure = IllegalStateException("db is gone")
        val sink = FakeSink().apply { failOnRecord = failure }

        recorder(sink).record(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(listOf<Throwable>(failure), reportedFailures)
    }

    @Test
    fun `a throwing failure hook still does not propagate`() {
        val sink = FakeSink().apply { failOnRecord = IllegalStateException("db is gone") }
        val recorder = CrashLogRecorder(
            sink = sink,
            metadata = metadata,
            onRecordingFailed = { throw IllegalStateException("logging is gone too") }
        )

        recorder.record(Thread.currentThread(), RuntimeException("boom"))
    }

    @Test
    fun `nothing is reported when recording succeeds`() {
        val sink = FakeSink()

        recorder(sink).record(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(reportedFailures.isEmpty())
    }

    @Test
    fun `default retention cap is fifty crashes`() {
        assertEquals(50, CrashLogRecorder.DEFAULT_MAX_STORED_CRASHES)
    }
}
