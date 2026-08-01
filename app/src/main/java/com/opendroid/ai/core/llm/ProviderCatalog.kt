package com.opendroid.ai.core.llm

/**
 * Stable provider identity and defaults used by Settings, request resolution,
 * connection tests, and providers. Provider display strings are persisted, so
 * aliases are normalized here instead of being interpreted ad hoc.
 */
object ProviderCatalog {
    data class ProviderSpec(
        val displayName: String,
        val defaultModel: String,
        val canonicalName: String = displayName
    )

    const val ON_DEVICE = "On-Device AI"
    const val LEGACY_ON_DEVICE = "Gemma 4 (On-device)"

    val providers: List<ProviderSpec> = listOf(
        ProviderSpec("Google Gemini", "gemini-2.0-flash"),
        ProviderSpec("OpenAI", "gpt-4o"),
        ProviderSpec("Anthropic Claude", ClaudeModelCatalog.defaultModelId),
        ProviderSpec("Mistral AI", "mistral-large-latest"),
        ProviderSpec("Groq", "llama-3.3-70b-specdec"),
        ProviderSpec("OpenRouter", "google/gemini-2.0-flash-exp:free"),
        ProviderSpec("Together AI", "meta-llama/Llama-3-70b-chat-hf"),
        ProviderSpec("Cohere", "command-r-plus"),
        ProviderSpec("DeepSeek", "deepseek-chat"),
        ProviderSpec("Copilot API", "gpt-4o"),
        ProviderSpec("Custom OpenAI Compatible", "custom-model"),
        ProviderSpec("Ollama", "llama3"),
        ProviderSpec(ON_DEVICE, "gemma-4-on-device"),
        ProviderSpec("LiteRT-LM (On-device)", "gemma3-1b-it"),
        // Compatibility entry for the directly addressable AI Core backend.
        // Its persisted key is normalized to the unified on-device provider.
        ProviderSpec(LEGACY_ON_DEVICE, "gemma-4-on-device", ON_DEVICE)
    )

    private val byExternalName = providers.associateBy { it.displayName }
    private val byCanonicalName = providers.associateBy { it.canonicalName }

    fun canonicalName(providerName: String): String =
        byExternalName[providerName.trim()]?.canonicalName ?: providerName.trim()

    fun isKnown(providerName: String): Boolean {
        val normalized = canonicalName(providerName)
        return byCanonicalName.containsKey(normalized)
    }

    fun defaultModel(providerName: String): String {
        val normalized = canonicalName(providerName)
        return requireNotNull(byCanonicalName[normalized]) {
            "Unknown LLM provider."
        }.defaultModel
    }

    fun requiresApiKey(providerName: String): Boolean = when (canonicalName(providerName)) {
        "Google Gemini",
        "OpenAI",
        "Anthropic Claude",
        "Mistral AI",
        "Groq",
        "OpenRouter",
        "Together AI",
        "Cohere",
        "DeepSeek",
        "Custom OpenAI Compatible" -> true
        else -> false
    }
}
