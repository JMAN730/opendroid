package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAIProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "OpenAI"
    override val availableModels: List<String> =
        listOf("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo", "o1-preview", "o3-mini")

    override val errorProvider = ProviderErrorDetail.Provider.OPENAI

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest) = Endpoint(
        url = "https://api.openai.com/v1/chat/completions",
        apiKey = config.requireApiKey(request)
    )
}
