package com.opendroid.ai.core.agent

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatErrorUiStateTest {

    @Test
    fun `auth failures open settings and never offer blind retry`() {
        val state = ChatErrorUiState.fromException(
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
}
