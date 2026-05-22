package com.opendroid.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.opendroid.ai.data.db.dao.ConversationDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.MemoryDao
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.db.entities.MemoryEntity
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.db.entities.TaskHistoryEntity

import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity

@Database(
    entities = [
        ConversationEntity::class,
        PlanEntity::class,
        MemoryEntity::class,
        TaskHistoryEntity::class,
        MacroEntity::class,
        UnknownActionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OpenDroidDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun planDao(): PlanDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun macroDao(): MacroDao
    abstract fun unknownActionDao(): UnknownActionDao
}
