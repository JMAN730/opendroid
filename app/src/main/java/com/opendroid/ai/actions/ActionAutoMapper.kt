package com.opendroid.ai.actions

import android.util.Log
import com.opendroid.ai.core.agent.ActionSchema
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-grade action resolution pipeline.
 *
 * Resolution order:
 *   1. Normalize (trim, uppercase, underscores)
 *   2. Direct schema check
 *   3. Exact alias lookup (200+ mappings)
 *   4. Semantic keyword fallback
 *   5. Graceful failure with suggestion
 *
 * This makes the agent resilient to ANY LLM wording variation.
 */
@Singleton
class ActionAutoMapper @Inject constructor() {

    companion object {
        private const val TAG = "ActionResolver"
        /** Sentinel: remove this step from the plan entirely */
        const val SKIP = "SKIP"
    }

    data class MappingResult(
        val originalAction: String,
        val mappedAction: String?,   // null when SKIP
        val wasMapped: Boolean,
        val mappedParams: Map<String, String>
    )

    // ════════════════════════════════════════════════════════════════
    //  STEP 1: NORMALIZATION
    // ════════════════════════════════════════════════════════════════

    /**
     * Normalizes any raw action string into canonical form.
     * "open url" → "OPEN_URL"
     * "navigate-to-url" → "NAVIGATE_TO_URL"
     * "  Send Whatsapp Message  " → "SEND_WHATSAPP_MESSAGE"
     */
    fun normalizeActionName(raw: String): String {
        return raw.trim()
            .uppercase()
            .replace(Regex("[\\s]+"), "_")   // spaces → underscores
            .replace("-", "_")               // hyphens → underscores
            .replace(Regex("[^A-Z0-9_]"), "") // strip invalid chars
    }

    // ════════════════════════════════════════════════════════════════
    //  STEP 2: ALIAS TABLE — every known LLM hallucination variant
    // ════════════════════════════════════════════════════════════════

