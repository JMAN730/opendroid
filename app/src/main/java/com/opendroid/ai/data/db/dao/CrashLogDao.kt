package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.opendroid.ai.data.db.entities.CrashLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CrashLogDao {

    @Insert
    suspend fun insert(crash: CrashLogEntity): Long

    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC, id DESC")
    fun getAllFlow(): Flow<List<CrashLogEntity>>

    @Query("SELECT * FROM crash_logs ORDER BY timestamp DESC, id DESC")
    suspend fun getAll(): List<CrashLogEntity>

    /**
     * Keeps the [keep] most recently *inserted* crashes and deletes the rest.
     *
     * Retention deliberately orders by `id`, not by `timestamp`. `timestamp`
     * comes from the wall clock, which can move backwards - a device correcting
     * a clock that was set into the future gives the newest row the largest
     * `id` but an older `timestamp`. Ordering retention by `timestamp` would
     * then rank that row outside the keep set and delete the crash that just
     * happened. Insertion order cannot go backwards, so it is the safe key
     * here; the read queries still order by `timestamp` for presentation.
     */
    @Query(
        """
        DELETE FROM crash_logs
        WHERE id NOT IN (
            SELECT id FROM crash_logs ORDER BY id DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneToMostRecent(keep: Int)

    @Query("DELETE FROM crash_logs")
    suspend fun clearAll()
}
