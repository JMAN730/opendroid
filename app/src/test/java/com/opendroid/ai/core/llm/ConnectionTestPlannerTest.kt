package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.data.models.LLMConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestPlannerTest {

    @Test
    fun `connection test persists the on-screen key and endpoint before probing`() {
        // The provider under test reads the saved config; the key field saves on a 1 s
        // debounce, so the saved config can still hold the previous key.
        val saved = LLMConfig(
            apiKeys = mapOf("Custom OpenAI Compatible" to "old-key", "OpenAI" to "sk-openai"),
            customEndpoints = mapOf("Custom OpenAI Compatible" to "https://old.example/v1")
        )
        val onScreen = saved.copy(
            apiKeys = saved.apiKeys + ("Custom OpenAI Compatible" to "new-key"),
            customEndpoints = mapOf("Custom OpenAI Compatible" to "https://new.example/v1")
        )

        val flushed = ConnectionTestPlanner.withCandidateConnection(
            saved = saved,
            candidate = onScreen,
            providerName = "Custom OpenAI Compatible"
        )

        assertEquals("new-key", flushed.apiKeys["Custom OpenAI Compatible"])
        assertEquals("https://new.example/v1", flushed.customEndpoints["Custom OpenAI Compatible"])
        assertEquals("sk-openai", flushed.apiKeys["OpenAI"])
    }

    @Test
    fun `connection test persists the on-screen Ollama url`() {
        val saved = LLMConfig(ollamaUrl = "http://old:11434")
        val flushed = ConnectionTestPlanner.withCandidateConnection(
            saved = saved,
            candidate = saved.copy(ollamaUrl = "http://new:11434"),
            providerName = "Ollama"
        )
        assertEquals("http://new:11434", flushed.ollamaUrl)
    }

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
    fun `cloud providers derive from the catalog and exclude on-device entries`() {
        val cloud = ConnectionTestPlanner.cloudProviders()
        val expected = ProviderCatalog.providers
            .filter { spec -> spec.canonicalName != ProviderCatalog.ON_DEVICE }
            .filter { spec -> spec.canonicalName != "LiteRT-LM (On-device)" }
            .map { spec -> spec.displayName }

        assertEquals(expected, cloud)
        assertTrue(cloud.contains("Ollama"))
        assertTrue(!cloud.contains(ProviderCatalog.ON_DEVICE))
        assertTrue(!cloud.contains(ProviderCatalog.LEGACY_ON_DEVICE))
        assertTrue(!cloud.contains("LiteRT-LM (On-device)"))
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
