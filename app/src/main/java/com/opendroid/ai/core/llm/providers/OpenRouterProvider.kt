package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenRouterProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "OpenRouter"
    override val availableModels: List<String> = listOf(
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3-8b-instruct:free",
        "gryphe/mythomax-l2-13b:free"
    )

    override val errorProvider = ProviderErrorDetail.Provider.OPENROUTER

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest) = Endpoint(
        url = "https://openrouter.ai/api/v1/chat/completions",
        apiKey = config.requireApiKey(request),
        // OpenRouter attributes traffic to the calling app through these.
        headers = mapOf(
            "HTTP-Referer" to "https://opendroid.ai",
            "X-Title" to "OpenDroid"
        )
    )
}
