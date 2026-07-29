package com.opendroid.ai.data.crash

import com.opendroid.ai.core.crash.CrashLogRecord
import com.opendroid.ai.core.crash.CrashLogSink
import com.opendroid.ai.data.db.dao.CrashLogDao
import com.opendroid.ai.data.db.entities.CrashLogEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persists crashes to Room from the crashing thread.
 *
 * `runBlocking` is correct here rather than a lazy coroutine launch: the system
 * handler kills the process the moment this returns, so a write that has not
 * completed by then is a write that never happens.
 *
 * Blocking on a *suspend* DAO function is also what keeps this legal on the main
 * thread - Room dispatches suspend queries to its own executor, so its
 * "cannot access database on the main thread" guard does not apply. Most crashes
 * are main-thread crashes, so that detail is load-bearing.
 */
class RoomCrashLogSink(
    private val dao: CrashLogDao,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : CrashLogSink {

    override fun record(record: CrashLogRecord) {
        awaitWithTimeout { dao.insert(record.toEntity()) }
    }

    override fun prune(keepMostRecent: Int) {
        awaitWithTimeout { dao.pruneToMostRecent(keepMostRecent) }
    }

    /**
     * A wedged database must not hang the crash path - that turns a crash into
     * an ANR, and the user loses the report either way.
     */
    private fun awaitWithTimeout(block: suspend () -> Unit) {
        runBlocking { withTimeoutOrNull(timeoutMillis) { block() } }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}

fun CrashLogEntity.toRecord(): CrashLogRecord = CrashLogRecord(
    timestamp = timestamp,
    exceptionClass = exceptionClass,
    message = message,
    threadName = threadName,
    stackTrace = stackTrace,
    appVersionName = appVersionName,
    appVersionCode = appVersionCode,
    androidRelease = androidRelease,
    androidSdkInt = androidSdkInt,
    deviceManufacturer = deviceManufacturer,
    deviceModel = deviceModel
)

fun CrashLogRecord.toEntity(): CrashLogEntity = CrashLogEntity(
    timestamp = timestamp,
    exceptionClass = exceptionClass,
    message = message,
    threadName = threadName,
    stackTrace = stackTrace,
    appVersionName = appVersionName,
    appVersionCode = appVersionCode,
    androidRelease = androidRelease,
    androidSdkInt = androidSdkInt,
    deviceManufacturer = deviceManufacturer,
    deviceModel = deviceModel
)
