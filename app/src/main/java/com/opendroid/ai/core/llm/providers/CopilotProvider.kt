package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CopilotProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Copilot API"
    override val availableModels: List<String> = listOf("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val baseUrl = UrlUtils.formatBaseUrl(
            request.providerConfig?.endpoint?.takeIf { it.isNotBlank() } ?: config.copilotUrl,
            ""
        )
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("Copilot server URL is not configured. Set it in Settings.")
        }
        val endpoint = when {
            baseUrl.endsWith("/v1/chat/completions") || baseUrl.endsWith("/chat/completions") -> baseUrl
            baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
            else -> "$baseUrl/v1/chat/completions"
        }

        val startTime = System.currentTimeMillis()

        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: "gpt-4o"

        // Build messages payload
        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to messagesList,
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )

        if (request.responseFormat == ResponseFormat.JSON) {
            requestBodyMap["response_format"] = mapOf("type" to "json_object")
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody(mediaType))

        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() } ?: config.apiKeys[name]
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return withContext(Dispatchers.IO) {
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.COPILOT,
                    request = request,
                    knownSecrets = listOfNotNull(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                throw IOException("Empty response body from Copilot API")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val choices = jsonResponse.getAsJsonArray("choices")
            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            val content = messageObj.get("content").asString

            val usage = jsonResponse.getAsJsonObject("usage")
            val tokensUsed = usage?.get("total_tokens")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = tokensUsed,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val response = complete(request)
        val words = response.content.split(" ")
        for (word in words) {
            emit("$word ")
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return config.copilotUrl.trim().isNotEmpty()
    }

}
