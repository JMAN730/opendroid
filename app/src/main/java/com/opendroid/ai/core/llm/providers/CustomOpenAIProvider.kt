package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomOpenAIProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "Custom OpenAI Compatible"
    override val availableModels: List<String> = listOf("custom-model")

    override val errorProvider = ProviderErrorDetail.Provider.CUSTOM_OPENAI

    // Catalog seed is "custom-model"; backends rarely accept that literal name.
    override val defaultModel: String = "gpt-4o"

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest): Endpoint {
        // No silent fallback to api.openai.com. This provider exists to reach a
        // user-supplied backend, so an unconfigured endpoint must fail loudly rather
        // than ship the prompt (and any key) to a host the user never named.
        val configured = request.providerConfig?.endpoint?.takeIf { it.isNotBlank() }
            ?: config.customEndpoints[name]?.takeIf { it.isNotBlank() }
        val baseUrl = UrlUtils.formatBaseUrl(configured)
        check(baseUrl.isNotEmpty()) {
            "$name has no endpoint configured. Set one in Settings before using this provider."
        }
        // Authorization is only attached when the key is non-blank (base class).
        // Local/self-hosted OpenAI-compatible servers often need no key.
        return Endpoint(
            url = "$baseUrl/chat/completions",
            apiKey = config.apiKeyFor(request)
        )
    }

    // Configured via free-form URL; any install can attempt a call.
    override suspend fun isAvailable(): Boolean = true
}
