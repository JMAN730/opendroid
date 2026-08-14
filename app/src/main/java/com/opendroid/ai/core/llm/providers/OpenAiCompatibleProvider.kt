package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.llm.toOpenAIMessages
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val WORD_STREAM_DELAY_MS = 50L

/**
 * Replays a finished completion word by word.
 *
 * Most backends here are called through a blocking completion endpoint, but the
 * chat UI consumes a [Flow]. This is a presentation device, not real streaming:
 * no token arrives before the whole response has been received.
 */
internal fun LLMProvider.simulatedWordStream(request: LLMRequest): Flow<String> = flow {
    val response = complete(request)
    for (word in response.content.split(" ")) {
        emit("$word ")
        delay(WORD_STREAM_DELAY_MS)
    }
}

/**
 * The OpenAI `/chat/completions` contract, which most hosted providers speak
 * verbatim. Subclasses supply only what actually differs between them: where to
 * POST, which credential to send, and any provider-specific headers.
 *
 * The default model always comes from [ProviderCatalog] so a provider's seed
 * cannot drift from the one Settings and the model picker use.
 */
abstract class OpenAiCompatibleProvider(
    private val client: OkHttpClient,
    protected val settingsRepository: SettingsRepository
) : LLMProvider {

    /** Identifies this provider in redacted error reports. */
    protected abstract val errorProvider: ProviderErrorDetail.Provider

    /** Where to POST, plus the credential and headers that go with it. */
    protected data class Endpoint(
        val url: String,
        val apiKey: String? = null,
        val headers: Map<String, String> = emptyMap()
    )

    protected abstract suspend fun resolveEndpoint(config: LLMConfig, request: LLMRequest): Endpoint

    /**
     * Used when a request carries no model. Overridden only where the catalog
     * seed is a placeholder rather than something the backend would accept.
     */
    protected open val defaultModel: String get() = ProviderCatalog.defaultModel(name)

    /**
     * POSTs one chat completion and returns the parsed reply.
     *
     * Error bodies never reach the caller verbatim — [toSafeProviderException] classifies
     * them and strips the credential and prompt first, since providers routinely echo both
     * back in failure messages.
     */
    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val endpoint = resolveEndpoint(config, request)
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val startTime = System.currentTimeMillis()

        val body = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "messages" to request.messages.toOpenAIMessages(request.systemPrompt),
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            body["response_format"] = mapOf("type" to "json_object")
        }

        val httpRequest = Request.Builder()
            .url(endpoint.url)
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                endpoint.apiKey?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
                endpoint.headers.forEach { (key, value) -> header(key, value) }
            }
            .build()

        return withContext(Dispatchers.IO) {
            // execute() blocks in a way coroutine cancellation cannot interrupt, so a
            // cancelled request would otherwise hold its thread and socket until the
            // server replied. Bridge cancellation to the call itself, and release the
            // handle on the normal path so a completed request stops pinning the Job.
            val call = client.newCall(httpRequest)
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw response.toSafeProviderException(
                            provider = errorProvider,
                            request = request,
                            knownSecrets = listOfNotNull(endpoint.apiKey)
                        )
                    }
                    val payload = response.body.string()
                    if (payload.isBlank()) throw IOException("Empty response body from $name")

                    val json = gson.fromJson(payload, JsonObject::class.java)
                    val message = json.getAsJsonArray("choices")[0]
                        .asJsonObject
                        .getAsJsonObject("message")

                    LLMResponse(
                        content = message.get("content").asString,
                        tokensUsed = json.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0,
                        model = selectedModel,
                        provider = name,
                        latencyMs = System.currentTimeMillis() - startTime
                    )
                }
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    /**
     * Replays a completed response word by word. Not real streaming — these backends are
     * called through a blocking endpoint, so nothing is emitted until the whole reply
     * has arrived. See [simulatedWordStream].
     */
    override fun streamComplete(request: LLMRequest): Flow<String> = simulatedWordStream(request)

    /**
     * True once a key is stored for this provider. Subclasses reachable without a
     * credential (self-hosted backends) override this.
     */
    override suspend fun isAvailable(): Boolean =
        !settingsRepository.llmConfig.first().apiKeys[name].isNullOrBlank()

    /** The per-request key override, falling back to the stored one. */
    protected fun LLMConfig.apiKeyFor(request: LLMRequest): String? =
        request.providerConfig?.apiKey?.takeIf { it.isNotBlank() } ?: apiKeys[name]

    /** Like [apiKeyFor], but for providers that cannot be called anonymously. */
    protected fun LLMConfig.requireApiKey(request: LLMRequest): String =
        apiKeyFor(request) ?: throw IllegalStateException("API Key for $name is not set.")

    private companion object {
        private val gson = Gson()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
