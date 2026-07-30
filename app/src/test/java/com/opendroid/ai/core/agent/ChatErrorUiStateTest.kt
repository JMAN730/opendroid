package com.opendroid.ai.core.agent

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatErrorUiStateTest {

    @Test
    fun `auth failures open settings and never offer blind retry`() {
        val state = ChatErrorUiState.fromException(
            sessionId = "session",
            requestId = "req",
            runId = "run",
            failure = LLMErrorMapper.authMissing("OpenAI", "gpt-4o")
        )
        assertEquals(ChatErrorPrimaryAction.OPEN_SETTINGS, state.primaryAction())
        assertEquals(LLMError.AuthMissing, state.category)
    }

    @Test
    fun `retryable network failures offer retry`() {
        val state = ChatErrorUiState.fromException(
            sessionId = "session",
            requestId = "req",
            runId = "run",
            failure = LLMErrorMapper.fromThrowable(
                "OpenAI",
                "gpt-4o",
                java.net.UnknownHostException("offline")
            )
        )
        assertEquals(ChatErrorPrimaryAction.RETRY, state.primaryAction())
        assertEquals(LLMError.Network, state.category)
    }

    @Test
    fun `error carries the session it was raised in`() {
        val state = ChatErrorUiState.fromException(
            sessionId = "session-a",
            requestId = "req",
            runId = "run",
            failure = LLMErrorMapper.authMissing("OpenAI", "gpt-4o")
        )
        assertEquals("session-a", state.sessionId)
    }

    @Test
    fun `rate limited failure with retry-after enters waiting phase until the window closes`() {
        val now = 1_000_000L
        val state = ChatErrorUiState.fromException(
            sessionId = "session",
            requestId = "req",
            runId = "run",
            failure = LLMErrorMapper.fromHttpFailure(
                provider = ProviderErrorDetail.Provider.OPENAI,
                model = "gpt-4o",
                httpStatus = 429,
                headers = mapOf("Retry-After" to "30"),
                rawBody = """{"error":{"type":"rate_limit_error"}}"""
            ),
            nowMillis = now
        )
        assertEquals(LLMError.RateLimited, state.category)
        assertEquals(ChatErrorPrimaryAction.RETRY, state.primaryAction())
        val phase = state.phase
        assertTrue(phase is ChatErrorUiState.Phase.WaitingUntil)
        assertEquals(now + 30_000L, (phase as ChatErrorUiState.Phase.WaitingUntil).epochMillis)
    }

    @Test
    fun `failure without retry-after stays in final phase`() {
        val state = ChatErrorUiState.fromException(
            sessionId = "session",
            requestId = "req",
            runId = "run",
            failure = LLMErrorMapper.fromThrowable(
                "OpenAI",
                "gpt-4o",
                java.net.UnknownHostException("offline")
            )
        )
        assertEquals(ChatErrorUiState.Phase.Final, state.phase)
    }
}
