package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.data.models.LLMConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestPlannerTest {

    @Test
    fun `missing api key is AuthMissing without probing`() {
        val gap = ConnectionTestPlanner.configurationGap(
            LLMConfig(activeProvider = "OpenAI", apiKeys = emptyMap()),
            "OpenAI"
        )
        assertEquals(LLMError.AuthMissing, gap?.reason)
        assertEquals("OpenAI", gap?.provider)
    }

    @Test
    fun `configured providers exclude incomplete snapshots`() {
        val config = LLMConfig(
            apiKeys = mapOf("OpenAI" to "sk-test"),
            ollamaUrl = "",
            copilotUrl = ""
        )
        val configured = ConnectionTestPlanner.configuredProviders(config)
        assertTrue(configured.contains("OpenAI"))
        assertTrue(!configured.contains("Ollama"))
        assertTrue(!configured.contains("Copilot API"))
        assertNull(ConnectionTestPlanner.configurationGap(config, "OpenAI"))
    }

    @Test
    fun `custom endpoint missing is RequestInvalid`() {
        val gap = ConnectionTestPlanner.configurationGap(
            LLMConfig(apiKeys = mapOf("Custom OpenAI Compatible" to "sk")),
            "Custom OpenAI Compatible"
        )
        assertEquals(LLMError.RequestInvalid, gap?.reason)
    }

    @Test
    fun `success never encodes failure as latency`() {
        val connected = ConnectionTestPlanner.success(
            provider = "OpenAI",
            model = "gpt-4o",
            latencyMs = 342L,
            testedAtMillis = 1L
        )
        assertEquals(342L, connected.latencyMs)
        assertEquals("gpt-4o", connected.model)
    }
}