    private val actionAliases: Map<String, String> = mapOf(

        // ── URL / Browser Navigation ────────────────────────────────
        "NAVIGATE_TO_URL"         to "OPEN_URL",
        "OPEN_BROWSER_URL"        to "OPEN_URL",
        "BROWSE_WEBSITE"          to "OPEN_URL",
        "GO_TO_URL"               to "OPEN_URL",
        "VISIT_WEBSITE"           to "OPEN_URL",
        "VISIT_URL"               to "OPEN_URL",
        "OPEN_LINK"               to "OPEN_URL",
        "OPEN_WEBSITE"            to "OPEN_URL",
        "NAVIGATE_TO"             to "OPEN_URL",
        "GOTO_URL"                to "OPEN_URL",
        "BROWSE_URL"              to "OPEN_URL",
        "LOAD_URL"                to "OPEN_URL",
        "LOAD_PAGE"               to "OPEN_URL",
        "LOAD_WEBSITE"            to "OPEN_URL",
        "VIEW_URL"                to "OPEN_URL",
        "VIEW_WEBSITE"            to "OPEN_URL",
        "NAVIGATE_URL"            to "OPEN_URL",
        "OPEN_WEB_PAGE"           to "OPEN_URL",
        "OPEN_WEBPAGE"            to "OPEN_URL",
        "GO_TO_WEBSITE"           to "OPEN_URL",

        // ── Browser open variants ───────────────────────────────────
        "LAUNCH_BROWSER"          to "OPEN_BROWSER",
        "START_BROWSER"           to "OPEN_BROWSER",
        "OPEN_CHROME"             to "OPEN_BROWSER",
        "LAUNCH_CHROME"           to "OPEN_BROWSER",
        "OPEN_GOOGLE"             to "OPEN_BROWSER",
        "BROWSE"                  to "OPEN_BROWSER",

        // ── Web search variants ─────────────────────────────────────
        "SEARCH"                  to "WEB_SEARCH",
        "GOOGLE_SEARCH"           to "WEB_SEARCH",
        "SEARCH_WEB"              to "WEB_SEARCH",
        "SEARCH_GOOGLE"           to "WEB_SEARCH",
        "INTERNET_SEARCH"         to "WEB_SEARCH",
        "FIND_INFORMATION"        to "WEB_SEARCH",
        "LOOK_UP"                 to "WEB_SEARCH",
        "LOOKUP"                  to "WEB_SEARCH",
        "GOOGLE"                  to "WEB_SEARCH",
        "SEARCH_ONLINE"           to "WEB_SEARCH",
        "QUERY_WEB"               to "WEB_SEARCH",
        "FIND_ONLINE"             to "WEB_SEARCH",

        // ── Weather variants ────────────────────────────────────────
        "CHECK_WEATHER"           to "GET_WEATHER",
        "WEATHER"                 to "GET_WEATHER",
        "SHOW_WEATHER"            to "GET_WEATHER",
        "FETCH_WEATHER"           to "GET_WEATHER",
        "WEATHER_CHECK"           to "GET_WEATHER",
        "WEATHER_FORECAST"        to "GET_WEATHER",
        "GET_FORECAST"            to "GET_WEATHER",
        "CHECK_FORECAST"          to "GET_WEATHER",
        "CURRENT_WEATHER"         to "GET_WEATHER",

        // ── Call variants ───────────────────────────────────────────
        "CALL"                    to "MAKE_CALL",
        "PHONE_CALL"              to "MAKE_CALL",
        "DIAL"                    to "MAKE_CALL",
        "DIAL_NUMBER"             to "MAKE_CALL",
        "CALL_CONTACT"            to "MAKE_CALL",
        "PLACE_CALL"              to "MAKE_CALL",
        "RING"                    to "MAKE_CALL",
        "MAKE_PHONE_CALL"         to "MAKE_CALL",
        "CALL_NUMBER"             to "MAKE_CALL",
        "PHONE"                   to "MAKE_CALL",
        "RING_CONTACT"            to "MAKE_CALL",
        "DIAL_CONTACT"            to "MAKE_CALL",
        "OPEN_DIALER"             to "MAKE_CALL",
        "OPEN_PHONE"              to "MAKE_CALL",

        // ── WhatsApp variants ───────────────────────────────────────
        "OPEN_WHATSAPP"           to "SEND_WHATSAPP",
        "OPEN_WHATSAPP_CHAT"      to "SEND_WHATSAPP",
        "WHATSAPP_MESSAGE"        to "SEND_WHATSAPP",
        "WHATSAPP_SEND"           to "SEND_WHATSAPP",
        "SEND_WHATSAPP_MESSAGE"   to "SEND_WHATSAPP",
        "LAUNCH_WHATSAPP"         to "SEND_WHATSAPP",
        "MESSAGE_WHATSAPP"        to "SEND_WHATSAPP",
        "TEXT_WHATSAPP"            to "SEND_WHATSAPP",
        "WHATSAPP"                to "SEND_WHATSAPP",

        // ── SMS variants ────────────────────────────────────────────
        "OPEN_MESSAGES"           to "SEND_SMS",
        "OPEN_SMS"                to "SEND_SMS",
        "SMS_SEND"                to "SEND_SMS",
        "TEXT_MESSAGE"            to "SEND_SMS",
        "TEXT"                    to "SEND_SMS",
        "SEND_TEXT"               to "SEND_SMS",
        "TEXT_CONTACT"            to "SEND_SMS",
        "SEND_TEXT_MESSAGE"       to "SEND_SMS",
        "SMS"                     to "SEND_SMS",

        // ── Generic message (default → WhatsApp) ────────────────────
        "SEND_MESSAGE"            to "SEND_WHATSAPP",
        "MESSAGE"                 to "SEND_WHATSAPP",
        "MESSAGE_CONTACT"         to "SEND_WHATSAPP",

        // ── Email variants ──────────────────────────────────────────
        "EMAIL"                   to "SEND_EMAIL",
        "MAIL"                    to "SEND_EMAIL",
        "COMPOSE_EMAIL"           to "SEND_EMAIL",
        "WRITE_EMAIL"             to "SEND_EMAIL",
        "NEW_EMAIL"               to "SEND_EMAIL",

        // ── App opening variants ────────────────────────────────────
        "OPEN_APP_OR_WEBSITE"     to "OPEN_APP",
        "LAUNCH_APP"              to "OPEN_APP",
        "START_APP"               to "OPEN_APP",
        "RUN_APP"                 to "OPEN_APP",
        "EXECUTE_APP"             to "OPEN_APP",

        // ── Screenshot variants ─────────────────────────────────────
        "CAPTURE_SCREEN"          to "TAKE_SCREENSHOT",
        "SCREEN_CAPTURE"          to "TAKE_SCREENSHOT",
        "SCREENSHOT"              to "TAKE_SCREENSHOT",
        "GRAB_SCREEN"             to "TAKE_SCREENSHOT",
        "SNAP_SCREEN"             to "TAKE_SCREENSHOT",
        "SCREENGRAB"              to "TAKE_SCREENSHOT",

        // ── Flashlight variants ─────────────────────────────────────
        "FLASHLIGHT"              to "TOGGLE_FLASHLIGHT",
        "TORCH"                   to "TOGGLE_FLASHLIGHT",
        "FLASH"                   to "TOGGLE_FLASHLIGHT",
        "TURN_ON_FLASHLIGHT"      to "TOGGLE_FLASHLIGHT",
        "TURN_OFF_FLASHLIGHT"     to "TOGGLE_FLASHLIGHT",

        // ── Informational/display variants → DISPLAY_INFO (auto-completing) ──
        "NOTIFY_USER"             to "DISPLAY_INFO",
        "ALERT_USER"              to "DISPLAY_INFO",
        "INFORM_USER"             to "DISPLAY_INFO",
        "SHOW_MESSAGE"            to "DISPLAY_INFO",
        "DISPLAY_MESSAGE"         to "DISPLAY_INFO",
        "SHOW_NOTIFICATION"       to "DISPLAY_INFO",
        "RESPOND"                 to "DISPLAY_INFO",
        "REPLY"                   to "DISPLAY_INFO",
        "CHAT"                    to "DISPLAY_INFO",
        "SHOW_TOAST"              to "DISPLAY_INFO",
        "TOAST"                   to "DISPLAY_INFO",
        "LOG_INFO"                to "DISPLAY_INFO",
        "LOG_MESSAGE"             to "DISPLAY_INFO",
        "LOG"                     to "DISPLAY_INFO",
        "PRINT_MESSAGE"           to "DISPLAY_INFO",
        "SHOW_STATUS"             to "DISPLAY_INFO",
        "DISPLAY_STATUS"          to "DISPLAY_INFO",
        "DISPLAY_INFO"            to "DISPLAY_INFO",
        "SHOW_INFO"               to "DISPLAY_INFO",
        "DISPLAY_RESULT"          to "DISPLAY_INFO",
        "SHOW_RESULT"             to "DISPLAY_INFO",
        "OUTPUT"                  to "DISPLAY_INFO",
        "SHOW_OUTPUT"             to "DISPLAY_INFO",
        "REPORT"                  to "DISPLAY_INFO",
        "SHOW_REPORT"             to "DISPLAY_INFO",
        "SNACKBAR"                to "DISPLAY_INFO",
        "SHOW_SNACKBAR"           to "DISPLAY_INFO",

        // ── User input/prompt variants → ASK_USER ───────────────────
        "PROMPT_USER"             to "ASK_USER",
        "PROMPT_USER_SELECTION"   to "ASK_USER",
        "ASK_CONFIRMATION"        to "ASK_USER",
        "CONFIRM_ACTION"          to "ASK_USER",
        "REQUEST_CONFIRMATION"    to "ASK_USER",
        "GET_USER_INPUT"          to "ASK_USER",
        "USER_PROMPT"             to "ASK_USER",
        "CONFIRM_USER"            to "ASK_USER",
        "CONFIRM"                 to "ASK_USER",
        "PROMPT"                  to "ASK_USER",
        "REQUEST_INPUT"           to "ASK_USER",
        "USER_INPUT"              to "ASK_USER",
        "GET_INPUT"               to "ASK_USER",

        // ── Alarm/Timer variants ────────────────────────────────────
        "ALARM"                   to "SET_ALARM",
        "CREATE_ALARM"            to "SET_ALARM",
        "ADD_ALARM"               to "SET_ALARM",
        "TIMER"                   to "SET_TIMER",
        "CREATE_TIMER"            to "SET_TIMER",
        "START_TIMER"             to "SET_TIMER",
        "COUNTDOWN"               to "SET_TIMER",

        // ── Music variants ──────────────────────────────────────────
        "PLAY"                    to "PLAY_MUSIC",
        "PLAY_SONG"               to "PLAY_MUSIC",
        "PLAY_AUDIO"              to "PLAY_MUSIC",
        "MUSIC"                   to "PLAY_MUSIC",
        "PLAY_VIDEO"              to "PLAY_YOUTUBE",
        "WATCH_VIDEO"             to "PLAY_YOUTUBE",
        "WATCH_YOUTUBE"           to "PLAY_YOUTUBE",
        "YOUTUBE"                 to "PLAY_YOUTUBE",
        "STOP_MUSIC"              to "PAUSE_MUSIC",
        "STOP"                    to "PAUSE_MUSIC",

        // ── Volume variants ─────────────────────────────────────────
        "VOLUME"                  to "SET_VOLUME",
        "VOLUME_UP"               to "SET_VOLUME",
        "VOLUME_DOWN"             to "SET_VOLUME",
        "CHANGE_VOLUME"           to "SET_VOLUME",

        // ── Brightness variants ─────────────────────────────────────
        "BRIGHTNESS"              to "SET_BRIGHTNESS",
        "CHANGE_BRIGHTNESS"       to "SET_BRIGHTNESS",
        "ADJUST_BRIGHTNESS"       to "SET_BRIGHTNESS",

        // ── Navigation/directions variants ──────────────────────────
        "NAVIGATE"                to "GET_DIRECTIONS",
        "DIRECTIONS"              to "GET_DIRECTIONS",
        "MAP"                     to "GET_DIRECTIONS",
        "MAPS"                    to "GET_DIRECTIONS",
        "ROUTE"                   to "GET_DIRECTIONS",
        "FIND_ROUTE"              to "GET_DIRECTIONS",

        // ── Security/privacy hallucinations → SKIP ──────────────────
        "CONFIRM_CONTACT"         to SKIP,
        "VERIFY_CONTACT"          to SKIP,
        "VALIDATE_CONTACT"        to SKIP,
        "RESOLVE_CONTACT"         to SKIP,
        "SELECT_CONTACT"          to SKIP,
        "SECURITY_CHECK"          to SKIP,
        "PRIVACY_CHECK"           to SKIP,
        "SECURE_ENVIRONMENT"      to SKIP,
        "CHECK_SECURITY"          to SKIP,
        "VERIFY_SECURITY"         to SKIP,
        "ENSURE_SECURITY"         to SKIP,
        "SAFETY_CHECK"            to SKIP,
        "VERIFY_PERMISSIONS"      to SKIP,
        "CHECK_PERMISSIONS"       to SKIP,
        "CHECK_APP"               to SKIP,
        "VERIFY_APP"              to SKIP,
        "CHECK_APP_INSTALLED"     to SKIP,
        "CONFIRM_APP"             to SKIP,
        "VALIDATE_APP"            to SKIP,
        "ENSURE_APP"              to SKIP,
        "CHECK_HARDWARE"          to SKIP,
        "CHECK_PERMISSION"        to SKIP,
        "SHOW_WARNING"            to SKIP,
        "CONFIRM_DETAILS"         to SKIP,
        "CONFIRM_RECIPIENT"       to SKIP,
        "CONFIRM_MESSAGE"         to SKIP,
        "VERIFY_DETAILS"          to SKIP,
        "VALIDATE_INPUT"          to SKIP
    )

