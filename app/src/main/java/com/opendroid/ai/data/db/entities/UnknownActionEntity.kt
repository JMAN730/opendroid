package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unknown_actions")
data class UnknownActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val attemptedAction: String,
    val goal: String,
    val timestamp: Long = System.currentTimeMillis(),
    val fixStatus: String, // "AUTO_FIXED" | "REPLANNED" | "FAILED"
    val wasAutoFixed: Boolean = false,
    val fixedWith: String? = null
)
