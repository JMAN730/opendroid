package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "OTHER", // MESSAGE, EMAIL, SOCIAL, SYSTEM, OTHER
    val isAutoReplied: Boolean = false,
    val autoReplyText: String? = null,
    val contactName: String? = null,
    val senderEmail: String? = null,
    val isRead: Boolean = false
)
