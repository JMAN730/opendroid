package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable
import com.opendroid.ai.core.llm.AIModel
import com.opendroid.ai.core.llm.ClaudeModelCatalog
import com.opendroid.ai.core.llm.ProviderCatalog

@Serializable
data class LLMConfig(
    val activeProvider: String = "Google Gemini",
    val activeModel: String = "gemini-2.0-flash",
    /**
     * Provider/model pairs. `null` means the setting predates this field and is
     * resolved lazily from [activeProvider]/[activeModel] without an upgrade
     * write. An explicit empty map is a valid migrated value.
     */
    val selectedModels: Map<String, String>? = null,
    val apiKeys: Map<String, String> = emptyMap(), // Provider -> API Key
    val customEndpoints: Map<String, String> = emptyMap(), // Provider -> URL
    // Off by default: LLM-generated plans must be confirmed by the user before
    // executing device actions (calls, messages, settings changes).
    val autoConfirmPlans: Boolean = false,
    // Auto mode (see docs: upstream issue 18 spec). null = never set; resolvedAutoMode()
    // migrates the legacy autoConfirmPlans flag (true behaved like YOLO).
    val autoMode: AutoMode? = null,
    // Action name -> grant timestamp (epoch millis; 0L = seeded default).
    // null = never seeded; effectiveGrantedActions() falls back to defaults.
    // An explicit empty map means "user revoked everything" and stays empty.
    val grantedActions: Map<String, Long>? = null,
    val latencyBenchmarks: Map<String, Long> = emptyMap(), // Provider -> latency Ms
    val elevenLabsApiKey: String = "",
    val elevenLabsVoiceId: String = "",
    val ollamaUrl: String = "",
    val copilotUrl: String = "",
    val multiAgentModeEnabled: Boolean = false,
    val showFloatingButton: Boolean = true,
    val isDarkMode: Boolean = true,
    val lastModelFetch: Map<String, Long> = emptyMap(), // Provider -> last fetch timestamp
    val modelCache: Map<String, List<AIModel>> = emptyMap() // Provider -> cached AIModels list
)

fun LLMConfig.resolvedAutoMode(): AutoMode =
    autoMode ?: if (autoConfirmPlans) AutoMode.YOLO else AutoMode.OFF

fun LLMConfig.effectiveGrantedActions(): Map<String, Long> =
    grantedActions ?: AutoMode.DEFAULT_GRANTS.associateWith { 0L }

fun LLMConfig.selectedModelFor(providerName: String): String {
    val provider = ProviderCatalog.canonicalName(providerName)
    val migratedPairs = selectedModels
        ?.entries
        ?.associate { (key, value) -> ProviderCatalog.canonicalName(key) to value }
    val legacySelection = activeModel.takeIf {
        ProviderCatalog.canonicalName(activeProvider) == provider && it.isNotBlank()
    }
    val selected = migratedPairs?.get(provider)?.takeIf(String::isNotBlank)
        ?: (if (selectedModels == null) legacySelection else null)
        ?: ProviderCatalog.defaultModel(provider)

    return if (provider == "Anthropic Claude") {
        ClaudeModelCatalog.resolve(selected) ?: ClaudeModelCatalog.defaultModelId
    } else {
        selected.trim()
    }
}

fun LLMConfig.withSelectedModel(providerName: String, model: String): LLMConfig {
    val provider = ProviderCatalog.canonicalName(providerName)
    require(ProviderCatalog.isKnown(provider)) { "Unknown LLM provider." }
    val safeModel = if (provider == "Anthropic Claude") {
        ClaudeModelCatalog.resolve(model) ?: ClaudeModelCatalog.defaultModelId
    } else {
        model.trim().ifBlank { ProviderCatalog.defaultModel(provider) }
    }
    val pairs = buildMap {
        selectedModels?.forEach { (key, value) ->
            put(ProviderCatalog.canonicalName(key), value)
        }
        if (selectedModels == null && activeModel.isNotBlank()) {
            put(ProviderCatalog.canonicalName(activeProvider), activeModel)
        }
        put(provider, safeModel)
    }
    return copy(
        activeModel = if (ProviderCatalog.canonicalName(activeProvider) == provider) safeModel else activeModel,
        selectedModels = pairs
    )
}

fun LLMConfig.withActiveProvider(providerName: String): LLMConfig {
    val provider = ProviderCatalog.canonicalName(providerName)
    require(ProviderCatalog.isKnown(provider)) { "Unknown LLM provider." }
    return copy(
        activeProvider = provider,
        activeModel = selectedModelFor(provider)
    )
}
