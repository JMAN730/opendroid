package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable
import com.opendroid.ai.core.llm.AIModel

@Serializable
data class LLMConfig(
    val activeProvider: String = "Google Gemini",
    val activeModel: String = "gemini-2.0-flash",
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

data class ApprovalSettings(
    val mode: AutoMode,
    val grantedActions: Set<String>
)

fun LLMConfig.approvalSettings(): ApprovalSettings = ApprovalSettings(
    mode = resolvedAutoMode(),
    grantedActions = effectiveGrantedActions().keys
)
