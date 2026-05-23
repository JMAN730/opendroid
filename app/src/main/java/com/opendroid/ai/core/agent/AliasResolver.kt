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
        "flash"             to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "flashlight"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "torch"             to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "torchlight"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "light"             to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "open flash"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "open torch"        to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        "open flashlight"   to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "toggle")),
        // Explicit on
        "turn on flash"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "true")),
        "turn on torch"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "true")),
        "turn on flashlight" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "true")),
        "flash on"          to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "true")),
        "torch on"          to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "true")),
        "flashlight on"     to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "true")),
        // Explicit off
        "turn off flash"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "false")),
        "turn off torch"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "false")),
        "turn off flashlight" to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "false")),
        "flash off"         to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "false")),
        "torch off"         to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "false")),
        "flashlight off"    to ActionHint("TOGGLE_FLASHLIGHT", mapOf("on" to "false")),

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
        "play", "book", "order", "set alarm", "set timer", "remind"
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
}
