package com.opendroid.ai.actions

import android.util.Log
import com.opendroid.ai.core.agent.ActionSchema
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin fallback layer for action hallucination.
 *
 * ActionSchema handles all validation. This mapper only handles edge cases
 * where the LLM generates an action name that's NOT in the schema but can
 * be mapped to a valid one. With the schema system, this should rarely fire.
 */
@Singleton
class ActionAutoMapper @Inject constructor() {

    companion object {
        private const val TAG = "ActionAutoMapper"
        /** Sentinel value meaning: remove this step from the plan entirely */
        const val SKIP = "SKIP"
    }

    data class MappingResult(
        val originalAction: String,
        val mappedAction: String?,   // null when SKIP (remove step)
        val wasMapped: Boolean,
        val mappedParams: Map<String, String>
    )

    // ────────────────────────────────────────────────────────────────
    //  Exact mapping table: hallucinated name → correct action
    //  With ActionSchema in place, this should rarely be needed.
    // ────────────────────────────────────────────────────────────────

    private val actionMappings: Map<String, String> = mapOf(
        // ── Contact verification variants → ASK_USER ──
        "CONFIRM_CONTACT"         to "ASK_USER",
        "VERIFY_CONTACT"          to "ASK_USER",
        "VALIDATE_CONTACT"        to "ASK_USER",
        "LOOKUP_CONTACT"          to "ASK_USER",
        "FIND_CONTACT"            to "ASK_USER",
        "SEARCH_CONTACT"          to "ASK_USER",
        "CHECK_CONTACT"           to "ASK_USER",
        "RESOLVE_CONTACT"         to "ASK_USER",
        "GET_CONTACT"             to "ASK_USER",
        "CONTACT_LOOKUP"          to "ASK_USER",

        // ── User prompting variants → ASK_USER ──
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

        // ── Security/privacy check variants → SKIP ──
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
        "VALIDATE_INPUT"          to SKIP,

        // ── App opening variants → OPEN_APP ──
        "OPEN_APP_OR_WEBSITE"     to "OPEN_APP",
        "LAUNCH_APP"              to "OPEN_APP",
        "START_APP"               to "OPEN_APP",
        "RUN_APP"                 to "OPEN_APP",

        // ── Notification/message variants → CHAT ──
        "NOTIFY_USER"             to "CHAT",
        "ALERT_USER"              to "CHAT",
        "INFORM_USER"             to "CHAT",
        "SHOW_MESSAGE"            to "CHAT",
        "DISPLAY_MESSAGE"         to "CHAT",
        "SHOW_NOTIFICATION"       to "CHAT",

        // ── Web/browser variants ──
        "OPEN_WEBSITE"            to "WEB_SEARCH",
        "NAVIGATE_TO"             to "WEB_SEARCH",
        "OPEN_URL"                to "WEB_SEARCH",
        "BROWSE"                  to "WEB_SEARCH",
        "SEARCH_WEB"              to "WEB_SEARCH",
        "GOOGLE"                  to "WEB_SEARCH",
        "GOOGLE_SEARCH"           to "WEB_SEARCH",
        "INTERNET_SEARCH"         to "WEB_SEARCH",

        // ── Screenshot variants ──
        "CAPTURE_SCREEN"          to "TAKE_SCREENSHOT",
        "SCREEN_CAPTURE"          to "TAKE_SCREENSHOT",
        "SCREENSHOT"              to "TAKE_SCREENSHOT",
        "GRAB_SCREEN"             to "TAKE_SCREENSHOT",

        // ── Call variants ──
        "CALL"                    to "MAKE_CALL",
        "PHONE_CALL"              to "MAKE_CALL",
        "DIAL"                    to "MAKE_CALL",
        "PLACE_CALL"              to "MAKE_CALL",

        // ── Message variants ──
        "TEXT"                    to "SEND_SMS",
        "SEND_TEXT"               to "SEND_SMS",
        "SEND_MESSAGE"            to "SEND_WHATSAPP",
        "MESSAGE"                 to "SEND_WHATSAPP",

        // ── WhatsApp-specific hallucinations → SEND_WHATSAPP ──
        "OPEN_WHATSAPP"           to "SEND_WHATSAPP",
        "OPEN_WHATSAPP_CHAT"      to "SEND_WHATSAPP",
        "WHATSAPP_MESSAGE"        to "SEND_WHATSAPP",
        "WHATSAPP_SEND"           to "SEND_WHATSAPP",
        "SEND_WHATSAPP_MESSAGE"   to "SEND_WHATSAPP",
        "LAUNCH_WHATSAPP"         to "SEND_WHATSAPP",

        // ── SMS-specific hallucinations → SEND_SMS ──
        "OPEN_MESSAGES"           to "SEND_SMS",
        "OPEN_SMS"                to "SEND_SMS",
        "SMS_SEND"                to "SEND_SMS",
        "TEXT_MESSAGE"            to "SEND_SMS",

        // ── Call-specific hallucinations → MAKE_CALL ──
        "CALL"                    to "MAKE_CALL",
        "PHONE_CALL"              to "MAKE_CALL",
        "DIAL"                    to "MAKE_CALL",
        "DIAL_NUMBER"             to "MAKE_CALL",
        "CALL_CONTACT"            to "MAKE_CALL",
        "PLACE_CALL"              to "MAKE_CALL",
        "OPEN_DIALER"             to "MAKE_CALL",
        "OPEN_PHONE"              to "MAKE_CALL",
        "RING"                    to "MAKE_CALL",

        // ── Contact-related hallucinations ──
        "RESOLVE_CONTACT"         to SKIP,
        "LOOKUP_CONTACT"          to SKIP,
        "FIND_CONTACT"            to SKIP,
        "SEARCH_CONTACT"          to SKIP,
        "GET_CONTACT"             to SKIP,
        "SELECT_CONTACT"          to SKIP,

        // ── Weather/Search/Browser hallucinations ──
        "CHECK_WEATHER"           to "GET_WEATHER",
        "WEATHER"                 to "GET_WEATHER",
        "SHOW_WEATHER"            to "GET_WEATHER",
        "FETCH_WEATHER"           to "GET_WEATHER",
        "SEARCH"                  to "WEB_SEARCH",
        "GOOGLE_SEARCH"           to "WEB_SEARCH",
        "SEARCH_WEB"              to "WEB_SEARCH",
        "SEARCH_GOOGLE"           to "WEB_SEARCH",
        "FIND_INFORMATION"        to "WEB_SEARCH",
        "LOOK_UP"                 to "WEB_SEARCH",
        "BROWSE"                  to "OPEN_BROWSER",
        "OPEN_GOOGLE"             to "OPEN_BROWSER",
        "LAUNCH_BROWSER"          to "OPEN_BROWSER",
        "OPEN_CHROME"             to "OPEN_BROWSER"
    )

