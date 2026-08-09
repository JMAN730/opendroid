package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor

/**
 * Runtime authorization gate for provider selection. Persisted provider names
 * and client-side availability are not authorization; cloud providers need a
 * non-empty credential in the authenticated credential snapshot, and endpoint-
 * based providers need their endpoint as well.
 */
object ProviderAuthorization {
    fun isConfigured(config: LLMConfig, providerName: String): Boolean {
        val provider = ProviderCatalog.canonicalName(providerName)
        if (!ProviderCatalog.isKnown(provider)) return false

        val model = config.selectedModelFor(provider)
        if (ProviderCatalog.requiresApiKey(provider) &&
            !(provider == "Google Gemini" && model == "gemini-nano") &&
            config.apiKeys[provider].isNullOrBlank()
        ) {
            return false
}
        return when (provider) {
            "Custom OpenAI Compatible" ->
                config.customEndpoints[provider].orEmpty().isNotBlank()
            "Copilot API" -> config.copilotUrl.trim().isNotBlank()
            "Ollama" -> config.ollamaUrl.trim().isNotBlank()
            else -> true
        }
    }
}
