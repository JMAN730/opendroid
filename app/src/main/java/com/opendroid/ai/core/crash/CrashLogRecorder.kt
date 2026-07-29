package com.opendroid.ai.core.crash

/**
 * Builds a [CrashLogRecord] from an uncaught exception and hands it to a [CrashLogSink].
 *
 * Every failure is swallowed. This code runs between the exception becoming
 * uncaught and the system handler killing the process; anything thrown here
 * would replace a real, diagnosable crash with a useless one from the crash
 * logger itself.
 *
 * Deliberately free of Android APIs - reporting a recording failure goes through
 * [onRecordingFailed] rather than `android.util.Log` so the whole class stays
 * exercisable from plain JVM unit tests.
 */
class CrashLogRecorder(
    private val sink: CrashLogSink,
    private val metadata: DeviceMetadata,
    private val maxStoredCrashes: Int = DEFAULT_MAX_STORED_CRASHES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecordingFailed: (Throwable) -> Unit = {}
) : CrashRecorder {

    override fun record(thread: Thread, throwable: Throwable) {
        try {
            // Insert first, then prune: pruning first could evict against a cap
            // that the about-to-be-inserted crash has not been counted towards.
            sink.record(buildRecord(thread, throwable))
            sink.prune(maxStoredCrashes)
        } catch (t: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError while serialising a
            // huge stack trace is a realistic failure here and must not escape.
            reportQuietly(t)
        }
    }

    private fun reportQuietly(t: Throwable) {
        try {
            onRecordingFailed(t)
        } catch (ignored: Throwable) {
            // Reporting the failure failed. There is nowhere left to go.
        }
    }

    fun buildRecord(thread: Thread, throwable: Throwable): CrashLogRecord = CrashLogRecord(
        timestamp = clock(),
        exceptionClass = CrashReportFormatter.exceptionClassOf(throwable),
        message = CrashReportFormatter.messageOf(throwable),
        threadName = thread.name,
        stackTrace = CrashReportFormatter.stackTraceOf(throwable),
        appVersionName = metadata.appVersionName,
        appVersionCode = metadata.appVersionCode,
        androidRelease = metadata.androidRelease,
        androidSdkInt = metadata.androidSdkInt,
        deviceManufacturer = metadata.deviceManufacturer,
        deviceModel = metadata.deviceModel
    )

    companion object {
        /** Enough history to spot a pattern, small enough to stay cheap to store and render. */
        const val DEFAULT_MAX_STORED_CRASHES = 50
    }
}
