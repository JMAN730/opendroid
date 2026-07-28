package com.opendroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opendroid.ai.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    /** All sessions, most recently active first. */
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentSession(): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_sessions WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrentSessionOnce(): ChatSessionEntity?

    /** Most recently active session id, used as a fallback when there is no current session. */
    @Query("SELECT id FROM chat_sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getAnySessionId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ChatSessionEntity)

    /**
     * Marks [sessionId] as the current session and un-marks every other one in the
     * same statement, so there is never a moment with zero or two current sessions.
     */
    @Query("UPDATE chat_sessions SET isCurrent = (id = :sessionId)")
    suspend fun markCurrent(sessionId: String)

    @Query("UPDATE chat_sessions SET updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun touch(sessionId: String, timestamp: Long)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun rename(sessionId: String, title: String, timestamp: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: String)
}
