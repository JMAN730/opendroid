package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.agent.ActionRiskPolicy
import com.opendroid.ai.core.llm.error.LLMError

/** Pure policy for provider fallback ordering and trust-boundary checks. */
object ProviderSelectionPolicy {
    fun explicitFallbacks(
        activeProvider: String,
        configuredFallbacks: Iterable<String>,
        risk: ActionRisk
    ): List<String> {
        if (!ActionRiskPolicy.allowsAutomaticFallback(risk)) return emptyList()

        val active = ProviderCatalog.canonicalName(activeProvider)
        return configuredFallbacks
            .map(ProviderCatalog::canonicalName)
            .filter { ProviderCatalog.isKnown(it) }
            .filter { it != active }
            .distinct()
    }

    fun shouldEscalateMalformed(error: LLMError, hasNextProvider: Boolean): Boolean =
        error == LLMError.MalformedResponse && hasNextProvider

    fun terminalError(activeProvider: String, providerCount: Int): LLMError =
        if (providerCount == 1 && ProviderCatalog.isOnDevice(activeProvider)) {
            LLMError.SafeFallbackUnavailable
        } else {
            LLMError.MalformedResponse
        }
}
