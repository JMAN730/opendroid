package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroqProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "Groq"
    override val availableModels: List<String> = listOf(
        "llama-3.3-70b-versatile",
        "llama3-70b-8192",
        "gemma2-9b-it",
        "mixtral-8x7b-32768"
    )

    override val errorProvider = ProviderErrorDetail.Provider.GROQ

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest) = Endpoint(
        url = "https://api.groq.com/openai/v1/chat/completions",
        apiKey = config.requireApiKey(request)
    )
}
