package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TogetherAIProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "Together AI"
    override val availableModels: List<String> = listOf(
        "meta-llama/Llama-3-70b-chat-hf",
        "meta-llama/Llama-3-8b-chat-hf",
        "Qwen/Qwen1.5-72B-Chat"
    )

    override val errorProvider = ProviderErrorDetail.Provider.TOGETHER_AI

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest) = Endpoint(
        url = "https://api.together.xyz/v1/chat/completions",
        apiKey = config.requireApiKey(request)
    )
}
