package com.opendroid.ai.actions

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 2 defense against LLM action hallucination.
 *
 * Intercepts every action BEFORE dispatch. If the action name is not
 * registered in ActionDispatcher, this mapper tries to convert it to
 * a known equivalent. This is the safety net that catches anything
 * Layer 1 (positive-only prompt) misses.
 *
 * The LLM constantly invents new action name variations:
 *   VERIFY_CONTACT → CONFIRM_CONTACT → VALIDATE_CONTACT → CHECK_CONTACT
 * Blocking individual names is whack-a-mole. This mapper catches ALL
 * variations via exact + fuzzy pattern matching.
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
    //  Master mapping: hallucinated name → correct registered action
    //  Add every hallucinated name you encounter. This list only grows.
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

        // ── App verification variants → OPEN_APP ──
        "VERIFY_APP"              to "OPEN_APP",
        "CHECK_APP"               to "OPEN_APP",
        "CHECK_APP_INSTALLED"     to "OPEN_APP",
        "CONFIRM_APP"             to "OPEN_APP",
        "VALIDATE_APP"            to "OPEN_APP",
        "ENSURE_APP"              to "OPEN_APP",
        "OPEN_APP_OR_WEBSITE"     to "OPEN_APP",
        "LAUNCH_APP"              to "OPEN_APP",
        "START_APP"               to "OPEN_APP",
        "RUN_APP"                 to "OPEN_APP",

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

        // ── Phone lookup variants → ASK_USER ──
        "VERIFY_PHONE"            to "ASK_USER",
        "CHECK_PHONE"             to "ASK_USER",
        "LOOKUP_PHONE"            to "ASK_USER",
        "GET_PHONE_NUMBER"        to "ASK_USER",
        "FIND_PHONE_NUMBER"       to "ASK_USER",

        // ── Notification/message variants → CHAT ──
        "NOTIFY_USER"             to "CHAT",
        "ALERT_USER"              to "CHAT",
        "INFORM_USER"             to "CHAT",
        "SHOW_MESSAGE"            to "CHAT",
        "DISPLAY_MESSAGE"         to "CHAT",
        "SHOW_NOTIFICATION"       to "CHAT",

        // ── Web/browser variants ──
        "OPEN_WEBSITE"            to "OPEN_BROWSER",
        "NAVIGATE_TO"             to "OPEN_BROWSER",
        "OPEN_URL"                to "OPEN_BROWSER",
        "BROWSE"                  to "OPEN_BROWSER",
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
        "MESSAGE"                 to "SEND_WHATSAPP"
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

        // Already registered → no mapping needed
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
    //  Fuzzy pattern matching — catches variations we haven't seen yet
    // ────────────────────────────────────────────────────────────────

    private fun findFuzzyMatch(upper: String): String? {
        // Pattern: anything with CONTACT → ASK_USER
        if ("CONTACT" in upper) return "ASK_USER"

        // Pattern: anything with CONFIRM/VERIFY/VALIDATE → ASK_USER
        if ("CONFIRM" in upper) return "ASK_USER"
        if ("VERIFY" in upper) return "ASK_USER"
        if ("VALIDATE" in upper) return "ASK_USER"

        // Pattern: anything with PROMPT → ASK_USER
        if ("PROMPT" in upper) return "ASK_USER"

        // Pattern: anything with SECURITY/PRIVACY → SKIP
        if ("SECURITY" in upper) return SKIP
        if ("PRIVACY" in upper) return SKIP

        // Pattern: anything with SCREENSHOT → TAKE_SCREENSHOT
        if ("SCREENSHOT" in upper) return "TAKE_SCREENSHOT"

        // Pattern: OPEN_*/LAUNCH_*/START_* → OPEN_APP
        if (upper.startsWith("OPEN_") ||
            upper.startsWith("LAUNCH_") ||
            upper.startsWith("START_")) return "OPEN_APP"

        // Pattern: SEARCH_* → WEB_SEARCH
        if (upper.startsWith("SEARCH_")) return "WEB_SEARCH"

        // Pattern: CHECK_* → ASK_USER (generic check = ask user)
        if (upper.startsWith("CHECK_")) return "ASK_USER"

        // Pattern: SEND_* → try to match a known send action
        if (upper.startsWith("SEND_")) return "SEND_SMS" // safe default

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