    // ────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────

    /**
     * Map an action name to a registered equivalent.
     * @param registeredActions set of action names that the dispatcher knows
     */
    fun mapAction(
        action: String,
        params: Map<String, String>,
        registeredActions: Set<String>
    ): MappingResult {

        // If valid in ActionSchema → pass through (primary check)
        if (ActionSchema.isValid(action)) {
            return MappingResult(
                originalAction = action,
                mappedAction = action,
                wasMapped = false,
                mappedParams = params
            )
        }

        // Already registered (catches hallucination-trap handlers) → pass through
        if (action in registeredActions) {
            return MappingResult(
                originalAction = action,
                mappedAction = action,
                wasMapped = false,
                mappedParams = params
            )
        }

        val upper = action.uppercase().trim()

        // Try exact match in mapping table
        val exactMatch = actionMappings[upper]
        if (exactMatch != null) {
            Log.d(TAG, "Exact match: $action → $exactMatch")
            return buildMappingResult(action, exactMatch, params)
        }

        // Try fuzzy pattern matching
        val fuzzyMatch = findFuzzyMatch(upper)
        if (fuzzyMatch != null) {
            Log.d(TAG, "Fuzzy match: $action → $fuzzyMatch")
            return buildMappingResult(action, fuzzyMatch, params)
        }

        // Truly unknown → cannot map
        Log.w(TAG, "No mapping found for: $action")
        return MappingResult(
            originalAction = action,
            mappedAction = null,
            wasMapped = false,
            mappedParams = params
        )
    }

