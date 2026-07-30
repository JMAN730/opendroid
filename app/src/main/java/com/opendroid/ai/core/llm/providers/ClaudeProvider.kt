package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
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
class ClaudeProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Anthropic Claude"
    override val availableModels: List<String> = ClaudeModelCatalog.models.map { it.id }

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = config.apiKeys[name] ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        // The persisted model ID is untrusted input: resolve it against the catalog
        // (migrating legacy IDs) rather than sending it to Anthropic verbatim.
        val selectedModel = if (config.activeModel.isBlank()) {
            ClaudeModelCatalog.defaultModelId
        } else {
            ClaudeModelCatalog.resolve(config.activeModel)
                ?: throw IllegalStateException(
                    "The selected Claude model \"${config.activeModel}\" is no longer supported. " +
                        "Please pick another model in Settings."
                )
        }

        val messagesList = mutableListOf<Map<String, Any>>()
        request.messages.forEach { msg ->
            val role = if (msg.sender == com.opendroid.ai.data.models.ChatMessage.Sender.USER) "user" else "assistant"
            if (msg.imageBase64 != null && role == "user") {
                messagesList.add(
                    mapOf(
                        "role" to role,
                        "content" to listOf(
                            mapOf("type" to "text", "text" to msg.text),
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to "image/jpeg",
                                    "data" to msg.imageBase64
                                )
                            )
                        )
                    )
                )
            } else {
                messagesList.add(mapOf("role" to role, "content" to msg.text))
            }
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "system" to request.systemPrompt,
            "messages" to messagesList,
            "max_tokens" to request.maxTokens
        )
        // Current Opus-tier models reject sampling parameters with HTTP 400.
        if (ClaudeModelCatalog.acceptsSamplingParameters(selectedModel)) {
            requestBodyMap["temperature"] = request.temperature
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                // Never surface or log the raw Anthropic body: it can echo request
                // content and credentials into logcat and bug reports.
                throw IOException("Claude request failed with HTTP ${response.code}.")
            }
            val responseBody = response.body?.string() ?: throw IOException("Empty response body from Claude")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val contentArray = jsonResponse.getAsJsonArray("content")
            val content = contentArray[0].asJsonObject.get("text").asString

            val usage = jsonResponse.getAsJsonObject("usage")
            val inputTokens = usage?.get("input_tokens")?.asInt ?: 0
            val outputTokens = usage?.get("output_tokens")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = inputTokens + outputTokens,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            val response = complete(request)
            val words = response.content.split(" ")
            for (word in words) {
                emit("$word ")
                kotlinx.coroutines.delay(50)
            }
        } catch (e: IllegalStateException) {
            // Configuration problems the user can fix (unsupported model, missing key)
            // are surfaced as-is: a clear instruction, not an exception dump.
            emit(e.message ?: "Claude is not configured correctly. Check Settings.")
        } catch (e: Exception) {
            emit("Error streaming Claude: ${e.localizedMessage}")
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
