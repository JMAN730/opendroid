package com.opendroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single chat history. The app supports multiple independent chat sessions
 * instead of one global conversation; [ConversationEntity.sessionId] points
 * back at the owning row here.
 *
 * [isCurrent] marks the session currently shown/appended to. Exactly one row
 * should have isCurrent = true at any time - always flip it via
 * ChatSessionDao.markCurrent, which clears every other row atomically.
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isCurrent: Boolean = false
)
