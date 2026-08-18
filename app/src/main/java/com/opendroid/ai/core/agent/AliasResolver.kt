package com.opendroid.ai.core.agent

import com.opendroid.ai.core.util.DurationParser

/**
 * Alias resolver that maps common natural language phrases
 * directly to action hints. When a match is found, the AgentLoop
 * can bypass the LLM entirely and execute the action directly.
 *
 * This gives OpenDroid "common sense" vocabulary — the user says
 * "flash", "torch", or "light" and the flashlight toggles immediately.
 */
object AliasResolver {

    data class ActionHint(
        val action: String,
        val baseParams: Map<String, String>
    )

    private val aliases: Map<String, ActionHint> = mapOf(

        // ── FLASHLIGHT (ambiguous = toggle, explicit = on/off) ──
        // Toggle aliases — flip current state
        "flash"             to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "flashlight"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "torch"             to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "torchlight"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "light"             to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "open flash"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "open torch"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        "open flashlight"   to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "toggle")),
        // Explicit on
        "turn on flash"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "turn on torch"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "turn on flashlight" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "turn flash on"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "turn torch on"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "turn flashlight on" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "flash on"          to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "torch on"          to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "flashlight on"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "enable flash"      to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "enable torch"      to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        // Explicit off
        "turn off flash"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn off torch"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn off flashlight" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn flash off"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn torch off"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn flashlight off" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "flash off"         to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "torch off"         to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "flashlight off"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "disable flash"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "disable torch"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "close flash"       to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "close torch"       to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),

        // ── SCREENSHOT ──────────────────────────────────
        "screenshot"            to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "take screenshot"       to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "take a screenshot"     to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "screen shot"           to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "capture screen"        to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "capture screenshot"    to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "snap screen"           to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "screengrab"            to ActionHint("TAKE_SCREENSHOT", emptyMap()),
        "screen capture"        to ActionHint("TAKE_SCREENSHOT", emptyMap()),

        // ── VISION / ANALYZE SCREENSHOT ─────────────────
        "analyze screen"                to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "analyse screen"                to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "what's on screen"              to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "what's on my screen"           to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "whats on screen"               to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "whats on my screen"            to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "what do you see"               to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "read screen"                   to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "read my screen"                to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "describe screen"               to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "describe my screen"            to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "screenshot and analyze"        to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "take screenshot and analyze"   to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "look at screen"                to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),
        "look at my screen"             to ActionHint("ANALYZE_SCREENSHOT", emptyMap()),

        // ── SCREEN UNDERSTANDING & NOTES ─────────────────
        "read screen and remember"          to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "read and remember screen"          to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "read this screen and remember"     to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "remember this screen"              to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "remember this"                     to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "save this information"             to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "save this info"                    to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "save this screen"                  to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "important information")),
        "save screen to notes"              to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "notes")),
        "save this screen to notes"         to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "notes")),
        "save this to my notes"             to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "notes")),
        "save this to notes"                to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "notes")),
        "add this to my notes"              to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "notes")),
        "add this to notes"                 to ActionHint("READ_AND_REMEMBER_SCREEN", mapOf("topic" to "notes")),
        "read my notes"                     to ActionHint("READ_NOTES", emptyMap()),
        "read notes"                        to ActionHint("READ_NOTES", emptyMap()),
        "show my notes"                     to ActionHint("READ_NOTES", emptyMap()),
        "show notes"                        to ActionHint("READ_NOTES", emptyMap()),
        "view my notes"                     to ActionHint("READ_NOTES", emptyMap()),
        "view notes"                        to ActionHint("READ_NOTES", emptyMap()),
        "list my notes"                     to ActionHint("READ_NOTES", emptyMap()),
        "list notes"                        to ActionHint("READ_NOTES", emptyMap()),
        "show knowledge graph"              to ActionHint("QUERY_KNOWLEDGE_GRAPH", emptyMap()),
        "show my knowledge graph"           to ActionHint("QUERY_KNOWLEDGE_GRAPH", emptyMap()),
        "view knowledge graph"              to ActionHint("QUERY_KNOWLEDGE_GRAPH", emptyMap()),
        "my knowledge graph"                to ActionHint("QUERY_KNOWLEDGE_GRAPH", emptyMap()),
        "who do i contact most often"       to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "CONTACT")),
        "who do i talk to most"             to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "CONTACT")),
        "frequently contacted"              to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "CONTACT")),
        "top contacts"                      to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "CONTACT")),
        "what are my active projects"       to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "PROJECT")),
        "show my projects"                  to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "PROJECT")),
        "what are my routines"              to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "TASK_ROUTINE")),
        "show my routines"                  to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "TASK_ROUTINE")),
        "show my preferences"               to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "USER_PREFERENCE")),
        "what are my preferences"           to ActionHint("QUERY_KNOWLEDGE_GRAPH", mapOf("category" to "USER_PREFERENCE")),

        // ── WIFI ─────────────────────────────────────────
        "wifi"              to ActionHint("TOGGLE_WIFI", mapOf("state" to "toggle")),
        "wifi on"           to ActionHint("TOGGLE_WIFI", mapOf("state" to "on")),
        "wifi off"          to ActionHint("TOGGLE_WIFI", mapOf("state" to "off")),
        "turn on wifi"      to ActionHint("TOGGLE_WIFI", mapOf("state" to "on")),
        "turn off wifi"     to ActionHint("TOGGLE_WIFI", mapOf("state" to "off")),
        "enable wifi"       to ActionHint("TOGGLE_WIFI", mapOf("state" to "on")),
        "disable wifi"      to ActionHint("TOGGLE_WIFI", mapOf("state" to "off")),
        "open wifi"         to ActionHint("TOGGLE_WIFI", mapOf("state" to "on")),
        "start wifi"        to ActionHint("TOGGLE_WIFI", mapOf("state" to "on")),
        "internet on"       to ActionHint("TOGGLE_WIFI", mapOf("state" to "on")),
        "internet off"      to ActionHint("TOGGLE_WIFI", mapOf("state" to "off")),

        // ── BLUETOOTH ────────────────────────────────────
        "bluetooth"         to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "toggle")),
        "bluetooth on"      to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "on")),
        "bluetooth off"     to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "off")),
        "bt on"             to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "on")),
        "bt off"            to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "off")),
        "turn on bluetooth" to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "on")),
        "turn off bluetooth" to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "off")),
        "open bluetooth"    to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "on")),
        "start bluetooth"   to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "on")),
        "enable bluetooth"  to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "on")),
        "disable bluetooth" to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "off")),
        "close bluetooth"   to ActionHint("TOGGLE_BLUETOOTH", mapOf("state" to "off")),

        // ── VOLUME ───────────────────────────────────────
        "mute"              to ActionHint("SET_VOLUME", mapOf("type" to "ring", "level" to "0")),
        "unmute"            to ActionHint("SET_VOLUME", mapOf("type" to "ring", "level" to "50")),
        "silent"            to ActionHint("SET_VOLUME", mapOf("type" to "ring", "level" to "0")),
        "silent mode"       to ActionHint("SET_VOLUME", mapOf("type" to "ring", "level" to "0")),
        "loud"              to ActionHint("SET_VOLUME", mapOf("type" to "ring", "level" to "100")),
        "volume up"         to ActionHint("SET_VOLUME", mapOf("type" to "media", "level" to "80")),
        "volume down"       to ActionHint("SET_VOLUME", mapOf("type" to "media", "level" to "30")),
        "max volume"        to ActionHint("SET_VOLUME", mapOf("type" to "media", "level" to "100")),

        // ── SCREEN LOCK ──────────────────────────────────
        "lock"              to ActionHint("LOCK_SCREEN", emptyMap()),
        "lock phone"        to ActionHint("LOCK_SCREEN", emptyMap()),
        "lock screen"       to ActionHint("LOCK_SCREEN", emptyMap()),
        "screen off"        to ActionHint("LOCK_SCREEN", emptyMap()),
        "sleep"             to ActionHint("LOCK_SCREEN", emptyMap()),

        // ── BRIGHTNESS (only fixed-level aliases; dynamic levels handled in resolve()) ──
        "bright"            to ActionHint("SET_BRIGHTNESS", mapOf("level" to "100")),
        "dim"               to ActionHint("SET_BRIGHTNESS", mapOf("level" to "20")),
        "dim screen"        to ActionHint("SET_BRIGHTNESS", mapOf("level" to "20")),
        "max brightness"    to ActionHint("SET_BRIGHTNESS", mapOf("level" to "100")),
        "min brightness"    to ActionHint("SET_BRIGHTNESS", mapOf("level" to "0")),
        "full brightness"   to ActionHint("SET_BRIGHTNESS", mapOf("level" to "100")),
        "brightness low"    to ActionHint("SET_BRIGHTNESS", mapOf("level" to "20")),
        "brightness high"   to ActionHint("SET_BRIGHTNESS", mapOf("level" to "80")),
        "low brightness"    to ActionHint("SET_BRIGHTNESS", mapOf("level" to "20")),
        "high brightness"   to ActionHint("SET_BRIGHTNESS", mapOf("level" to "80")),

        // ── RINGER MODE ─────────────────────────────────
        "vibrate"           to ActionHint("SET_RINGER_MODE", mapOf("mode" to "vibrate")),
        "vibrate mode"      to ActionHint("SET_RINGER_MODE", mapOf("mode" to "vibrate")),
        "vibration mode"    to ActionHint("SET_RINGER_MODE", mapOf("mode" to "vibrate")),
        "normal mode"       to ActionHint("SET_RINGER_MODE", mapOf("mode" to "normal")),
        "normal ringer"     to ActionHint("SET_RINGER_MODE", mapOf("mode" to "normal")),
        "ringer normal"     to ActionHint("SET_RINGER_MODE", mapOf("mode" to "normal")),

        // ── DND / HOTSPOT ────────────────────────────────
        "dnd"               to ActionHint("TOGGLE_DND", mapOf("state" to "toggle")),
        "do not disturb"    to ActionHint("TOGGLE_DND", mapOf("state" to "toggle")),
        "dnd on"            to ActionHint("TOGGLE_DND", mapOf("state" to "on")),
        "dnd off"           to ActionHint("TOGGLE_DND", mapOf("state" to "off")),
        "turn on dnd"       to ActionHint("TOGGLE_DND", mapOf("state" to "on")),
        "turn off dnd"      to ActionHint("TOGGLE_DND", mapOf("state" to "off")),
        "hotspot"           to ActionHint("TOGGLE_HOTSPOT", mapOf("state" to "toggle")),
        "hotspot on"        to ActionHint("TOGGLE_HOTSPOT", mapOf("state" to "on")),
        "hotspot off"       to ActionHint("TOGGLE_HOTSPOT", mapOf("state" to "off")),
        "turn on hotspot"   to ActionHint("TOGGLE_HOTSPOT", mapOf("state" to "on")),
        "turn off hotspot"  to ActionHint("TOGGLE_HOTSPOT", mapOf("state" to "off")),
        "tethering"         to ActionHint("TOGGLE_HOTSPOT", mapOf("state" to "toggle")),

        // ── COMMON APP SHORTCUTS ─────────────────────────
        "settings"          to ActionHint("OPEN_APP", mapOf("appName" to "Settings")),
        "open settings"     to ActionHint("OPEN_APP", mapOf("appName" to "Settings")),
        "camera"            to ActionHint("OPEN_APP", mapOf("appName" to "Camera")),
        "open camera"       to ActionHint("OPEN_APP", mapOf("appName" to "Camera")),
        "maps"              to ActionHint("OPEN_APP", mapOf("appName" to "Google Maps")),
        "open maps"         to ActionHint("OPEN_APP", mapOf("appName" to "Google Maps")),
        "whatsapp"          to ActionHint("OPEN_APP", mapOf("appName" to "WhatsApp")),
        "open whatsapp"     to ActionHint("OPEN_APP", mapOf("appName" to "WhatsApp")),

        // ── CLIPBOARD ────────────────────────────────────
        "clear clipboard"   to ActionHint("CLEAR_CLIPBOARD", emptyMap()),
        "empty clipboard"   to ActionHint("CLEAR_CLIPBOARD", emptyMap()),
        "erase clipboard"   to ActionHint("CLEAR_CLIPBOARD", emptyMap()),
        "show clipboard"    to ActionHint("GET_CLIPBOARD", emptyMap()),
        "read clipboard"    to ActionHint("GET_CLIPBOARD", emptyMap()),
        "what's in clipboard" to ActionHint("GET_CLIPBOARD", emptyMap()),
        "clipboard"         to ActionHint("GET_CLIPBOARD", emptyMap()),

        // ── BROWSER ─────────────────────────────────────
        "open browser"      to ActionHint("OPEN_BROWSER", emptyMap()),
        "open chrome"       to ActionHint("OPEN_BROWSER", emptyMap()),
        "launch browser"    to ActionHint("OPEN_BROWSER", emptyMap()),
        "private browsing"  to ActionHint("ENABLE_PRIVATE_MODE", emptyMap()),
        "incognito mode"    to ActionHint("ENABLE_PRIVATE_MODE", emptyMap()),
        "open incognito"    to ActionHint("ENABLE_PRIVATE_MODE", emptyMap()),
        "private mode"      to ActionHint("ENABLE_PRIVATE_MODE", emptyMap()),
        "incognito"         to ActionHint("ENABLE_PRIVATE_MODE", emptyMap()),
        "clear browser history" to ActionHint("CLEAR_BROWSER_DATA", emptyMap()),
        "clear browser data" to ActionHint("CLEAR_BROWSER_DATA", emptyMap()),
        "clear browsing data" to ActionHint("CLEAR_BROWSER_DATA", emptyMap()),
        "clear cache"       to ActionHint("CLEAR_BROWSER_DATA", emptyMap()),
        "delete browser data" to ActionHint("CLEAR_BROWSER_DATA", emptyMap()),

        // ── WEATHER ─────────────────────────────────────
        "weather"                   to ActionHint("GET_WEATHER", emptyMap()),
        "weather today"             to ActionHint("GET_WEATHER", emptyMap()),
        "what's the weather"        to ActionHint("GET_WEATHER", emptyMap()),
        "whats the weather"         to ActionHint("GET_WEATHER", emptyMap()),
        "what is the weather"       to ActionHint("GET_WEATHER", emptyMap())
    )

    private val contextualOffPhrases = listOf(
        "turn it off", "switch it off", "turn that off", "switch that off",
        "disable it", "stop it", "shut it off", "turn this off"
    )

    private val contextualOnPhrases = listOf(
        "turn it on", "switch it on", "turn that on", "switch that on",
        "enable it", "start it", "turn this on"
    )

    private val toggleActions = setOf(
        "TOGGLE_FLASHLIGHT", "TOGGLE_WIFI", "TOGGLE_BLUETOOTH", "TOGGLE_DND",
        "TOGGLE_HOTSPOT", "TOGGLE_MOBILE_DATA"
    )

    /**
     * Words that indicate a compound intent — when present in the input,
     * partial alias matching should be skipped so the LLM can generate
     * the correct multi-param action (e.g., SEND_WHATSAPP with contact+message).
     */
    private val compoundIntentWords = setOf(
        "send", "message", "text", "msg", "call", "dial", "ring",
        "email", "mail", "navigate", "directions", "search", "find",
        "play", "book", "order", "remind"
    )

    /**
     * Resolve user input to an ActionHint.
     * Returns null if no alias matches.
     */
    private fun cleanInput(input: String): String {
        return input.lowercase()
            .replace(Regex("""[.,\/#!$%\^&\*;:{}=\-_`~()?+!]"""), " ") // replace punctuation with spaces
            .replace(Regex("""\b(the|a|an|please|could\s+you|please\s+turn|turn\s+the|open\s+the|close\s+the|enable\s+the|disable\s+the|switch\s+on\s+the|switch\s+off\s+the)\b"""), "") // remove stop/filler words
            .replace(Regex("""\s+"""), " ") // collapse multiple spaces
            .trim()
    }

    /**
     * Matches "open youtube and search/play/watch X", "youtube search/play/watch X",
     * and "search/play/watch X on youtube" style phrasings, capturing X as the query.
     * These read as compound (an "and" with an action verb on both sides) but are
     * really a single PLAY_YOUTUBE call — see [extractYoutubeQuery].
     */
    private val youtubeQueryPatterns = listOf(
        Regex("""^open\s+youtube\s+and\s+(?:search(?:\s+for)?|play|watch)\s+(.+)$"""),
        Regex("""^youtube\s+(?:search(?:\s+for)?|play|watch)\s+(.+)$"""),
        Regex("""^(?:search|play|watch)\s+(.+?)\s+on\s+youtube$""")
    )

    /**
     * Extracts the search query from a "open youtube and search cat videos",
     * "youtube search lofi", "search cat videos on youtube" style phrasing.
     * Returns null when the input doesn't match one of these YouTube phrasings —
     * in particular a bare "youtube" or "open youtube" (no query) always returns
     * null so those keep falling through to their existing behavior.
     */
    fun extractYoutubeQuery(input: String): String? {
        val cleaned = cleanInput(input)
        for (regex in youtubeQueryPatterns) {
            val match = regex.find(cleaned) ?: continue
            val query = match.groupValues[1].trim()
            if (query.isNotEmpty()) return query
        }
        return null
    }

    /**
     * Resolve user input to an ActionHint.
     * Returns null if no alias matches.
     */
    fun resolve(input: String): ActionHint? {
        val lower = input.lowercase().trim()
        val cleaned = cleanInput(input)

        // 1. Exact match (always wins)
        aliases[cleaned]?.let { return it }
        aliases[lower]?.let { return it }

        // 1b. YouTube search/play/watch extraction — routes straight to the working
        //     single-shot PLAY_YOUTUBE action instead of the compound-intent guard
        //     below (which would otherwise swallow this because it contains "search"
        //     or "play") or the LLM planner's broken OPEN_APP + CLICK_TEXT/TYPE_TEXT plan.
        extractYoutubeQuery(input)?.let { query ->
            return ActionHint("PLAY_YOUTUBE", mapOf("query" to query))
        }

        // 2. Dynamic brightness extraction — "set brightness to 30%", "brightness 60", etc.
        //    This runs BEFORE compound-intent guard so the LLM doesn't need to handle it.
        if (lower.contains("brightness")) {
            val numberMatch = Regex("""\d+""").find(lower)
            if (numberMatch != null) {
                val level = numberMatch.value.toIntOrNull()?.coerceIn(0, 100) ?: 50
                return ActionHint("SET_BRIGHTNESS", mapOf("level" to level.toString()))
            }
            // Bare "brightness" or "set brightness" with no number → default 50%
            if (lower == "brightness" || lower == "set brightness") {
                return ActionHint("SET_BRIGHTNESS", mapOf("level" to "50"))
            }
        }

        // 3. Dynamic volume extraction — "set volume to 40", "volume 70", etc.
        if (lower.contains("volume") && !lower.contains("music")) {
            val numberMatch = Regex("""\d+""").find(lower)
            if (numberMatch != null) {
                val level = numberMatch.value.toIntOrNull()?.coerceIn(0, 100) ?: 50
                return ActionHint("SET_VOLUME", mapOf("type" to "media", "level" to level.toString()))
            }
        }

        // 4. Skip partial matching if input has compound intent
        //    e.g., "open whatsapp and send message to dad" should NOT match "open whatsapp"
        //    — it needs the LLM to generate SEND_WHATSAPP with contact+message params
        val hasCompoundIntent = compoundIntentWords.any { word -> lower.contains(word) }
        if (hasCompoundIntent) {
            return null
        }

        // 5. Longest partial match — only for simple, single-intent inputs
        return aliases.entries
            .filter { (key, _) -> cleaned.contains(key) || lower.contains(key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    // ── Alarm shortcut helpers ──────────────────────────

    private val alarmPhrases = listOf(
        "set alarm", "set an alarm", "set a alarm",
        "alarm at", "alarm for", "alarm to",
        "wake me up at", "wake me at", "wake me up", "wake me",
        "wakeup alarm", "wakeup at", "morning alarm",
        "put alarm", "remind me to wake"
    )

    /**
     * Check if input is an alarm request.
     * Used by AgentLoop to fast-path alarm commands before LLM.
     */
    fun isAlarmRequest(input: String): Boolean {
        val lower = input.lowercase().trim()
        return alarmPhrases.any { lower.contains(it) }
    }

    /**
     * Extract the time portion from an alarm request.
     * Strips alarm trigger phrases to isolate the time string.
     */
    fun extractAlarmTime(input: String): String? {
        var cleaned = input.lowercase().trim()

        // Remove alarm trigger phrases (longest first to avoid partial removal)
        val sortedPhrases = alarmPhrases.sortedByDescending { it.length }
        for (phrase in sortedPhrases) {
            cleaned = cleaned.replace(phrase, "")
        }

        // Remove filler words
        cleaned = cleaned
            .replace("for tomorrow", "")
            .replace("tomorrow", "")
            .replace("today", "")
            .replace("for", "")
            .replace("at", "")
            .replace("to", "")
            .trim()

        return if (cleaned.isNotEmpty()) cleaned else null
    }

    /**
     * Resolve pronoun-based follow-ups like "turn it off" using the last executed action.
     */
    fun resolveContextual(input: String, lastAction: String?, lastParams: Map<String, String>?): ActionHint? {
        if (lastAction.isNullOrBlank()) return null
        val lower = input.lowercase().trim()
        val wantsOff = contextualOffPhrases.any { lower == it || lower.contains(it) }
        val wantsOn = contextualOnPhrases.any { lower == it || lower.contains(it) }
        if (!wantsOff && !wantsOn) return null

        val state = if (wantsOff) "off" else "on"
        if (lastAction in toggleActions) {
            return ActionHint(lastAction, mapOf("state" to state))
        }

        if (lastAction == "SET_VOLUME" && wantsOff) {
            return ActionHint("SET_VOLUME", mapOf("type" to (lastParams?.get("type") ?: "ring"), "level" to "0"))
        }
        if (lastAction == "SET_VOLUME" && wantsOn) {
            return ActionHint("SET_VOLUME", mapOf("type" to (lastParams?.get("type") ?: "ring"), "level" to "50"))
        }

        return null
    }

    private val timerPhrases = listOf(
        "set timer", "set a timer", "set an timer", "start timer", "start a timer",
        "timer for", "countdown", "count down"
    )

    fun isTimerRequest(input: String): Boolean {
        val lower = input.lowercase().trim()
        return timerPhrases.any { lower.contains(it) } || Regex("""\btimer\b""").containsMatchIn(lower)
    }

    fun extractTimerDuration(input: String): Int? {
        var cleaned = input.lowercase().trim()
        val sortedPhrases = timerPhrases.sortedByDescending { it.length }
        for (phrase in sortedPhrases) {
            cleaned = cleaned.replace(phrase, " ")
        }
        cleaned = cleaned.replace("set a", " ").replace("set", " ").trim()
        return DurationParser.parseToSeconds(cleaned)
    }

    private val readAndRememberPhrases = listOf(
        "read this screen and save",
        "read screen and save",
        "read and save",
        "read and remember",
        "read this and save",
        "remember this screen",
        "save this screen",
        "save screen to notes",
        "save this information to my notes",
        "save this information",
        "save the meeting details",
        "save meeting details",
        "add this to my notes",
        "save to my notes",
        "save to notes",
        "remember this",
        "read this whatsapp message and save",
        "read this message and save"
    )

    fun isReadAndRememberRequest(input: String): Boolean {
        val lower = input.lowercase().trim()
        return readAndRememberPhrases.any { lower.contains(it) } ||
               (lower.contains("screen") && (lower.contains("save") || lower.contains("remember") || lower.contains("notes"))) ||
               (lower.contains("read") && (lower.contains("save") || lower.contains("remember")) && (lower.contains("screen") || lower.contains("message") || lower.contains("notes") || lower.contains("details")))
    }

    fun extractTopicForReadAndRemember(input: String): String {
        val lower = input.lowercase().trim()
        val topicRegex = Regex("""(?:save|extract|remember)\s+(?:the\s+)?(meeting details|important information|meeting info|notes|details|info|events?|tasks?|[a-zA-Z0-9\s]+?)(?:\s+to\s+my\s+notes|\s+to\s+notes|\s+in\s+my\s+notes|\s+in\s+notes|$)""", RegexOption.IGNORE_CASE)
        val match = topicRegex.find(lower)
        val matchedTopic = match?.groupValues?.getOrNull(1)?.trim()
        return if (!matchedTopic.isNullOrBlank() && matchedTopic.length > 2 && matchedTopic != "this" && matchedTopic != "it") {
            matchedTopic
        } else if (lower.contains("meeting")) {
            "meeting details"
        } else if (lower.contains("note")) {
            "notes"
        } else {
            "important information"
        }
    }

    private val recallPrefixes = listOf(
        "what did i save about",
        "what did i remember about",
        "what do you remember about",
        "what's in my notes about",
        "whats in my notes about",
        "search notes for",
        "recall memory about",
        "what did i save on",
        "search memory for",
        "what did i save"
    )

    fun isRecallMemoryRequest(input: String): Boolean {
        val lower = input.lowercase().trim()
        return recallPrefixes.any { lower.startsWith(it) }
    }

    fun extractRecallQuery(input: String): String {
        val lower = input.lowercase().trim()
        for (prefix in recallPrefixes.sortedByDescending { it.length }) {
            if (lower.startsWith(prefix)) {
                val query = lower.substring(prefix.length).trim().removeSuffix("?").trim()
                if (query.isNotEmpty()) return query
            }
        }
        return input.trim().removeSuffix("?").trim()
    }
}
