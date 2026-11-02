package com.opendroid.ai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.ChatAgent
import com.opendroid.ai.core.agent.ChatErrorPrimaryAction
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.agent.primaryAction
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.resolvedAutoMode
import com.opendroid.ai.data.repository.ChatSession
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: ChatAgent,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val llmConfig: StateFlow<LLMConfig> = settingsRepository.llmConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LLMConfig())

    // Scoped to whichever session is current; re-collects automatically when the
    // user switches chats since the repository's flow is driven off the same
    // reactive "current session" pointer that switchToSession() flips.
    val conversationHistory: StateFlow<List<ChatMessage>> = conversationRepository.conversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Every chat session, most recently active first. */
    val sessions: StateFlow<List<ChatSession>> = conversationRepository.sessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Id of whichever session is current; null only before the very first session exists. */
    val currentSessionId: StateFlow<String?> = conversationRepository.currentSessionId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val agentState: StateFlow<AgentState> = agentLoop.agentState

    /**
     * [AgentLoop.chatError], but scoped to whichever chat is on screen - the same rule
     * [visibleAgentState] applies to the shared agent state. An error raised by a task
     * in chat A must never render its recovery card inside chat B; the underlying error
     * stays published so switching back to its own chat shows it again.
     */
    val chatError: StateFlow<ChatErrorUiState?> = combine(
        agentLoop.chatError, sessions
    ) { error, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        error?.takeIf { it.sessionId == current }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun dismissChatError() {
        agentLoop.dismissChatError()
    }

    fun retryAfterChatError(context: Context) {
        val error = agentLoop.chatError.value ?: return
        when (error.primaryAction()) {
            ChatErrorPrimaryAction.RETRY -> {
                val pausedPlanId = error.resumePlanId
                if (pausedPlanId != null) {
                    // The model failed mid-plan. Carry on with the steps not yet run:
                    // re-sending the request would plan it again and repeat steps that
                    // already happened. resumePlan clears the card itself, since
                    // dismissing it would fail the plan.
                    agentLoop.resumePlan(pausedPlanId, error.sessionId, context)
                    return
                }
                agentLoop.dismissChatError()
                // Re-execute the request the error actually describes, in ITS session -
                // never re-send the visible chat's last message, and never insert a
                // duplicate user bubble: the original message is already persisted.
                agentLoop.retryRequest(error.requestId, error.sessionId, context)
            }
            else -> agentLoop.dismissChatError()
        }
    }

    // The chat [state], a single field shared by every chat, belongs to. A proposal
    // carries its own pin; anything else belongs to the task AgentLoop pinned last. Comes
    // from AgentLoop rather than tracking our own copy, since a voice or bubble task
    // starts without going through this ViewModel.
    private fun sessionOf(state: AgentState, activeTaskSessionId: String?, proposedPlanSessionId: String?): String? =
        if (state is AgentState.PlanProposed) proposedPlanSessionId ?: activeTaskSessionId else activeTaskSessionId

    /**
     * [agentState], but forced to [AgentState.Idle] whenever it actually describes a
     * task pinned to a DIFFERENT chat than whichever one is current. Since a task now
     * keeps running (and writing) in the chat it started in even after the user
     * switches away, the shared [agentState] alone can't distinguish "busy in this
     * chat" from "busy in some other chat" - this is what any UI representing the
     * CURRENTLY VIEWED chat (thinking bubble, plan-approval prompt, orb pulse, status
     * text) must read instead of [agentState] directly, or it would misattribute a
     * background chat's activity to whatever the user happens to be looking at.
     */
    val visibleAgentState: StateFlow<AgentState> = combine(
        agentState, agentLoop.activeTaskSessionId, agentLoop.proposedPlanSessionId, sessions
    ) { state, activeTaskSessionId, proposedPlanSessionId, sessionList ->
        val taskSessionId = sessionOf(state, activeTaskSessionId, proposedPlanSessionId)
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        if (taskSessionId == null || taskSessionId == current) state else AgentState.Idle
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AgentState.Idle
    )

    /**
     * Id of whichever chat has an actively in-flight task (thinking or executing a plan
     * step), or null when nothing is running anywhere. Unlike [visibleAgentState] this
     * is NOT scoped to the currently viewed chat - it drives the chat-picker's "still
     * running" indicator, which must stay visible for that chat even while the user has
     * switched away from it.
     */
    val runningSessionId: StateFlow<String?> = combine(
        agentState, agentLoop.activeTaskSessionId, agentLoop.proposedPlanSessionId
    ) { state, activeTaskSessionId, proposedPlanSessionId ->
        if (state is AgentState.Thinking || state is AgentState.ExecutingPlan) {
            sessionOf(state, activeTaskSessionId, proposedPlanSessionId)
        } else {
            null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun sendMessage(query: String, context: Context) {
        if (query.isBlank()) return
        // Reads the live-collected [sessions] snapshot for the same reason
        // [isCurrentSession] does - [sessions] is guaranteed to be actively collected by
        // ChatScreen, [currentSessionId] is not. Passed straight through so AgentLoop pins
        // this chat instead of re-resolving "current" itself at an arbitrary later moment.
        val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
        agentLoop.processQuery(query, context, sessionId)
    }

    /** Id of the contact picker message the agent is waiting on; only its card is active. */
    val pendingContactPickerMessageId: StateFlow<String?> = agentLoop.pendingContactPickerMessageId

    fun selectContact(pickerMessageId: String, contact: Map<String, String>, context: Context) {
        val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
        agentLoop.answerContactPicker(pickerMessageId, contact, context, sessionId)
    }

    /**
     * Edits a previously sent user message and resends it, DeepSeek-style: cancels any
     * in-flight agent task first (so a running/awaiting-input task can never race with
     * or swallow the resend), deletes that message and every message after it in the
     * current session so the conversation visibly rewinds, then sends the edited text
     * as a fresh query.
     */
    fun editAndResend(messageId: String, newText: String, context: Context) {
        if (newText.isBlank()) return
        agentLoop.cancelCurrentTask()
        viewModelScope.launch {
            val sessionId = conversationRepository.ensureCurrentSessionId()
            conversationRepository.deleteMessageAndAfter(sessionId, messageId)
            agentLoop.processQuery(newText, context, sessionId)
        }
    }

    fun approvePlan(context: Context, planId: String, grants: Set<String> = emptySet()) {
        agentLoop.approveProposedPlan(context, planId, grants)
    }

    /** Chip tap: Off <-> Auto only. YOLO is entered solely from Settings behind
     *  its warning dialog and exits to OFF from here (tapping the red chip is
     *  an explicit "get me out of YOLO", which is always safe). */
    fun cycleAutoMode() {
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                val next = when (current.resolvedAutoMode()) {
                    AutoMode.OFF -> AutoMode.AUTO
                    AutoMode.AUTO -> AutoMode.OFF
                    AutoMode.YOLO -> AutoMode.OFF
                }
                current.copy(autoMode = next, autoConfirmPlans = next == AutoMode.YOLO)
            }
        }
    }

    fun rejectPlan() {
        agentLoop.rejectProposedPlan()
    }

    /** Clears the messages of the CURRENT chat only - other chats are untouched. */
    fun clearChat() {
        val current = sessions.value.firstOrNull { it.isCurrent }?.id
        if (current != null) cancelTaskIn(current)
        viewModelScope.launch {
            conversationRepository.clearCurrentSession()
        }
    }

    /**
     * Starts a brand-new, empty chat and switches to it. Deliberately does NOT cancel
     * whatever the agent is currently doing: since a task's session is pinned once at
     * its start and threaded through explicitly, it keeps running - and keeps writing
     * its replies - in the chat it was launched from, not wherever the user navigates
     * to afterwards. [runningSessionId] / [visibleAgentState] are how the UI stays
     * truthful about that chat still being busy while this one is not.
     */
    fun newChat() {
        viewModelScope.launch {
            conversationRepository.createSession()
        }
    }

    /**
     * Switches the active chat; [conversationHistory] re-collects for the new session.
     * Deliberately does NOT cancel an in-flight task for the same reason [newChat] does
     * not - see its doc comment.
     */
    fun switchToSession(sessionId: String) {
        if (isCurrentSession(sessionId)) return
        viewModelScope.launch {
            conversationRepository.switchToSession(sessionId)
        }
    }

    /** Renames a chat without changing which one is current. */
    fun renameSession(sessionId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            conversationRepository.renameSession(sessionId, title.trim())
        }
    }

    /**
     * Deletes a chat and its messages. A task or plan proposal pinned to that chat is
     * cancelled first - deleting it out from under a still-running task would otherwise
     * leave that task writing into a session that no longer exists - while one pinned to
     * any other chat keeps going. The repository guarantees another session becomes
     * current - the user is never left with no chat selected.
     */
    fun deleteChat(sessionId: String) {
        cancelTaskIn(sessionId)
        agentLoop.forgetSession(sessionId)
        viewModelScope.launch {
            conversationRepository.deleteSession(sessionId)
            conversationRepository.ensureCurrentSessionId()
        }
    }

    // Reads the live-collected [sessions] snapshot rather than [currentSessionId].value:
    // currentSessionId is exposed for callers that actually collect it, but nothing in
    // this ViewModel guarantees it is being collected, and an un-collected
    // WhileSubscribed StateFlow never advances past its initial value. [sessions] is
    // always actively collected by ChatScreen (it drives the chat picker itself), so by
    // the time a user can tap switch/delete on a session row, its value is current.
    private fun isCurrentSession(sessionId: String): Boolean =
        sessions.value.any { it.id == sessionId && it.isCurrent }

    private fun cancelTaskIn(sessionId: String) {
        val owner = sessionOf(
            agentLoop.agentState.value,
            agentLoop.activeTaskSessionId.value,
            agentLoop.proposedPlanSessionId.value
        )
        if (owner == sessionId) {
            agentLoop.cancelCurrentTask()
        }
    }

    /**
     * Cancels whatever the agent is currently doing without touching the
     * conversation history. Shared by the Plan tab and the chat UI's stop control.
     */
    fun stopTask() {
        agentLoop.cancelCurrentTask()
    }
}