    // ════════════════════════════════════════════════════════════════
    //  STEP 3: SEMANTIC KEYWORD FALLBACK
    // ════════════════════════════════════════════════════════════════

    /**
     * Keyword-based semantic matching. Ordered by specificity (most
     * specific patterns first to avoid false positives).
     */
    private data class SemanticRule(
        val keywords: List<String>,
        val excludeKeywords: List<String> = emptyList(),
        val resolvedAction: String
    )

    private val semanticRules = listOf(
        // Skip patterns (highest priority)
        SemanticRule(listOf("PERMISSION"), emptyList(), SKIP),
        SemanticRule(listOf("SECURITY"), emptyList(), SKIP),
        SemanticRule(listOf("PRIVACY"), emptyList(), SKIP),
        SemanticRule(listOf("AUDIT"), emptyList(), SKIP),
        SemanticRule(listOf("INSPECT"), emptyList(), SKIP),
        SemanticRule(listOf("VALIDATE"), listOf("INPUT"), SKIP),
        SemanticRule(listOf("VERIFY"), listOf("CONTACT"), SKIP),
        SemanticRule(listOf("CONFIRM"), emptyList(), SKIP),
        SemanticRule(listOf("PROTECT"), emptyList(), SKIP),
        SemanticRule(listOf("MONITOR"), emptyList(), SKIP),
        SemanticRule(listOf("RESTRICT"), emptyList(), SKIP),

        // Prompt / user input
        SemanticRule(listOf("PROMPT"), emptyList(), "ASK_USER"),

        // Screenshot
        SemanticRule(listOf("SCREENSHOT"), emptyList(), "TAKE_SCREENSHOT"),
        SemanticRule(listOf("SCREEN", "CAPTURE"), emptyList(), "TAKE_SCREENSHOT"),

        // WhatsApp (before generic MESSAGE)
        SemanticRule(listOf("WHATSAPP"), emptyList(), "SEND_WHATSAPP"),

        // SMS (before generic MESSAGE)
        SemanticRule(listOf("SMS"), emptyList(), "SEND_SMS"),

        // Email
        SemanticRule(listOf("EMAIL"), emptyList(), "SEND_EMAIL"),
        SemanticRule(listOf("MAIL"), listOf("VOICEMAIL"), "SEND_EMAIL"),

        // Generic message → WhatsApp default
        SemanticRule(listOf("MESSAGE"), listOf("SHOW", "DISPLAY", "ERROR"), "SEND_WHATSAPP"),

        // Weather (before SEARCH)
        SemanticRule(listOf("WEATHER"), emptyList(), "GET_WEATHER"),
        SemanticRule(listOf("FORECAST"), emptyList(), "GET_WEATHER"),

        // URL / website (before generic BROWSER)
        SemanticRule(listOf("URL"), emptyList(), "OPEN_URL"),
        SemanticRule(listOf("WEBSITE"), emptyList(), "OPEN_URL"),
        SemanticRule(listOf("WEBPAGE"), emptyList(), "OPEN_URL"),
        SemanticRule(listOf("WEB", "PAGE"), emptyList(), "OPEN_URL"),

        // Browser
        SemanticRule(listOf("BROWSER"), emptyList(), "OPEN_BROWSER"),
        SemanticRule(listOf("CHROME"), emptyList(), "OPEN_BROWSER"),

        // Call (exclude RECALL)
        SemanticRule(listOf("CALL"), listOf("RECALL", "VIDEO"), "MAKE_CALL"),
        SemanticRule(listOf("DIAL"), emptyList(), "MAKE_CALL"),
        SemanticRule(listOf("PHONE"), listOf("HEADPHONE"), "MAKE_CALL"),

        // Search
        SemanticRule(listOf("SEARCH"), emptyList(), "WEB_SEARCH"),

        // Music/Media
        SemanticRule(listOf("YOUTUBE"), emptyList(), "PLAY_YOUTUBE"),
        SemanticRule(listOf("MUSIC"), listOf("STOP", "PAUSE", "VOLUME"), "PLAY_MUSIC"),
        SemanticRule(listOf("PLAY"), listOf("STORE"), "PLAY_MUSIC"),

        // Alarm/Timer
        SemanticRule(listOf("ALARM"), emptyList(), "SET_ALARM"),
        SemanticRule(listOf("TIMER"), emptyList(), "SET_TIMER"),
        SemanticRule(listOf("COUNTDOWN"), emptyList(), "SET_TIMER"),
        SemanticRule(listOf("REMIND"), emptyList(), "SET_REMINDER"),

        // Navigation
        SemanticRule(listOf("DIRECTION"), emptyList(), "GET_DIRECTIONS"),
        SemanticRule(listOf("NAVIGATE"), listOf("URL", "WEB", "BROWSER"), "GET_DIRECTIONS"),

        // Flashlight
        SemanticRule(listOf("FLASHLIGHT"), emptyList(), "TOGGLE_FLASHLIGHT"),
        SemanticRule(listOf("TORCH"), emptyList(), "TOGGLE_FLASHLIGHT"),

        // System toggles
        SemanticRule(listOf("WIFI"), emptyList(), "TOGGLE_WIFI"),
        SemanticRule(listOf("BLUETOOTH"), emptyList(), "TOGGLE_BLUETOOTH"),
        SemanticRule(listOf("HOTSPOT"), emptyList(), "TOGGLE_HOTSPOT"),

        // Brightness / Volume
        SemanticRule(listOf("BRIGHTNESS"), emptyList(), "SET_BRIGHTNESS"),
        SemanticRule(listOf("VOLUME"), emptyList(), "SET_VOLUME"),

        // OPEN_*/LAUNCH_*/START_* generic → OPEN_APP (last resort)
        SemanticRule(listOf("OPEN_"), emptyList(), "OPEN_APP"),
        SemanticRule(listOf("LAUNCH_"), emptyList(), "OPEN_APP"),
        SemanticRule(listOf("START_"), emptyList(), "OPEN_APP"),

        // SEND_* generic → SEND_SMS (safe default)
        SemanticRule(listOf("SEND_"), emptyList(), "SEND_SMS"),

        // CHECK_* (but NOT weather/stock)
        SemanticRule(listOf("CHECK_"), listOf("WEATHER", "STOCK", "BALANCE"), SKIP)
    )

