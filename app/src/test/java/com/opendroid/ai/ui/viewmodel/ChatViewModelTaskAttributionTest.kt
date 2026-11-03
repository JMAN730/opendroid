package com.opendroid.ai.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.ChatAgent
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * Attribution of the single shared agent state to chats, read from the session the agent
 * itself pinned. Chat A is where the user last typed, B is the chat on screen (the last
 * one created), C is a third chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatViewModelTaskAttributionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val agent = FakeChatAgent()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var database: OpenDroidDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var viewModel: ChatViewModel
    private lateinit var chatA: String
    private lateinit var chatB: String
    private lateinit var chatC: String

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ConversationRepository(database, database.conversationDao(), database.chatSessionDao())
        runBlocking {
            chatC = repository.createSession("C")
            chatA = repository.createSession("A")
            chatB = repository.createSession("B")
        }
        viewModel = ChatViewModel(agent, repository, newSettingsRepository())
        // ChatScreen keeps these collected; WhileSubscribed flows stand still without it.
        collectors.launch { viewModel.sessions.collect {} }
        collectors.launch { viewModel.visibleAgentState.collect {} }
        collectors.launch { viewModel.runningSessionId.collect {} }
        awaitOnScreen(chatB)
    }

    @After
    fun tearDown() {
        collectors.cancel()
        database.close()
    }

    @Test
    fun `clearing a chat leaves a task running in another chat alone`() {
        agent.runTask(chatA)

        viewModel.clearChat()

        assertEquals(0, agent.cancelCalls)
    }

    @Test
    fun `clearing a chat cancels its own running task`() {
        agent.runTask(chatB)

        viewModel.clearChat()

        assertEquals(1, agent.cancelCalls)
    }

    @Test
    fun `clearing a chat cancels the plan waiting for approval in it`() {
        agent.proposePlan(chatB)

        viewModel.clearChat()

        assertEquals(1, agent.cancelCalls)
    }

    @Test
    fun `clearing a chat leaves a plan waiting for approval in another chat alone`() {
        agent.proposePlan(chatA)

        viewModel.clearChat()

        assertEquals(0, agent.cancelCalls)
    }

    @Test
    fun `deleting the chat on screen leaves a task running in another chat alone`() {
        agent.runTask(chatA)

        viewModel.deleteChat(chatB)

        assertEquals(0, agent.cancelCalls)
    }

    @Test
    fun `deleting a chat from elsewhere cancels the task running in it`() {
        typeMessageIn(chatA)
        agent.runTask(chatB)
        switchTo(chatC)

        viewModel.deleteChat(chatB)

        assertEquals(1, agent.cancelCalls)
    }

    @Test
    fun `deleting a chat from elsewhere cancels the plan waiting for approval in it`() {
        agent.proposePlan(chatA)

        viewModel.deleteChat(chatA)

        assertEquals(1, agent.cancelCalls)
    }

    @Test
    fun `deleting the last typed chat leaves a voice task in another chat alone`() {
        typeMessageIn(chatA)
        switchTo(chatB)
        agent.runTask(chatB)
        switchTo(chatC)

        viewModel.deleteChat(chatA)

        assertEquals(0, agent.cancelCalls)
    }

    @Test
    fun `a voice proposal shows in the chat it was pinned to, not the last typed one`() {
        typeMessageIn(chatA)
        switchTo(chatB)

        agent.proposePlan(chatB)
        awaitVisibleState { it is AgentState.PlanProposed }

        switchTo(chatA)
        awaitVisibleState { it === AgentState.Idle }
    }

    @Test
    fun `a voice task shows as running in the chat it was pinned to`() {
        typeMessageIn(chatA)
        switchTo(chatB)

        agent.runTask(chatB)

        await("runningSessionId == chat B") { viewModel.runningSessionId.value == chatB }
        awaitVisibleState { it === AgentState.Thinking }
    }

    @Test
    fun `a typed task only shows in its own chat`() {
        typeMessageIn(chatA)
        agent.agentState.value = AgentState.Thinking
        await("runningSessionId == chat A") { viewModel.runningSessionId.value == chatA }

        switchTo(chatB)

        awaitVisibleState { it === AgentState.Idle }
    }

    @Test
    fun `approving a plan keeps its task in the chat it was proposed in`() {
        agent.proposePlan(chatA)

        viewModel.approvePlan(context, "p1")

        await("runningSessionId == chat A") { viewModel.runningSessionId.value == chatA }
        assertEquals(chatB, viewModel.sessions.value.single { it.isCurrent }.id)
    }

    @Test
    fun `retry on a mid-plan error card resumes the plan instead of re-sending the request`() {
        // s1 sent an SMS, then the re-evaluation call got a 503 before s2 ran.
        agent.chatError.value = serverError(chatA, requestId = "plan-1", resumePlanId = "plan-1")

        viewModel.retryAfterChatError(context)

        assertEquals(listOf("plan-1" to chatA), agent.resumed)
        assertEquals(emptyList<Pair<String, String>>(), agent.retried)
        // Dismissing would fail the plan before it could resume.
        assertEquals(0, agent.dismissCalls)
    }

    @Test
    fun `retry on any other error card re-sends the request it describes`() {
        agent.chatError.value = serverError(chatA, requestId = "msg-1", resumePlanId = null)

        viewModel.retryAfterChatError(context)

        assertEquals(listOf("msg-1" to chatA), agent.retried)
        assertEquals(emptyList<Pair<String, String>>(), agent.resumed)
    }

    private fun serverError(sessionId: String, requestId: String, resumePlanId: String?) = ChatErrorUiState(
        sessionId = sessionId,
        requestId = requestId,
        runId = "run",
        category = LLMError.ServerError,
        provider = "OpenAI",
        model = "gpt-4o",
        httpStatus = 503,
        retryable = true,
        resumePlanId = resumePlanId
    )

    private fun typeMessageIn(sessionId: String) {
        switchTo(sessionId)
        viewModel.sendMessage("hello", context)
        assertEquals(sessionId, agent.lastExplicitSessionId)
    }

    private fun switchTo(sessionId: String) {
        runBlocking { repository.switchToSession(sessionId) }
        awaitOnScreen(sessionId)
    }

    private fun awaitOnScreen(sessionId: String) {
        await("chat $sessionId on screen") {
            viewModel.sessions.value.singleOrNull { it.isCurrent }?.id == sessionId
        }
    }

    private fun awaitVisibleState(matches: (AgentState) -> Boolean) {
        await("visible state match, last ${viewModel.visibleAgentState.value}") {
            matches(viewModel.visibleAgentState.value)
        }
    }

    // Room delivers on its own threads and the collectors resume on the paused main looper.
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            assertTrue("Timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-chat-viewmodel-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = EmptyCredentialStore(),
        runStartupMigration = false
    )

    /** Stands in for AgentLoop: tests pin sessions directly and record what the view model cancels. */
    private class FakeChatAgent : ChatAgent {
        override val agentState = MutableStateFlow<AgentState>(AgentState.Idle)
        override val chatError = MutableStateFlow<ChatErrorUiState?>(null)
        override val activeTaskSessionId = MutableStateFlow<String?>(null)
        override val proposedPlanSessionId = MutableStateFlow<String?>(null)

        var cancelCalls = 0
        var lastExplicitSessionId: String? = null

        fun runTask(sessionId: String) {
            activeTaskSessionId.value = sessionId
            agentState.value = AgentState.Thinking
        }

        fun proposePlan(sessionId: String) {
            activeTaskSessionId.value = sessionId
            proposedPlanSessionId.value = sessionId
            agentState.value = AgentState.PlanProposed(plan())
        }

        override fun processQuery(query: String, context: Context, explicitSessionId: String?) {
            lastExplicitSessionId = explicitSessionId
            activeTaskSessionId.value = explicitSessionId
        }

        override fun approveProposedPlan(context: Context, planId: String, grantActions: Set<String>) {
            proposedPlanSessionId.value = null
            agentState.value = AgentState.Thinking
        }

        override fun cancelCurrentTask() {
            cancelCalls++
            proposedPlanSessionId.value = null
            agentState.value = AgentState.Idle
        }

        val retried = mutableListOf<Pair<String, String>>()
        val resumed = mutableListOf<Pair<String, String>>()
        var dismissCalls = 0

        override fun retryRequest(requestId: String, sessionId: String, context: Context) {
            retried += requestId to sessionId
        }

        override fun resumePlan(planId: String, sessionId: String, context: Context) {
            resumed += planId to sessionId
        }

        override fun dismissChatError() {
            dismissCalls++
        }
        override fun rejectProposedPlan() = Unit
        override fun forgetSession(sessionId: String) = Unit
        override val pendingContactPickerMessageId = MutableStateFlow<String?>(null)
        override fun answerContactPicker(
            pickerMessageId: String,
            contact: Map<String, String>,
            context: Context,
            explicitSessionId: String?
        ): Boolean = false

        private fun plan() = Plan(
            planId = "p1",
            goal = "test goal",
            estimatedDuration = "1m",
            estimatedSteps = 0,
            steps = emptyList(),
            status = PlanStatus.PROPOSED
        )
    }

    private class EmptyCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