    // ────────────────────────────────────────────────────────────────
    //  Fuzzy pattern matching — last resort for truly unknown actions
    // ────────────────────────────────────────────────────────────────

    private fun findFuzzyMatch(upper: String): String? {
        // Anything with PERMISSION/REVIEW/AUDIT/SECURITY/PRIVACY → SKIP
        val skipPatterns = listOf(
            "PERMISSION", "REVIEW", "AUDIT", "SECURITY",
            "PRIVACY", "PROTECT", "MONITOR", "RESTRICT",
            "VERIFY", "VALIDATE", "CONFIRM", "CHECK_APP",
            "SCAN", "ANALYZE_APP", "INSPECT"
        )
        if (skipPatterns.any { upper.contains(it) }) return SKIP

        // Pattern: anything with PROMPT → ASK_USER
        if ("PROMPT" in upper) return "ASK_USER"

        // Pattern: anything with SCREENSHOT → TAKE_SCREENSHOT
        if ("SCREENSHOT" in upper) return "TAKE_SCREENSHOT"

        // Pattern: OPEN_*/LAUNCH_*/START_* → OPEN_APP (but NOT communication apps or browser)
        val communicationKeywords = listOf("WHATSAPP", "SMS", "MESSAGE", "EMAIL", "CALL", "DIALER", "BROWSER", "CHROME")
        if ((upper.startsWith("OPEN_") || upper.startsWith("LAUNCH_") || upper.startsWith("START_")) &&
            communicationKeywords.none { upper.contains(it) }) return "OPEN_APP"

        // Communication OPEN_* patterns → correct action
        if (upper.contains("WHATSAPP")) return "SEND_WHATSAPP"
        if (upper.contains("SMS") || upper.contains("MESSAGE")) return "SEND_SMS"

        // Pattern: anything with WEATHER → GET_WEATHER
        if (upper.contains("WEATHER")) return "GET_WEATHER"

        // Pattern: anything with CALL/DIAL/PHONE (not recall) → MAKE_CALL
        if ((upper.contains("CALL") || upper.contains("DIAL") || upper.contains("PHONE")) &&
            !upper.contains("RECALL")) return "MAKE_CALL"

        // Pattern: anything with BROWSE/CHROME → OPEN_BROWSER
        if (upper.contains("BROWSER") || upper.contains("CHROME")) return "OPEN_BROWSER"

        // Pattern: SEARCH_* or anything with SEARCH → WEB_SEARCH
        if (upper.startsWith("SEARCH_") || upper.contains("SEARCH")) return "WEB_SEARCH"

        // Pattern: CHECK_* → SKIP (generic checks are hallucinations, but not weather/stock)
        if (upper.startsWith("CHECK_") && !upper.contains("WEATHER") && !upper.contains("STOCK")) return SKIP

        // Pattern: SEND_* → SEND_SMS (safe default)
        if (upper.startsWith("SEND_")) return "SEND_SMS"

        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  Build result with corrected params
    // ────────────────────────────────────────────────────────────────

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

        val correctedParams = buildCorrectedParams(
            originalAction = originalAction,
            mappedAction = mappedAction,
            originalParams = originalParams
        )

        return MappingResult(
            originalAction = originalAction,
            mappedAction = mappedAction,
            wasMapped = true,
            mappedParams = correctedParams
        )
    }

    private fun buildCorrectedParams(
        originalAction: String,
        mappedAction: String,
        originalParams: Map<String, String>
    ): Map<String, String> {
        return when (mappedAction) {
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

            "CHAT" -> {
                val message = originalParams["message"]
                    ?: originalParams["text"]
                    ?: originalParams["content"]
                    ?: "Action completed."
                mapOf("message" to message)
            }

            else -> originalParams // Pass through for other mapped actions
        }
    }
}
