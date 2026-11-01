package com.opendroid.ai.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Bounds how much text is collected from a streaming generation.
 *
 * On-device engines keep decoding until they emit an end token or fill the KV
 * cache; LiteRT-LM's `SamplerConfig` / `ConversationConfig` take no output
 * limit. The only way to enforce the output budget from
 * [PromptBudget.outputBudget] is therefore to stop collecting the upstream
 * flow, which cancels the producer. A `return@collect` inside `collect {}`
 * does not do that: it returns from that one emission's lambda and collection
 * (and native decoding) carries on until the context window is full.
 */
object OutputCap {

    /** Outcome of [collectCapped]: the text gathered and whether the cap cut it short. */
    data class Result(val text: String, val truncated: Boolean)

    /**
     * Turns a flow of cumulative text snapshots (each emission is the whole text
     * generated so far) into a flow of the newly appended text. Emissions that
     * do not grow the text are dropped.
     */
    fun snapshotDeltas(snapshots: Flow<String>): Flow<String> = flow {
        var lastLength = 0
        snapshots.collect { fullText ->
            if (fullText.length > lastLength) {
                emit(fullText.substring(lastLength))
                lastLength = fullText.length
            }
        }
    }

    /**
     * Collects [deltas], forwarding each to [onText], until the accumulated text
     * reaches [cap] tokens (per [PromptBudget.estimateTokens]) or the flow
     * completes. Reaching the cap stops the upstream collection, so a producer
     * that never emits an end token does not keep running.
     */
    suspend fun collectCapped(
        deltas: Flow<String>,
        cap: Int,
        onText: suspend (String) -> Unit
    ): Result {
        val accumulated = StringBuilder()
        var truncated = false
        deltas
            .transformWhile { delta ->
                accumulated.append(delta)
                emit(delta)
                val withinBudget = PromptBudget.estimateTokens(accumulated.toString()) < cap
                if (!withinBudget) truncated = true
                withinBudget
            }
            .collect { delta -> onText(delta) }
        return Result(accumulated.toString(), truncated)
    }
}
