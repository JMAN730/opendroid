package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CopilotProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "Copilot API"
    override val availableModels: List<String> = listOf("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo")

    override val errorProvider = ProviderErrorDetail.Provider.COPILOT

    override val defaultModel: String = "gpt-4o"

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest): Endpoint {
        val baseUrl = UrlUtils.formatBaseUrl(
            request.providerConfig?.endpoint?.takeIf { it.isNotBlank() } ?: config.copilotUrl,
            ""
        )
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("Copilot server URL is not configured. Set it in Settings.")
        }
        val url = when {
            baseUrl.endsWith("/v1/chat/completions") || baseUrl.endsWith("/chat/completions") -> baseUrl
            baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
            else -> "$baseUrl/v1/chat/completions"
        }
        // Key optional: many self-hosted Copilot proxies bind on LAN without auth.
        return Endpoint(url = url, apiKey = config.apiKeyFor(request))
    }

    override suspend fun isAvailable(): Boolean =
        settingsRepository.llmConfig.first().copilotUrl.trim().isNotEmpty()
}
