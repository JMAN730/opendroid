package com.opendroid.ai.data.crash

import com.opendroid.ai.data.db.entities.CrashLogEntity
import com.opendroid.ai.core.crash.CrashLogSink
import kotlinx.coroutines.flow.Flow

/** Storage boundary for local crash diagnostics. UI and application wiring use this API. */
interface CrashLogRepository : CrashLogSink {
    fun getAllFlow(): Flow<List<CrashLogEntity>>
    suspend fun getAll(): List<CrashLogEntity>
    suspend fun clearAll()
}
