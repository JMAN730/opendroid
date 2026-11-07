package com.opendroid.ai.core.llm.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.AIModel
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ModelListParsers
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ChatGPT Codex backend: chat completions through `/responses` (always streamed) and the
 * account's model list through `/models`. Requests are authorized with the ChatGPT sign-in from
 * [ChatGptAuthRepository]; a 401 refreshes the session once and retries.
 *
 * Every call here blocks on the network and must run on an IO dispatcher.
 */
@Singleton
class ChatGptBackend internal constructor(
    client: OkHttpClient,
    private val auth: ChatGptAuthRepository,
    private val baseUrl: String
) {
    @Inject
    constructor(client: OkHttpClient, auth: ChatGptAuthRepository) :
        this(client, auth, ChatGptProtocol.CODEX_BASE_URL)

    /**
     * Reasoning models can stay silent between stream events for longer than the shared
     * client's 15 seconds. newBuilder() shares the connection pool.
     */
    private val streamingClient = client.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val modelsClient = client
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    class Completion(val text: String, val tokensUsed: Int)

    /**
     * Sends [request] to [model] and returns the full reply, passing each text delta to [onDelta]
     * as it arrives.
     */
    suspend fun respond(
        request: LLMRequest,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Completion {
        val body = gson.toJson(responsesBody(request, model)).toRequestBody(jsonType)
        val build = { session: ChatGptSession ->
            authorized(Request.Builder().url("$baseUrl/responses"), session)
                .header("Accept", "text/event-stream")
                .post(body)
                .build()
        }
        var session = auth.validSession()
        var response = streamingClient.newCall(build(session)).execute()
        if (response.code == 401) {
            response.close()
            session = auth.refresh(rejected = session)
            response = streamingClient.newCall(build(session)).execute()
        }
        response.use {
            if (!it.isSuccessful) {
                throw it.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.CHATGPT,
                    request = request.copy(model = model),
                    knownSecrets = session.secrets()
                )
            }
            return readStream(it.body.source(), model, onDelta)
        }
    }

    /** The models this ChatGPT account can use, in the backend's order. */
    suspend fun listModels(): List<AIModel> {
        val build = { session: ChatGptSession ->
            authorized(
                Request.Builder().url("$baseUrl/models?client_version=${ChatGptProtocol.CODEX_CLIENT_VERSION}"),
                session
            ).get().build()
        }
        var session = auth.validSession()
        var response = modelsClient.newCall(build(session)).execute()
        if (response.code == 401) {
            response.close()
            session = auth.refresh(rejected = session)
            response = modelsClient.newCall(build(session)).execute()
        }
        val text = response.use {
            // Status only: the body and URL never reach a message.
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val source = it.body.source()
            if (source.request(MAX_MODELS_BODY_BYTES + 1)) {
                throw IOException("The models endpoint returned an oversized response.")
            }
            source.buffer.readUtf8()
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            throw IOException("The models endpoint returned an unexpected response shape.")
        }
        return ChatGptModelParser.parse(root)
    }

    private fun authorized(builder: Request.Builder, session: ChatGptSession): Request.Builder {
        builder
            .header("Authorization", "Bearer ${session.access}")
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", ChatGptProtocol.CODEX_ORIGINATOR)
            .header("User-Agent", ChatGptProtocol.CODEX_USER_AGENT)
        session.accountId?.let { builder.header("chatgpt-account-id", it) }
        return builder
    }

    private fun responsesBody(request: LLMRequest, model: String): Map<String, Any> = mapOf(
        "model" to model,
        "instructions" to request.systemPrompt,
        "input" to request.messages.mapNotNull(::inputItem),
        "include" to listOf("reasoning.encrypted_content"),
        // The Codex backend only serves streamed, unstored responses.
        "stream" to true,
        "store" to false
    )

    private fun inputItem(message: ChatMessage): Map<String, Any>? {
        if (message.sender == ChatMessage.Sender.USER) {
            val content = buildList {
                if (message.text.isNotEmpty() || message.imageBase64 == null) {
                    add(mapOf("type" to "input_text", "text" to message.text))
                }
                message.imageBase64?.let {
                    add(mapOf("type" to "input_image", "image_url" to "data:image/jpeg;base64,$it"))
                }
            }
            return mapOf("role" to "user", "content" to content)
        }
        if (message.text.isBlank()) return null
        return mapOf(
            "role" to "assistant",
            "content" to listOf(mapOf("type" to "output_text", "text" to message.text))
        )
    }

    private suspend fun readStream(
        source: BufferedSource,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Completion {
        val output = StringBuilder()
        var outputBytes = 0L
        var tokensUsed = 0
        var failure: LLMException? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val line = readLine(source, model)?.trim() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val event = try {
                JsonParser.parseString(payload).takeIf(JsonElement::isJsonObject)?.asJsonObject
            } catch (_: Exception) {
                null
            } ?: continue

            when (event.string("type")) {
                "response.output_text.delta" -> {
                    val delta = event.get("delta")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                        ?: continue
                    outputBytes += delta.toByteArray(StandardCharsets.UTF_8).size
                    if (outputBytes > MAX_OUTPUT_BYTES) throw LLMErrorMapper.malformed(ProviderCatalog.CHATGPT, model)
                    output.append(delta)
                    if (delta.isNotEmpty()) onDelta(delta)
                }
                "response.completed" -> {
                    tokensUsed = event.obj("response")?.obj("usage")?.int("total_tokens") ?: tokensUsed
                }
                "response.failed" -> {
                    failure = streamFailure(event.obj("response")?.obj("error"), model)
                }
                "error" -> {
                    failure = streamFailure(event.obj("error") ?: event, model)
                }
            }
        }

        // A failure after some text still fails the reply: the deltas were already passed to
        // onDelta, so a streaming caller keeps the partial text and marks it incomplete.
        failure?.let { throw it }
        if (output.isBlank()) throw LLMErrorMapper.malformed(ProviderCatalog.CHATGPT, model)
        return Completion(output.toString(), tokensUsed)
    }

    /** Reads one line, bounding its length. Returns null at the end of the stream. */
    private fun readLine(source: BufferedSource, model: String): String? {
        if (source.exhausted()) return null
        val newline = source.indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES)
        if (newline == -1L) {
            if (source.buffer.size >= MAX_LINE_BYTES) throw LLMErrorMapper.malformed(ProviderCatalog.CHATGPT, model)
            return source.readUtf8()
        }
        val line = source.readUtf8(newline)
        source.skip(1)
        return line
    }

    /**
     * Classifies an in-stream failure from its code and type only. The message text is the
     * provider's and is never kept.
     */
    private fun streamFailure(error: JsonObject?, model: String): LLMException {
        val evidence = listOfNotNull(error?.string("code"), error?.string("type"))
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val category = when {
            evidence.contains("usage_limit") || evidence.contains("insufficient_quota") ||
                evidence.contains("quota") -> LLMError.QuotaExhausted
            evidence.contains("rate_limit") -> LLMError.RateLimited
            evidence.contains("context_length") || evidence.contains("invalid_prompt") ||
                evidence.contains("invalid_request") -> LLMError.RequestInvalid
            evidence.contains("model_not_found") || evidence.contains("unsupported_model") ->
                LLMError.ModelUnavailable
            else -> LLMError.ServerError
        }
        return LLMException(
            error = category,
            provider = ProviderCatalog.CHATGPT,
            model = model,
            retryable = category == LLMError.RateLimited || category == LLMError.ServerError
        )
    }

    private companion object {
        const val STREAM_READ_TIMEOUT_SECONDS = 180L
        const val MAX_LINE_BYTES = 1024L * 1024
        const val MAX_OUTPUT_BYTES = 2L * 1024 * 1024
        const val MAX_MODELS_BODY_BYTES = 2L * 1024 * 1024

        fun ChatGptSession.secrets(): List<String> = listOfNotNull(access, refresh, accountId)

        fun JsonObject.obj(key: String): JsonObject? =
            get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

        fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        fun JsonObject.int(key: String): Int? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asInt }.getOrNull() }
    }
}

