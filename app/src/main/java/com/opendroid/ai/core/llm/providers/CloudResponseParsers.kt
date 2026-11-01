package com.opendroid.ai.core.llm.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMException

/** The two fields every cloud completion contributes to an `LLMResponse`. */
internal data class ParsedCompletion(val content: String, val tokensUsed: Int)

/**
 * Null-safe readers for the 2xx bodies of the cloud chat APIs.
 *
 * Gson's `asString`/`asInt` accessors throw `UnsupportedOperationException`,
 * `ClassCastException` or `NullPointerException` on a JSON `null`, a missing
 * key or a wrong type. None of those are recognised by
 * [LLMErrorMapper.fromThrowable], so a response the provider legitimately
 * sends (a refusal with `"content": null`, a tool-call turn, a missing `usage`
 * block) used to surface as a non-retryable `Unknown` error. These readers
 * throw a typed [LLMException] of category `MalformedResponse` instead when the
 * reply genuinely carries no text, and never fail a good completion over an
 * absent token count.
 */
internal object CloudResponseParsers {

    /**
     * OpenAI `chat/completions` shape, shared by OpenAI, Groq, Mistral,
     * Together AI, DeepSeek, OpenRouter and custom OpenAI-compatible hosts.
     *
     * A refusal (`"content": null, "refusal": "..."`) is surfaced as the reply so
     * the user sees why the model declined. A tool-call turn or a 200 that
     * carries only an `error` object (OpenRouter does this) has no text and is
     * malformed.
     */
    fun openAiChatCompletion(body: String, provider: String, model: String): ParsedCompletion {
        val root = parseObject(body, provider, model)
        val message = root.get("choices")
            ?.asArrayOrNull()
            ?.firstOrNull()
            ?.asObjectOrNull()
            ?.get("message")
            ?.asObjectOrNull()
            ?: throw LLMErrorMapper.malformed(provider, model)
        val content = message.get("content")?.asStringOrNull()
            ?: message.get("refusal")?.asStringOrNull()?.takeIf(String::isNotBlank)
            ?: throw LLMErrorMapper.malformed(provider, model)

        val usage = root.get("usage")?.asObjectOrNull()
        val tokensUsed = usage?.get("total_tokens")?.asIntOrNull()
            ?: usage?.sumOfInts("prompt_tokens", "completion_tokens")
            ?: 0

        return ParsedCompletion(content = content, tokensUsed = tokensUsed)
    }

    /**
     * Anthropic Messages shape. `content` is a list of typed blocks; `thinking`
     * and `tool_use` blocks may precede the text, so every `text` block is
     * joined rather than reading index zero.
     */
    fun claudeMessage(body: String, provider: String, model: String): ParsedCompletion {
        val root = parseObject(body, provider, model)
        val blocks = root.get("content")?.asArrayOrNull()
            ?: throw LLMErrorMapper.malformed(provider, model)
        val content = blocks.joinTextBlocks()
            ?: throw LLMErrorMapper.malformed(provider, model)

        val usage = root.get("usage")?.asObjectOrNull()
        val tokensUsed = usage?.sumOfInts("input_tokens", "output_tokens") ?: 0

        return ParsedCompletion(content = content, tokensUsed = tokensUsed)
    }

    /**
     * Cohere v2 `chat` shape. `message.content` is a list of typed blocks and is
     * empty on a tool-call turn. v2 reports `usage.tokens.{input,output}_tokens`,
     * not a total, so the total is their sum.
     */
    fun cohereChat(body: String, provider: String, model: String): ParsedCompletion {
        val root = parseObject(body, provider, model)
        val blocks = root.get("message")
            ?.asObjectOrNull()
            ?.get("content")
            ?.asArrayOrNull()
            ?: throw LLMErrorMapper.malformed(provider, model)
        val content = blocks.joinTextBlocks()
            ?: throw LLMErrorMapper.malformed(provider, model)

        val tokens = root.get("usage")?.asObjectOrNull()?.get("tokens")?.asObjectOrNull()
        val tokensUsed = tokens?.sumOfInts("input_tokens", "output_tokens") ?: 0

        return ParsedCompletion(content = content, tokensUsed = tokensUsed)
    }

    private fun parseObject(body: String, provider: String, model: String): JsonObject {
        if (body.isBlank()) throw LLMErrorMapper.malformed(provider, model)
        val element = try {
            JsonParser.parseString(body)
        } catch (_: JsonParseException) {
            throw LLMErrorMapper.malformed(provider, model)
        }
        return element.asObjectOrNull() ?: throw LLMErrorMapper.malformed(provider, model)
    }

    /**
     * The concatenated `text` of every `text` block, or null when there is none.
     * A block without a `type` but with a string `text` is accepted as text.
     */
    private fun JsonArray.joinTextBlocks(): String? {
        val texts = mapNotNull { block ->
            val obj = block.asObjectOrNull() ?: return@mapNotNull null
            val type = obj.get("type")?.asStringOrNull()
            if (type != null && type != "text") return@mapNotNull null
            obj.get("text")?.asStringOrNull()
        }
        return if (texts.isEmpty()) null else texts.joinToString("")
    }

    /** The sum of the named integer fields, or null when none of them is a number. */
    private fun JsonObject.sumOfInts(vararg keys: String): Int? {
        val values = keys.mapNotNull { key -> get(key)?.asIntOrNull() }
        return if (values.isEmpty()) null else values.sum()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonElement.asArrayOrNull(): JsonArray? = takeIf(JsonElement::isJsonArray)?.asJsonArray

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.asIntOrNull(): Int? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}
