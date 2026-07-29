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
 * Single source of truth for the Anthropic Claude models OpenDroid supports.
 *
 * ## Adding or retiring a model
 * 1. Append a [ClaudeModelSpec] to [models], or remove it and add an entry to
 *    [legacyAliases] pointing at its replacement.
 * 2. That's it — the provider, the offline fallback list, the live-fetch
 *    decoration, and the Settings default all read from here.
 *
 * Pure Kotlin: no Android or network dependencies, so it is directly unit-testable.
 */
object ClaudeModelCatalog {

    /** The Claude models Anthropic currently serves and OpenDroid supports. */
    val models: List<ClaudeModelSpec> = listOf(
        ClaudeModelSpec(
            id = "claude-opus-5",
            displayName = "Claude Opus 5",
            isPremium = true
        ),
        ClaudeModelSpec(
            id = "claude-sonnet-5",
            displayName = "Claude Sonnet 5",
            isRecommended = true
        ),
        ClaudeModelSpec(
            id = "claude-haiku-4-5",
            displayName = "Claude Haiku 4.5",
            isFree = true,
            acceptsSamplingParameters = true
        ),
        ClaudeModelSpec(
            id = "claude-opus-4-8",
            displayName = "Claude Opus 4.8"
        ),
        ClaudeModelSpec(
            id = "claude-sonnet-4-6",
            displayName = "Claude Sonnet 4.6",
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
        "claude-haiku-4" to "claude-haiku-4-5",
        // The date-suffixed Haiku ID the provider used to rewrite requests to.
        "claude-haiku-4-5-20251001" to "claude-haiku-4-5",
        // Retired Anthropic 3.x models.
        "claude-3-opus-20240229" to "claude-opus-4-8",
        "claude-3-7-sonnet-20250219" to "claude-sonnet-5",
        "claude-3-5-sonnet-20241022" to "claude-sonnet-5",
        "claude-3-5-sonnet-20240620" to "claude-sonnet-5",
        "claude-3-sonnet-20240229" to "claude-sonnet-5",
        "claude-3-5-haiku-20241022" to "claude-haiku-4-5",
        "claude-3-haiku-20240307" to "claude-haiku-4-5"
    )

    /**
     * Retired Anthropic models with no documented replacement. Selecting one is
     * an error the user has to resolve, not something to migrate silently.
     */
    private val retiredWithoutReplacement: Set<String> = setOf(
        "claude-2.1",
        "claude-2.0",
        "claude-instant-1.2",
        "claude-instant-1"
    )

    /**
     * Shape of an Anthropic model ID. Anything matching this is forwarded to the
     * API as-is so a model released before OpenDroid ships a catalog update stays
     * usable; anything else is rejected before it reaches the request body.
     */
    private val anthropicModelIdPattern = Regex("^claude-[a-z0-9]+(?:[.-][a-z0-9]+)*$")

    private val byId: Map<String, ClaudeModelSpec> = models.associateBy { it.id }

    /**
     * Resolves an arbitrary, untrusted model ID from persisted settings to a
     * model ID that may be sent to Anthropic.
     *
     * @return the catalog ID (unchanged for a current model, the replacement for
     *   a legacy alias), the ID itself for an unknown-but-well-formed Anthropic
     *   model, or `null` when the ID is retired without a replacement, belongs to
     *   another provider, or is malformed.
     */
    fun resolve(id: String): String? {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return null
        if (byId.containsKey(trimmed)) return trimmed
        legacyAliases[trimmed]?.let { return it }
        if (trimmed in retiredWithoutReplacement) return null
        return trimmed.takeIf { anthropicModelIdPattern.matches(it) }
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
