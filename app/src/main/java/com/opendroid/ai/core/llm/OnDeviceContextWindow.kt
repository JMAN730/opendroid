package com.opendroid.ai.core.llm

/**
 * User-selectable context-window sizes for LiteRT-LM models.
 *
 * Each [OnDeviceModelSpec.contextWindow] is the size the catalog knows is safe
 * for that artifact. Raising it lets longer conversations and multi-step plans
 * run, but the engine allocates a bigger KV cache: on a low-RAM device, or on a
 * `.task` build whose cache size is baked into the file (e.g. `...ekv1280.task`),
 * a larger value can fail to load. [LiteRTLMProvider] therefore falls back to the
 * catalog size when an enlarged engine will not initialize.
 */
object OnDeviceContextWindow {

    /** Smallest window worth offering: below this even a short plan cannot run. */
    const val MIN_TOKENS = 1024

    /** Upper bound on what the picker offers, per product decision. */
    const val MAX_TOKENS = 32768

    /** Sizes shown in the picker, in addition to the model's own default. */
    private val OFFERED = listOf(1280, 2048, 4096, 8192, 16384, 32768)

    /** Confines a persisted or user-entered value to the supported range. */
    fun clamp(tokens: Int): Int = tokens.coerceIn(MIN_TOKENS, MAX_TOKENS)

    /**
     * The choices to show for [spec]: the model's own default plus every offered
     * size, ascending and de-duplicated.
     */
    fun optionsFor(spec: OnDeviceModelSpec): List<Int> = optionsFor(spec.contextWindow)

    /** As [optionsFor], for callers that hold only the model's default size. */
    fun optionsFor(defaultWindow: Int): List<Int> =
        (OFFERED + defaultWindow)
            .map(::clamp)
            .distinct()
            .sorted()

    /** Compact label for a token count, e.g. 8192 -> "8K", 1280 -> "1280". */
    fun label(tokens: Int): String =
        if (tokens >= 1024 && tokens % 1024 == 0) "${tokens / 1024}K" else tokens.toString()
}
