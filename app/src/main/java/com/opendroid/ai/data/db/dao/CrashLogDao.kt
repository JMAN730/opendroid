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
     * Keeps the [keep] newest crashes and deletes the rest.
     *
     * Ordered by `id` as well as `timestamp` so that crashes landing inside the
     * same millisecond still have a deterministic winner.
     */
    @Query(
        """
        DELETE FROM crash_logs
        WHERE id NOT IN (
            SELECT id FROM crash_logs ORDER BY timestamp DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneToMostRecent(keep: Int)

    @Query("SELECT COUNT(*) FROM crash_logs")
    suspend fun count(): Int

    @Query("DELETE FROM crash_logs")
    suspend fun clearAll()
}
