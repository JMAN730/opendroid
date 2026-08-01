package com.opendroid.ai.data.crash

import com.opendroid.ai.core.crash.CrashLogRecord
import com.opendroid.ai.data.db.dao.CrashLogDao
import com.opendroid.ai.data.db.entities.CrashLogEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow

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
) : CrashLogRepository {

    override fun getAllFlow(): Flow<List<CrashLogEntity>> = dao.getAllFlow()

    override suspend fun getAll(): List<CrashLogEntity> = dao.getAll()

    override suspend fun clearAll() = dao.clearAll()

    /**
     * Monotonic deadline shared by every write for a single crash, set on the
     * first one. Budgeting per call instead would let a wedged database stall
     * the crashing thread for `timeoutMillis` once per write.
     *
     * Never reset: one sink records one crash, because the process dies right
     * after. A sink reused across two crashes would find the budget spent.
     */
    private var deadlineMillis: Long? = null

    override fun record(record: CrashLogRecord) {
        awaitWithinBudget { dao.insert(record.toEntity()) }
    }

    override fun prune(keepMostRecent: Int) {
        awaitWithinBudget { dao.pruneToMostRecent(keepMostRecent) }
    }

    /**
     * A wedged database must not hang the crash path - that turns a crash into
     * an ANR, and the user loses the report either way. Storing the crash takes
     * priority over pruning: whatever budget the insert leaves is what pruning
     * gets, and pruning is skipped outright once the budget is gone.
     */
    private fun awaitWithinBudget(block: suspend () -> Unit) {
        val remaining = remainingBudgetMillis()
        if (remaining <= 0L) return
        runBlocking { withTimeoutOrNull(remaining) { block() } }
    }

    private fun remainingBudgetMillis(): Long {
        // Monotonic: the crash path must not be at the mercy of a wall-clock jump.
        val now = System.nanoTime() / 1_000_000
        val deadline = deadlineMillis ?: (now + timeoutMillis).also { deadlineMillis = it }
        return (deadline - now).coerceAtLeast(0L)
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