/**
 * Reads the Codex `/models` response without trusting its shape, as seem_box does: any object
 * with an `id`, `slug`, or `model` is a model, found at the top level or under a list key.
 */
internal object ChatGptModelParser {
    private val LIST_KEYS = listOf("models", "data", "items", "results", "available")

    fun parse(root: JsonElement): List<AIModel> {
        val found = linkedMapOf<String, String?>()
        visit(root, found, depth = 0)
        val recommended = ChatGptProtocol.PREFERRED_MODELS.firstOrNull(found::containsKey)
        return found.map { (id, displayName) ->
            AIModel(
                id = id,
                displayName = displayName ?: ModelListParsers.formatModelName(id),
                provider = ProviderCatalog.CHATGPT,
                isRecommended = id == recommended
            )
        }
    }

    private fun visit(element: JsonElement?, found: MutableMap<String, String?>, depth: Int) {
        if (element == null || depth > MAX_DEPTH) return
        if (element.isJsonArray) {
            element.asJsonArray.forEach { visit(it, found, depth + 1) }
            return
        }
        if (!element.isJsonObject) return
        val record = element.asJsonObject
        val id = sequenceOf("id", "slug", "model")
            .mapNotNull { key -> record.text(key) }
            .firstOrNull()
        if (id != null && id !in found) found[id] = record.text("display_name")
        LIST_KEYS.forEach { key -> visit(record.get(key), found, depth + 1) }
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf(String::isNotEmpty)

    private const val MAX_DEPTH = 8
}
