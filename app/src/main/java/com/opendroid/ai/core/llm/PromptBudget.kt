package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * Token budgeting for on-device models with small, fixed context windows.
 *
 * LiteRT-LM engines abort natively (SIGABRT in liblitertlm_jni) when the prompt
 * exceeds the engine's `maxNumTokens` / the model's KV-cache size — that kills
 * the whole process as a force close, with no catchable exception. This object
 * lets providers check the prompt against the model's context window *before*
 * handing it to the native runtime, so oversized prompts surface as a normal
 * error message instead of a crash.
 *
 * LiteRT-LM 0.14 can only report tokens already in a conversation, not count a
 * prompt before sending it, so the counts here are an estimate built to run high.
 */
object PromptBudget {

    /**
     * Cost of a char, in thirds of a token. About 3 ASCII chars make a token. Qwen's
     * pre-tokenizer splits digits one per token and cuts the chars on either side away
     * from them, so those are whole tokens too. A non-ASCII char is at least one token,
     * so a surrogate pair (most emoji) counts as two, and the space or punctuation
     * right after one does not merge into it, so it is a whole token too.
     */
    private const val UNITS_PER_TOKEN = 3

    /** ChatML wrapping added by the engine around the prompt, including a default system line. */
    private const val TEMPLATE_TOKENS = 32

    /** "Conversation History:\n" and the closing "Model:" around the messages. */
    private const val CONVERSATION_FRAME_TOKENS = 10

    /** ~4 tokens for message prefix/newline. */
    private const val MESSAGE_FRAME_TOKENS = 4

    /** Smallest output allocation worth running inference for. */
    const val MIN_OUTPUT_TOKENS = 64

    private fun isWholeToken(c: Char): Boolean = c.code >= 0x80 || c in '0'..'9'

    private fun unitsAt(text: String, index: Int): Int {
        val mergesIntoAsciiRun = !isWholeToken(text[index]) &&
            (index == 0 || !isWholeToken(text[index - 1])) &&
            (index == text.lastIndex || text[index + 1] !in '0'..'9')
        return if (mergesIntoAsciiRun) 1 else UNITS_PER_TOKEN
    }

    /** Estimates the token count of [text] as an upper bound, rounding up. */
    fun estimateTokens(text: String): Int =
        (text.indices.sumOf { unitsAt(text, it) } + UNITS_PER_TOKEN - 1) / UNITS_PER_TOKEN

    /** The longest start of [text] that [estimateTokens] puts within [maxTokens]. */
    fun takeFirstTokens(text: String, maxTokens: Int): String {
        var units = 0
        var end = 0
        while (end < text.length) {
            units += unitsAt(text, end)
            if (units > maxTokens * UNITS_PER_TOKEN) break
            end++
        }
        if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    /**
     * [systemPrompt], cut to two thirds of a [contextWindow]-token context if it is over. At 3 ASCII
     * chars per token that is 2 chars per window token, so English prompts keep the length they had
     * at 4 chars per token and half the window, and the planning prompt keeps its context.
     */
    fun trimSystemPrompt(systemPrompt: String, contextWindow: Int): String {
        val head = takeFirstTokens(systemPrompt, contextWindow * 2 / 3)
        return if (head.length < systemPrompt.length) {
            head + "\n...[instructions truncated for on-device context]"
        } else {
            systemPrompt
        }
    }

    /** The longest end of [text] that [estimateTokens] puts within [maxTokens]. */
    private fun takeLastTokens(text: String, maxTokens: Int): String {
        var units = 0
        var start = text.length
        while (start > 0) {
            units += unitsAt(text, start - 1)
            if (units > maxTokens * UNITS_PER_TOKEN) break
            start--
        }
        if (start in 1 until text.length && text[start].isLowSurrogate()) start++
        return text.substring(start)
    }

    /** Tokens to budget for [prompt]: the estimate, about 10% on top, and the chat template. */
    fun budgetedPromptTokens(prompt: String): Int {
        val estimate = estimateTokens(prompt)
        return estimate + (estimate + 9) / 10 + TEMPLATE_TOKENS
    }

    /**
     * Returns how many output tokens a request may use once [prompt] is loaded
     * into a [contextWindow]-token context: [requestedOutputTokens] when there is
     * room, clamped down to the remaining space otherwise, or `null` when fewer
     * than [MIN_OUTPUT_TOKENS] would remain — i.e. the prompt is too long to run.
     */
    fun outputBudget(prompt: String, contextWindow: Int, requestedOutputTokens: Int): Int? {
        val remaining = contextWindow - budgetedPromptTokens(prompt)
        if (remaining < MIN_OUTPUT_TOKENS) return null
        return minOf(requestedOutputTokens, remaining)
    }

    /**
     * Output tokens to request for a plan on a [contextWindow]-token model. In a 1280-token window
     * the on-device planning prompt (520 real Qwen tokens) and a ~250-token request leave no room
     * for the usual 512.
     */
    fun planOutputTokens(contextWindow: Int): Int = if (contextWindow <= 1280) 256 else 512

    /**
     * Collects the deltas in [chunks], handing each to [onText], until they end or their text
     * reaches [cap] tokens, and returns that text. Reaching the cap stops the collection of
     * [chunks] but not native decoding, which the caller still has to cancel.
     */
    suspend fun collectCapped(chunks: Flow<String>, cap: Int, onText: suspend (String) -> Unit): String {
        val text = StringBuilder()
        chunks.firstOrNull { chunk ->
            text.append(chunk)
            onText(chunk)
            estimateTokens(text.toString()) >= cap
        }
        return text.toString()
    }

    /**
     * Fits a list of conversation messages into a prompt budget within [contextWindow] tokens,
     * reserving [reservedOutputTokens] for generation, so that the assembled prompt then passes
     * [outputBudget].
     *
     * Ensures:
     * 1. The latest message is always retained, using the output reservation down to
     *    [MIN_OUTPUT_TOKENS] before it is truncated.
     * 2. System prompt and tool schemas are respected via [overheadTokens], their [estimateTokens] count.
     * 3. Prior messages are included from newest to oldest up to available budget.
     */
    fun fitMessagesToBudget(
        messages: List<ChatMessage>,
        contextWindow: Int,
        reservedOutputTokens: Int = MIN_OUTPUT_TOKENS * 2,
        overheadTokens: Int = 0
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        // Inverts budgetedPromptTokens: the largest estimate whose budgeted total fits after the output.
        fun messageBudget(outputTokens: Int): Int =
            (contextWindow - outputTokens - TEMPLATE_TOKENS - 1) * 10 / 11 - overheadTokens - CONVERSATION_FRAME_TOKENS

        val latestMessage = messages.last()
        val latestBudget = (messageBudget(MIN_OUTPUT_TOKENS) - MESSAGE_FRAME_TOKENS).coerceAtLeast(MIN_OUTPUT_TOKENS)
        val trimmedLatest = latestMessage.copy(text = takeLastTokens(latestMessage.text, latestBudget))

        var remainingTokens = messageBudget(reservedOutputTokens) -
            MESSAGE_FRAME_TOKENS - estimateTokens(trimmedLatest.text)
        val selectedPriorMessages = mutableListOf<ChatMessage>()

        for (i in messages.size - 2 downTo 0) {
            val msg = messages[i]
            val msgTokens = estimateTokens(msg.text) + MESSAGE_FRAME_TOKENS
            if (remainingTokens >= msgTokens) {
                selectedPriorMessages.add(msg)
                remainingTokens -= msgTokens
            } else {
                break
            }
        }

        return selectedPriorMessages.reversed() + trimmedLatest
    }
}
