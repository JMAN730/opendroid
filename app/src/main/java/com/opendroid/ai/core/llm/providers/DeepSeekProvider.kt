package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepSeekProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "DeepSeek"
    override val availableModels: List<String> = listOf("deepseek-chat", "deepseek-reasoner")

    override val errorProvider = ProviderErrorDetail.Provider.DEEPSEEK

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest) = Endpoint(
        url = "https://api.deepseek.com/chat/completions",
        apiKey = config.requireApiKey(request)
    )
}