    // ════════════════════════════════════════════════════════════════
    //  PUBLIC API — full resolution pipeline
    // ════════════════════════════════════════════════════════════════

    fun mapAction(
        action: String,
        params: Map<String, String>,
        registeredActions: Set<String>
    ): MappingResult {
        val normalized = normalizeActionName(action)

        Log.d(TAG, "── Action Resolution ──────────────────")
        Log.d(TAG, "  Original:   $action")
        Log.d(TAG, "  Normalized: $normalized")

        // ── Layer 1: Direct schema match ──
        if (ActionSchema.isValid(normalized)) {
            Log.d(TAG, "  Result:     SCHEMA_VALID → $normalized")
            return MappingResult(action, normalized, action != normalized, params)
        }

        // ── Layer 2: Registered handler match ──
        if (normalized in registeredActions) {
            Log.d(TAG, "  Result:     REGISTERED → $normalized")
            return MappingResult(action, normalized, action != normalized, params)
        }

        // ── Layer 3: Exact alias lookup ──
        val aliasMatch = actionAliases[normalized]
        if (aliasMatch != null) {
            Log.d(TAG, "  Alias:      $normalized → $aliasMatch")
            Log.d(TAG, "  Result:     ALIAS_RESOLVED → $aliasMatch")
            return buildMappingResult(action, aliasMatch, params)
        }

        // ── Layer 4: Semantic keyword fallback ──
        val semanticMatch = findSemanticMatch(normalized)
        if (semanticMatch != null) {
            Log.d(TAG, "  Semantic:   $normalized → $semanticMatch")
            Log.d(TAG, "  Result:     SEMANTIC_RESOLVED → $semanticMatch")
            return buildMappingResult(action, semanticMatch, params)
        }

        // ── Layer 5: Unknown — suggest closest ──
        val suggestion = findClosestAction(normalized)
        Log.w(TAG, "  Result:     UNKNOWN (no match)")
        Log.w(TAG, "  Suggestion: $suggestion")
        return MappingResult(
            originalAction = action,
            mappedAction = null,
            wasMapped = false,
            mappedParams = params
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Semantic matcher
    // ════════════════════════════════════════════════════════════════

    private fun findSemanticMatch(normalized: String): String? {
        for (rule in semanticRules) {
            val allKeywordsMatch = rule.keywords.all { normalized.contains(it) }
            val noExcludesMatch = rule.excludeKeywords.none { normalized.contains(it) }
            if (allKeywordsMatch && noExcludesMatch) {
                return rule.resolvedAction
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    //  Closest action suggestion (for debug/logging)
    // ════════════════════════════════════════════════════════════════

    private fun findClosestAction(normalized: String): String {
        val allActions = ActionSchema.getAllActionNames()

        // Try longest common substring match
        return allActions
            .maxByOrNull { action ->
                val words = normalized.split("_")
                words.count { word -> action.contains(word) }
            } ?: "CHAT"
    }

    // ════════════════════════════════════════════════════════════════
    //  Build result with corrected params
    // ════════════════════════════════════════════════════════════════

    private fun buildMappingResult(
        originalAction: String,
        mappedAction: String,
        originalParams: Map<String, String>
    ): MappingResult {
        if (mappedAction == SKIP) {
            return MappingResult(
                originalAction = originalAction,
                mappedAction = null,
                wasMapped = true,
                mappedParams = emptyMap()
            )
        }

        val correctedParams = correctParams(originalAction, mappedAction, originalParams)
        return MappingResult(
            originalAction = originalAction,
            mappedAction = mappedAction,
            wasMapped = true,
            mappedParams = correctedParams
        )
    }

    private fun correctParams(
        originalAction: String,
        mappedAction: String,
        originalParams: Map<String, String>
    ): Map<String, String> {
        return when (mappedAction) {
            "OPEN_URL" -> {
                // Normalize URL param name variants
                val url = originalParams["url"]
                    ?: originalParams["link"]
                    ?: originalParams["website"]
                    ?: originalParams["page"]
                    ?: originalParams["address"]
                    ?: originalParams["href"]
                if (url != null) mapOf("url" to url) else originalParams
            }

            "ASK_USER" -> {
                val contact = originalParams["contact"]
                val question = when {
                    contact != null ->
                        "What is $contact's phone number?"
                    originalParams.containsKey("message") ->
                        "Who should I send this message to?"
                    else ->
                        "Could you provide more details for: ${originalAction.lowercase().replace("_", " ")}?"
                }
                mapOf("question" to question)
            }

            "OPEN_APP" -> {
                val appName = originalParams["appName"]
                    ?: originalParams["app"]
                    ?: originalParams["name"]
                    ?: originalParams["packageName"]
                    ?: "the requested app"
                mapOf("appName" to appName)
            }

            "DISPLAY_INFO" -> {
                val message = originalParams["message"]
                    ?: originalParams["text"]
                    ?: originalParams["content"]
                    ?: originalParams["info"]
                    ?: originalParams["notification"]
                    ?: originalParams["warning"]
                    ?: originalParams["status"]
                    ?: "Action completed."
                mapOf("message" to message)
            }

            "WEB_SEARCH" -> {
                val query = originalParams["query"]
                    ?: originalParams["search"]
                    ?: originalParams["q"]
                    ?: originalParams["text"]
                    ?: originalParams["keyword"]
                if (query != null) mapOf("query" to query) else originalParams
            }

            "GET_WEATHER" -> {
                val location = originalParams["location"]
                    ?: originalParams["city"]
                    ?: originalParams["place"]
                if (location != null) mapOf("location" to location) else originalParams
            }

            else -> originalParams
        }
    }
}
