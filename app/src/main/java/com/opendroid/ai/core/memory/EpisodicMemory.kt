package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpisodicMemory @Inject constructor(
    private val conversationRepository: ConversationRepository
) {
    /** Stores [message] into whichever session is current at call time. */
    suspend fun storeMessage(message: ChatMessage) {
        conversationRepository.insertMessage(message)
    }

    /**
     * Stores [message] into [sessionId] specifically. Use this from a caller that has
     * already pinned a session for a whole task (see AgentLoop) so the write can't drift
     * to whichever chat happens to be current by the time this call actually runs.
     */
    suspend fun storeMessage(message: ChatMessage, sessionId: String) {
        conversationRepository.insertMessage(sessionId, message)
    }

    /**
     * Searches the current session's recent history only - chats are independent
     * conversations, so a search here deliberately does not bleed across chats.
     */
    suspend fun searchLogs(query: String): List<ChatMessage> {
        return conversationRepository.getLastMessages(100).filter {
            it.text.contains(query, ignoreCase = true)
        }
    }
}
