package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.llm.toOpenAIMessages
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
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenCode Go exposes an OpenAI-compatible chat-completions shape, but its
 * gateway rejects requests that do not identify the calling agent and a
 * gateway session. That is why it cannot be represented by the generic
 * "Custom OpenAI Compatible" provider: those two headers are mandatory.
 */
@Singleton
class OpencodeGoProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = OpencodeGoProtocol.PROVIDER_NAME
    override val availableModels: List<String> = listOf(OpencodeGoProtocol.DEFAULT_MODEL)

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf(String::isNotBlank)
            ?: config.apiKeys[name]?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("API Key for $name is not set.")

        val configuredEndpoint = request.providerConfig?.endpoint?.takeIf(String::isNotBlank)
            ?: config.customEndpoints[name]?.takeIf(String::isNotBlank)
        val baseUrl = UrlUtils.formatBaseUrl(configuredEndpoint, OpencodeGoProtocol.DEFAULT_BASE_URL)
        val selectedModel = request.model?.takeIf(String::isNotBlank)
            ?: OpencodeGoProtocol.DEFAULT_MODEL
        val startTime = System.currentTimeMillis()

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to request.messages.toOpenAIMessages(request.systemPrompt),
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            requestBodyMap["response_format"] = mapOf("type" to "json_object")
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .apply {
                OpencodeGoProtocol.headers(apiKey).forEach { (headerName, headerValue) ->
                    header(headerName, headerValue)
                }
            }
            .post(gson.toJson(requestBodyMap).toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw response.toSafeProviderException(
                        provider = ProviderErrorDetail.Provider.OPENCODE_GO,
                        request = request,
                        knownSecrets = listOf(apiKey)
                    )
                }
                val responseBody = response.body.string()
                if (responseBody.isBlank()) {
                    throw IOException("Empty response body from $name")
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
        }
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
        return !config.apiKeys[name].isNullOrBlank()
    }
}

/**
 * Wire-level constants and headers shared by chat requests and model discovery.
 *
 * The chat and models endpoints deliberately differ: the gateway serves chat
 * completions under `/inference/go/openai/v1`, while its model catalog lives
 * under `/zen/go/v1`.
 */
internal object OpencodeGoProtocol {
    const val PROVIDER_NAME = "Opencode-Go"
    const val DEFAULT_MODEL = "beam-free"
    const val DEFAULT_BASE_URL = "https://opencode.ai/inference/go/openai/v1"
    const val MODELS_URL = "https://opencode.ai/zen/go/v1/models"

    private const val SESSION_PREFIX = "ses_"
    private const val SESSION_BYTES = 16
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * One session id for the process. The gateway uses it for request affinity,
     * and [LLMRequest] carries no finer-grained conversation id to key on.
     */
    val sessionId: String by lazy { newSessionId() }

    fun userAgent(): String = "OpenDroid/${BuildConfig.VERSION_NAME}"

    fun headers(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "User-Agent" to userAgent(),
        "x-opencode-session" to sessionId
    )

    /** `ses_` followed by exactly 32 lowercase hex characters, as the gateway requires. */
    internal fun newSessionId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(SESSION_BYTES).also(random::nextBytes)
        return buildString(SESSION_PREFIX.length + SESSION_BYTES * 2) {
            append(SESSION_PREFIX)
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }
}
