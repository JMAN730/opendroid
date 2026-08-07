package com.opendroid.ai.core.llm

/**
 * Describes a single Anthropic Claude model OpenDroid supports.
 */
data class ClaudeModelSpec(
    /** Stable Anthropic model ID, sent verbatim in the request body. */
    val id: String,
    /** Human-readable label shown in the model picker. */
    val displayName: String,
    /** Whether this is the best general-purpose default. */
    val isRecommended: Boolean = false,
    /** Whether this is the top-tier (most expensive) model. */
    val isPremium: Boolean = false,
    /** Whether this is the cheapest/fastest option. */
    val isFree: Boolean = false,
    /**
     * Whether the Anthropic API accepts sampling parameters (`temperature`,
     * `top_p`, `top_k`) for this model. The current Opus-tier and Claude 5
     * models reject them with HTTP 400.
     */
    val acceptsSamplingParameters: Boolean = false
)

/**
 * What OpenDroid knows *about* Anthropic Claude models: display names, badges,
 * which models accept sampling parameters, and how retired IDs migrate.
 *
 * This is not the list of models the picker shows. Anthropic's `/v1/models`
 * response is authoritative there, so a model released after this build is
 * listed and selectable without an app update; the catalog only decorates the
 * entries it recognizes.
 *
 * ## Adding or retiring a model
 * 1. Append a [ClaudeModelSpec] to [models], or remove it and add an entry to
 *    [legacyAliases] pointing at its replacement.
 * 2. That's it — the provider, the live-fetch decoration, and the Settings
 *    default all read from here.
 *
 * Pure Kotlin: no Android or network dependencies, so it is directly unit-testable.
 */
object ClaudeModelCatalog {

    /** The Claude models Anthropic currently serves and OpenDroid supports. */
    val models: List<ClaudeModelSpec> = listOf(
        ClaudeModelSpec(
            id = "claude-fable-5",
            displayName = "Claude Fable 5",
            isPremium = true
        ),
        ClaudeModelSpec(
            id = "claude-opus-5",
            displayName = "Claude Opus 5"
        ),
        ClaudeModelSpec(
            id = "claude-sonnet-5",
            displayName = "Claude Sonnet 5",
            isRecommended = true
        ),
        ClaudeModelSpec(
            id = "claude-opus-4-8",
            displayName = "Claude Opus 4.8"
        ),
        ClaudeModelSpec(
            id = "claude-opus-4-7",
            displayName = "Claude Opus 4.7"
        ),
        ClaudeModelSpec(
            id = "claude-opus-4-6",
            displayName = "Claude Opus 4.6",
            acceptsSamplingParameters = true
        ),
        ClaudeModelSpec(
            id = "claude-opus-4-5-20251101",
            displayName = "Claude Opus 4.5",
            acceptsSamplingParameters = true
        ),
        ClaudeModelSpec(
            id = "claude-sonnet-4-6",
            displayName = "Claude Sonnet 4.6",
            acceptsSamplingParameters = true
        ),
        ClaudeModelSpec(
            id = "claude-sonnet-4-5-20250929",
            displayName = "Claude Sonnet 4.5",
            acceptsSamplingParameters = true
        ),
        ClaudeModelSpec(
            id = "claude-haiku-4-5-20251001",
            displayName = "Claude Haiku 4.5",
            acceptsSamplingParameters = true
        )
    )

    /** The model selected when the user first switches the provider to Claude. */
    const val defaultModelId: String = "claude-sonnet-5"

    /**
     * Previously-persisted or retired model IDs mapped to their documented
     * replacement. Every value must be an ID present in [models].
     */
    private val legacyAliases: Map<String, String> = mapOf(
        // Unversioned family IDs previously accepted by the provider.
        "claude-opus-4" to "claude-opus-4-8",
        "claude-sonnet-4" to "claude-sonnet-4-6",
        "claude-haiku-4" to "claude-haiku-4-5-20251001",
        "claude-haiku-4-5" to "claude-haiku-4-5-20251001",
        // Retired Anthropic 4.0/4.1 models. Keep explicit entries so persisted
        // selections migrate instead of being rejected as unknown.
        "claude-opus-4-0" to "claude-opus-4-8",
        "claude-opus-4-20250514" to "claude-opus-4-8",
        "claude-opus-4-1" to "claude-opus-4-8",
        "claude-opus-4-1-20250805" to "claude-opus-4-8",
        "claude-sonnet-4-0" to "claude-sonnet-4-6",
        "claude-sonnet-4-20250514" to "claude-sonnet-4-6",
        // Retired Anthropic 3.x models.
        "claude-3-opus-20240229" to "claude-opus-4-8",
        "claude-3-7-sonnet-20250219" to "claude-sonnet-4-6",
        "claude-3-5-sonnet-20241022" to "claude-sonnet-4-6",
        "claude-3-5-sonnet-20240620" to "claude-sonnet-4-6",
        "claude-3-sonnet-20240229" to "claude-sonnet-4-6",
        "claude-3-5-haiku-20241022" to "claude-haiku-4-5-20251001",
        "claude-3-haiku-20240307" to "claude-haiku-4-5-20251001",
        // Retired Claude 2 models.
        "claude-2.1" to "claude-opus-4-8",
        "claude-2.0" to "claude-opus-4-8",
        // Retired Claude 1 and Instant models.
        "claude-1.0" to "claude-haiku-4-5-20251001",
        "claude-1.1" to "claude-haiku-4-5-20251001",
        "claude-1.2" to "claude-haiku-4-5-20251001",
        "claude-1.3" to "claude-haiku-4-5-20251001",
        "claude-instant-1.0" to "claude-haiku-4-5-20251001",
        "claude-instant-1.1" to "claude-haiku-4-5-20251001",
        "claude-instant-1.2" to "claude-haiku-4-5-20251001"
    )

    private val byId: Map<String, ClaudeModelSpec> = models.associateBy { it.id }

    /**
     * Resolves an arbitrary, untrusted model ID from persisted settings to a
     * model ID that may be sent to Anthropic.
     *
     * @return the catalog ID (unchanged for a current model, the replacement for
     *   a legacy alias), or `null` when the ID is unknown, retired without a
     *   replacement, belongs to another provider, or is malformed.
     */
    fun resolve(id: String): String? {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return null
        if (byId.containsKey(trimmed)) return trimmed
        legacyAliases[trimmed]?.let { return it }
        return null
    }

    /** The catalog entry for [id], or `null` if OpenDroid does not know the model. */
    fun specFor(id: String): ClaudeModelSpec? = byId[id.trim()]

    /**
     * Whether sampling parameters may be included in a request for [id].
     * Unknown models are treated as rejecting them — omitting a parameter is
     * always safe, sending a rejected one is an HTTP 400.
     */
    fun acceptsSamplingParameters(id: String): Boolean =
        specFor(id)?.acceptsSamplingParameters ?: false
}
