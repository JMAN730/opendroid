package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.llm.error.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSelectionPolicyTest {
    @Test
    fun `only explicit configured providers are candidates`() {
        assertEquals(
            listOf("OpenAI", "Ollama"),
            ProviderSelectionPolicy.explicitFallbacks(
                activeProvider = "On-Device AI",
                configuredFallbacks = listOf("OpenAI", "OpenAI", "unknown", "Ollama"),
                risk = ActionRisk.READ_ONLY
            )
        )
    }

    @Test
    fun `high impact plans do not cross provider boundaries automatically`() {
        assertEquals(
            emptyList<String>(),
            ProviderSelectionPolicy.explicitFallbacks(
                activeProvider = "On-Device AI",
                configuredFallbacks = listOf("OpenAI"),
                risk = ActionRisk.IRREVERSIBLE
            )
        )
    }

    @Test
    fun `only malformed output can escalate and local exhaustion is explicit`() {
        assertTrue(ProviderSelectionPolicy.shouldEscalateMalformed(LLMError.MalformedResponse, true))
        assertFalse(ProviderSelectionPolicy.shouldEscalateMalformed(LLMError.Network, true))
        assertEquals(
            LLMError.SafeFallbackUnavailable,
            ProviderSelectionPolicy.terminalError("On-Device AI", 1)
        )
        assertEquals(
            LLMError.MalformedResponse,
            ProviderSelectionPolicy.terminalError("OpenAI", 1)
        )
    }
}
