package com.opendroid.ai.core.agent

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
        "flash on"          to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "torch on"          to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "flashlight on"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "enable flash"      to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        "enable torch"      to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "on")),
        // Explicit off
        "turn off flash"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn off torch"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
        "turn off flashlight" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("state" to "off")),
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

        // ── WIFI ─────────────────────────────────────────
        "wifi on"           to ActionHint("TOGGLE_WIFI", mapOf("on" to "true")),
        "wifi off"          to ActionHint("TOGGLE_WIFI", mapOf("on" to "false")),
        "turn on wifi"      to ActionHint("TOGGLE_WIFI", mapOf("on" to "true")),
        "turn off wifi"     to ActionHint("TOGGLE_WIFI", mapOf("on" to "false")),
        "enable wifi"       to ActionHint("TOGGLE_WIFI", mapOf("on" to "true")),
        "disable wifi"      to ActionHint("TOGGLE_WIFI", mapOf("on" to "false")),
        "internet on"       to ActionHint("TOGGLE_WIFI", mapOf("on" to "true")),
        "internet off"      to ActionHint("TOGGLE_WIFI", mapOf("on" to "false")),

        // ── BLUETOOTH ────────────────────────────────────
        "bluetooth on"      to ActionHint("TOGGLE_BLUETOOTH", mapOf("on" to "true")),
        "bluetooth off"     to ActionHint("TOGGLE_BLUETOOTH", mapOf("on" to "false")),
        "bt on"             to ActionHint("TOGGLE_BLUETOOTH", mapOf("on" to "true")),
        "bt off"            to ActionHint("TOGGLE_BLUETOOTH", mapOf("on" to "false")),
        "turn on bluetooth" to ActionHint("TOGGLE_BLUETOOTH", mapOf("on" to "true")),
        "turn off bluetooth" to ActionHint("TOGGLE_BLUETOOTH", mapOf("on" to "false")),

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

        // ── BRIGHTNESS ───────────────────────────────────
        "bright"            to ActionHint("SET_BRIGHTNESS", mapOf("level" to "100")),
        "dim"               to ActionHint("SET_BRIGHTNESS", mapOf("level" to "20")),
        "max brightness"    to ActionHint("SET_BRIGHTNESS", mapOf("level" to "100")),
        "min brightness"    to ActionHint("SET_BRIGHTNESS", mapOf("level" to "0")),
        "full brightness"   to ActionHint("SET_BRIGHTNESS", mapOf("level" to "100")),

        // ── DND / HOTSPOT ────────────────────────────────
        "dnd"               to ActionHint("TOGGLE_DND", mapOf("on" to "true")),
        "do not disturb"    to ActionHint("TOGGLE_DND", mapOf("on" to "true")),
        "dnd on"            to ActionHint("TOGGLE_DND", mapOf("on" to "true")),
        "dnd off"           to ActionHint("TOGGLE_DND", mapOf("on" to "false")),
        "hotspot"           to ActionHint("TOGGLE_HOTSPOT", mapOf("on" to "true")),
        "hotspot on"        to ActionHint("TOGGLE_HOTSPOT", mapOf("on" to "true")),
        "hotspot off"       to ActionHint("TOGGLE_HOTSPOT", mapOf("on" to "false")),

        // ── COMMON APP SHORTCUTS ─────────────────────────
        "settings"          to ActionHint("OPEN_APP", mapOf("appName" to "Settings")),
        "open settings"     to ActionHint("OPEN_APP", mapOf("appName" to "Settings")),
        "camera"            to ActionHint("OPEN_APP", mapOf("appName" to "Camera")),
        "open camera"       to ActionHint("OPEN_APP", mapOf("appName" to "Camera")),
        "maps"              to ActionHint("OPEN_APP", mapOf("appName" to "Google Maps")),
        "open maps"         to ActionHint("OPEN_APP", mapOf("appName" to "Google Maps")),
        "whatsapp"          to ActionHint("OPEN_APP", mapOf("appName" to "WhatsApp")),
        "open whatsapp"     to ActionHint("OPEN_APP", mapOf("appName" to "WhatsApp"))
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
    fun resolve(input: String): ActionHint? {
        val lower = input.lowercase().trim()

        // 1. Exact match (always wins)
        aliases[lower]?.let { return it }

        // 2. Skip partial matching if input has compound intent
        //    e.g., "open whatsapp and send message to dad" should NOT match "open whatsapp"
        //    — it needs the LLM to generate SEND_WHATSAPP with contact+message params
        val hasCompoundIntent = compoundIntentWords.any { word -> lower.contains(word) }
        if (hasCompoundIntent) {
            return null
        }

        // 3. Longest partial match — only for simple, single-intent inputs
        return aliases.entries
            .filter { (key, _) -> lower.contains(key) }
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
}
