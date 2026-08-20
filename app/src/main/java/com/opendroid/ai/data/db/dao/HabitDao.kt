package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.HabitEventEntity
import com.opendroid.ai.data.db.entities.HabitRoutineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    // ── Events ───────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: HabitEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<HabitEventEntity>)

    @Query("SELECT * FROM habit_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 1000): List<HabitEventEntity>

    @Query("SELECT * FROM habit_events WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getEventsSince(sinceTimestamp: Long): List<HabitEventEntity>

    @Query("SELECT * FROM habit_events ORDER BY timestamp DESC LIMIT 50")
    fun getRecentEventsFlow(): Flow<List<HabitEventEntity>>

    @Query("SELECT COUNT(*) FROM habit_events")
    suspend fun getEventCount(): Int

    @Query("DELETE FROM habit_events WHERE timestamp < :beforeTimestamp")
    suspend fun clearEventsBefore(beforeTimestamp: Long)

    @Query("DELETE FROM habit_events")
    suspend fun clearAllEvents()

    // ── Routines ─────────────────────────────────────────
    @Query("SELECT * FROM habit_routines ORDER BY lastDetectedAt DESC")
    fun getAllRoutinesFlow(): Flow<List<HabitRoutineEntity>>

    @Query("SELECT * FROM habit_routines ORDER BY lastDetectedAt DESC")
    suspend fun getAllRoutines(): List<HabitRoutineEntity>

    @Query("SELECT * FROM habit_routines WHERE status = 'SUGGESTED' ORDER BY confidence DESC, lastDetectedAt DESC")
    fun getSuggestedRoutinesFlow(): Flow<List<HabitRoutineEntity>>

    @Query("SELECT * FROM habit_routines WHERE status IN ('APPROVED', 'ACTIVE') ORDER BY lastDetectedAt DESC")
    fun getActiveRoutinesFlow(): Flow<List<HabitRoutineEntity>>

    @Query("SELECT * FROM habit_routines WHERE id = :id LIMIT 1")
    suspend fun getRoutineById(id: String): HabitRoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: HabitRoutineEntity)

    @Query("UPDATE habit_routines SET status = :status, macroId = :macroId WHERE id = :id")
    suspend fun updateRoutineStatus(id: String, status: String, macroId: String? = null)

    @Query("UPDATE habit_routines SET lastExecutedAt = :timestamp WHERE id = :id")
    suspend fun updateLastExecuted(id: String, timestamp: Long)

    @Query("DELETE FROM habit_routines WHERE id = :id")
    suspend fun deleteRoutine(id: String)

    @Query("DELETE FROM habit_routines")
    suspend fun clearAllRoutines()
}
