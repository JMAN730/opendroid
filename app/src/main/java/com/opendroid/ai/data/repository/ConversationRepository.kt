package com.opendroid.ai.data.repository

import androidx.room.withTransaction
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.dao.ChatSessionDao
import com.opendroid.ai.data.db.dao.ConversationDao
import com.opendroid.ai.data.db.entities.ChatSessionEntity
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single chat history. The app keeps multiple independent sessions instead
 * of one global conversation; [isCurrent] mirrors whichever one is active.
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isCurrent: Boolean
)

@Singleton
class ConversationRepository @Inject constructor(
    private val database: OpenDroidDatabase,
    private val conversationDao: ConversationDao,
    private val chatSessionDao: ChatSessionDao
) {
    // Serializes lazy creation of the very first session so concurrent callers
    // (e.g. two ViewModels reading history at app startup) can't each insert
    // their own session - a fresh install must end up with exactly one.
    private val ensureSessionMutex = Mutex()

    /**
     * Messages of the currently active session, oldest first. Reactive to both
     * message changes and to the user switching sessions - the current-session
     * flag lives in [chatSessionDao], so a session switch alone re-triggers this.
     */
    val conversations: Flow<List<ChatMessage>> = chatSessionDao.getCurrentSession()
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { sessionId ->
            conversationDao.getMessagesForSession(sessionId ?: ensureCurrentSessionId())
        }
        .map { entities -> entities.map { it.toChatMessage() } }

    /** Every chat session, most recently active first. */
    val sessions: Flow<List<ChatSession>> = chatSessionDao.getAllSessions().map { entities ->
        entities.map { it.toChatSession() }
    }

    /** Reactive id of the current session; null only before the first session exists. */
    val currentSessionId: Flow<String?> = chatSessionDao.getCurrentSession().map { it?.id }

    /** Messages of an arbitrary session (not necessarily the current one), oldest first. */
    fun getMessages(sessionId: String): Flow<List<ChatMessage>> =
        conversationDao.getMessagesForSession(sessionId).map { entities ->
            entities.map { it.toChatMessage() }
        }

    /** Inserts [message] into the current session, lazily creating one if needed. */
    suspend fun insertMessage(message: ChatMessage) {
        insertMessage(ensureCurrentSessionId(), message)
    }

    /** Inserts [message] into a specific session. */
    suspend fun insertMessage(sessionId: String, message: ChatMessage) {
        conversationDao.insertMessage(message.toEntity(sessionId))
        chatSessionDao.touch(sessionId, message.timestamp)
    }

    /** Last [limit] messages of the current session, chronological order. */
    suspend fun getLastMessages(limit: Int): List<ChatMessage> =
        getLastMessages(ensureCurrentSessionId(), limit)

    /** Last [limit] messages of [sessionId], chronological order. */
    suspend fun getLastMessages(sessionId: String, limit: Int): List<ChatMessage> {
        return conversationDao.getLastMessagesForSession(sessionId, limit).map { entity ->
            entity.toChatMessage()
        }.reversed()
    }

    /**
     * Clears every message of the current session only. Other sessions are untouched.
     * This is what "clear this chat" means - see [clearAllSessions] for wiping history
     * across every chat.
     */
    suspend fun clearCurrentSession() {
        clearSession(ensureCurrentSessionId())
    }

    /** Clears every message of [sessionId], leaving the session itself intact. */
    suspend fun clearSession(sessionId: String) {
        conversationDao.clearSession(sessionId)
    }

    /**
     * Clears every message in every session - a true "wipe all history". Sessions
     * themselves (and their titles) are preserved, just emptied. Used for the Memory
     * tab's episodic-memory clear, which is labeled as clearing everything, not just
     * whichever chat happens to be current.
     */
    suspend fun clearAllSessions() {
        conversationDao.clearAll()
    }

    /** Deletes a single message by id. */
    suspend fun deleteMessage(messageId: String) {
        conversationDao.deleteMessage(messageId)
    }

    /**
     * Deletes [messageId] together with every message sent after it in
     * [sessionId]. This is the primitive behind "edit and resend": remove the
     * edited message and whatever followed it, then resubmit the edited text
     * as a new turn via [insertMessage].
     */
    suspend fun deleteMessageAndAfter(sessionId: String, messageId: String) {
        conversationDao.deleteMessageAndAfter(sessionId, messageId)
    }

    /** Creates a new, empty session, makes it current, and returns its id. */
    suspend fun createSession(title: String = "New Chat"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.withTransaction {
            chatSessionDao.insert(
                ChatSessionEntity(id = id, title = title, createdAt = now, updatedAt = now, isCurrent = false)
            )
            chatSessionDao.markCurrent(id)
        }
        return id
    }

    /** Switches which session [conversations]/[insertMessage]/[getLastMessages]/[clearCurrentSession] act on. */
    suspend fun switchToSession(sessionId: String) {
        chatSessionDao.markCurrent(sessionId)
    }

    /** Renames a session and bumps its updatedAt so it sorts as recently active. */
    suspend fun renameSession(sessionId: String, title: String) {
        chatSessionDao.rename(sessionId, title, System.currentTimeMillis())
    }

    /**
     * Deletes a session and every message in it. If it was the current session,
     * another existing session becomes current; if none remain, the next call
     * that needs a current session (e.g. the next [insertMessage]) lazily
     * creates a fresh one - the app is never left without an active session.
     */
    suspend fun deleteSession(sessionId: String) {
        database.withTransaction {
            val wasCurrent = chatSessionDao.getCurrentSessionOnce()?.id == sessionId
            conversationDao.clearSession(sessionId)
            chatSessionDao.delete(sessionId)
            if (wasCurrent) {
                chatSessionDao.getAnySessionId()?.let { fallback -> chatSessionDao.markCurrent(fallback) }
            }
        }
    }

    /**
     * Resolves the id of the current session, lazily creating the very first
     * session on a brand-new install (or after the last session was deleted).
     * Guarded by [ensureSessionMutex] with a double-check so concurrent callers
     * and repeated launches never produce duplicate sessions.
     */
    suspend fun ensureCurrentSessionId(): String {
        chatSessionDao.getCurrentSessionOnce()?.let { return it.id }
        return ensureSessionMutex.withLock {
            chatSessionDao.getCurrentSessionOnce()?.let { return@withLock it.id }
            chatSessionDao.getAnySessionId()?.let { existing ->
                chatSessionDao.markCurrent(existing)
                return@withLock existing
            }
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            chatSessionDao.insert(
                ChatSessionEntity(id = id, title = "New Chat", createdAt = now, updatedAt = now, isCurrent = true)
            )
            id
        }
    }

    private fun ConversationEntity.toChatMessage() = ChatMessage(
        id = id,
        text = text,
        sender = ChatMessage.Sender.valueOf(sender),
        timestamp = timestamp,
        modelBadge = modelBadge,
        contactPickerData = contactPickerData
    )

    private fun ChatMessage.toEntity(sessionId: String) = ConversationEntity(
        id = id,
        text = text,
        sender = sender.name,
        timestamp = timestamp,
        modelBadge = modelBadge,
        contactPickerData = contactPickerData,
        sessionId = sessionId
    )

    private fun ChatSessionEntity.toChatSession() = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isCurrent = isCurrent
    )
}
