package com.opendroid.ai.core.llm.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.error.LLMErrorMapper

internal data class ParsedCompletion(val content: String, val tokensUsed: Int)

/**
 * Reads successful (2xx) provider responses without trusting their shape.
 *
 * A response with no usable text is a typed malformed failure. A missing or
 * unusable token count is only 0: it must not cost the caller a good reply.
 */
internal object ProviderResponseParser {

    /** A `refusal` arrives with null content, so it is the reply the user gets. */
    fun openAiChatCompletion(body: String, provider: String, model: String): ParsedCompletion {
        val root = rootObject(body, provider, model)
        val message = root.arrayOrNull("choices")
            ?.firstOrNull()
            ?.asObjectOrNull()
            ?.objectOrNull("message")
        val content = message?.stringOrNull("content")
            ?: message?.stringOrNull("refusal")?.takeIf { it.isNotBlank() }
            ?: throw LLMErrorMapper.malformed(provider, model)
        return ParsedCompletion(content, root.objectOrNull("usage").intOrZero("total_tokens"))
    }

    fun claudeMessage(body: String, provider: String, model: String): ParsedCompletion {
        val root = rootObject(body, provider, model)
        val content = root.joinedTextBlocks()
            ?: throw LLMErrorMapper.malformed(provider, model)
        val usage = root.objectOrNull("usage")
        return ParsedCompletion(content, usage.intOrZero("input_tokens") + usage.intOrZero("output_tokens"))
    }

    /** Cohere v2 reports input and output counts under `usage.tokens`, with no total. */
    fun cohereMessage(body: String, provider: String, model: String): ParsedCompletion {
        val root = rootObject(body, provider, model)
        val content = root.objectOrNull("message")?.joinedTextBlocks()
            ?: throw LLMErrorMapper.malformed(provider, model)
        val tokens = root.objectOrNull("usage")?.objectOrNull("tokens")
        return ParsedCompletion(content, tokens.intOrZero("input_tokens") + tokens.intOrZero("output_tokens"))
    }

    private fun rootObject(body: String, provider: String, model: String): JsonObject {
        val root = try {
            JsonParser.parseString(body)
        } catch (_: JsonParseException) {
            throw LLMErrorMapper.malformed(provider, model)
        }
        return root.asObjectOrNull() ?: throw LLMErrorMapper.malformed(provider, model)
    }

    /** Other block types (`thinking`, `tool_use`) can come before or between the text blocks. */
    private fun JsonObject.joinedTextBlocks(): String? {
        val texts = arrayOrNull("content")
            ?.mapNotNull { block ->
                block.asObjectOrNull()
                    ?.takeIf { it.stringOrNull("type") == "text" }
                    ?.stringOrNull("text")
            }
            .orEmpty()
        return texts.takeIf { it.isNotEmpty() }?.joinToString("")
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.asObjectOrNull()

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject?.intOrZero(key: String): Int {
        val value = this?.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?: return 0
        return try {
            value.asInt
        } catch (_: NumberFormatException) {
            0
        }
    }
}
