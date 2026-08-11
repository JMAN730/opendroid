package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MistralProvider @Inject constructor(
    client: OkHttpClient,
    settingsRepository: SettingsRepository
) : OpenAiCompatibleProvider(client, settingsRepository) {

    override val name: String = "Mistral AI"
    override val availableModels: List<String> = listOf(
        "mistral-large-latest",
        "mistral-medium",
        "mistral-small",
        "open-mixtral-8x7b"
    )

    override val errorProvider = ProviderErrorDetail.Provider.MISTRAL

    override suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest) = Endpoint(
        url = "https://api.mistral.ai/v1/chat/completions",
        apiKey = config.requireApiKey(request)
    )
}
