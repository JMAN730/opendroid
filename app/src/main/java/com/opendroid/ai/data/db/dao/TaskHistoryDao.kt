package com.opendroid.ai.data.db.dao

import androidx.room.*
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskHistoryDao {
    @Query("SELECT * FROM task_history ORDER BY timestamp DESC")
    fun getTaskHistoryFlow(): Flow<List<TaskHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(task: TaskHistoryEntity)

    @Query("DELETE FROM task_history")
    suspend fun clearAll()
}
