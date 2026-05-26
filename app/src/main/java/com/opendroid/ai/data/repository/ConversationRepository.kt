package com.opendroid.ai.data.repository

import com.opendroid.ai.data.db.dao.ConversationDao
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao
) {
    val conversations: Flow<List<ChatMessage>> = conversationDao.getAllConversations().map { entities ->
        entities.map { entity ->
            ChatMessage(
                id = entity.id,
                text = entity.text,
                sender = ChatMessage.Sender.valueOf(entity.sender),
                timestamp = entity.timestamp,
                modelBadge = entity.modelBadge,
                contactPickerData = entity.contactPickerData
            )
        }
    }

    suspend fun insertMessage(message: ChatMessage) {
        conversationDao.insertMessage(
            ConversationEntity(
                id = message.id,
                text = message.text,
                sender = message.sender.name,
                timestamp = message.timestamp,
                modelBadge = message.modelBadge,
                contactPickerData = message.contactPickerData
            )
        )
    }

    suspend fun getLastMessages(limit: Int): List<ChatMessage> {
        return conversationDao.getLastMessages(limit).map { entity ->
            ChatMessage(
                id = entity.id,
                text = entity.text,
                sender = ChatMessage.Sender.valueOf(entity.sender),
                timestamp = entity.timestamp,
                modelBadge = entity.modelBadge,
                contactPickerData = entity.contactPickerData
            )
        }.reversed()
    }

    suspend fun clearAll() {
        conversationDao.clearAll()
    }
}
