package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AliasResolver] — the LLM-bypass fast path that maps common
 * natural-language phrases directly to action hints.
 *
 * Pure logic, no Android dependencies.
 */
class AliasResolverTest {

    // ── Exact alias matching ────────────────────────────────────────────

    @Test
    fun `bare flash resolves to flashlight toggle`() {
        val hint = AliasResolver.resolve("flash")
        assertNotNull(hint)
        assertEquals("TOGGLE_FLASHLIGHT", hint!!.action)
        assertEquals("toggle", hint.baseParams["state"])
    }

    @Test
    fun `explicit on and off variants carry the right state`() {
        assertEquals("on", AliasResolver.resolve("turn on flashlight")?.baseParams?.get("state"))
        assertEquals("off", AliasResolver.resolve("torch off")?.baseParams?.get("state"))
    }

    @Test
    fun `mute maps to ring volume zero`() {
        val hint = AliasResolver.resolve("mute")
        assertNotNull(hint)
        assertEquals("SET_VOLUME", hint!!.action)
        assertEquals("ring", hint.baseParams["type"])
        assertEquals("0", hint.baseParams["level"])
    }

    @Test
    fun `turn on do not disturb resolves to the DND toggle`() {
        assertEquals("TOGGLE_DND", AliasResolver.resolve("turn on do not disturb")?.action)
    }

    // ── cleanInput: stop-word / filler stripping ────────────────────────

    @Test
    fun `filler words are stripped before exact matching`() {
        // "the" and "please" are removed, leaving the exact alias "turn on flashlight"
        val hint = AliasResolver.resolve("turn on the flashlight please")
        assertNotNull(hint)
        assertEquals("TOGGLE_FLASHLIGHT", hint!!.action)
        assertEquals("on", hint.baseParams["state"])
    }

    // ── Dynamic brightness extraction ───────────────────────────────────

    @Test
    fun `brightness with explicit percent extracts the number`() {
        val hint = AliasResolver.resolve("set brightness to 30%")
        assertNotNull(hint)
        assertEquals("SET_BRIGHTNESS", hint!!.action)
        assertEquals("30", hint.baseParams["level"])
    }

    @Test
    fun `bare brightness defaults to 50`() {
        val hint = AliasResolver.resolve("brightness")
        assertNotNull(hint)
        assertEquals("SET_BRIGHTNESS", hint!!.action)
        assertEquals("50", hint.baseParams["level"])
    }

    @Test
    fun `brightness above 100 is clamped`() {
        val hint = AliasResolver.resolve("set brightness to 150")
        assertNotNull(hint)
        assertEquals("100", hint!!.baseParams["level"])
    }

    // ── Dynamic volume extraction ───────────────────────────────────────

    @Test
    fun `volume with number resolves to media volume`() {
        val hint = AliasResolver.resolve("volume 70")
        assertNotNull(hint)
        assertEquals("SET_VOLUME", hint!!.action)
        assertEquals("media", hint.baseParams["type"])
        assertEquals("70", hint.baseParams["level"])
    }

    // ── Compound-intent guard ───────────────────────────────────────────

    @Test
    fun `compound intent commands fall through to the LLM (null)`() {
        // Contains "send"/"message" — must NOT match the "open whatsapp" alias,
        // so the LLM can build SEND_WHATSAPP with contact + message params.
        assertNull(AliasResolver.resolve("open whatsapp and send message to dad"))
    }

    // ── Longest partial match for simple, single-intent inputs ──────────

    @Test
    fun `single-intent phrase resolves via partial match`() {
        val hint = AliasResolver.resolve("can you take a screenshot for me")
        assertNotNull(hint)
        assertEquals("TAKE_SCREENSHOT", hint!!.action)
    }

    @Test
    fun `unknown phrase returns null`() {
        assertNull(AliasResolver.resolve("what is the meaning of life"))
    }

    // ── Questions and bystander words never run a device action ─────────

    @Test
    fun `ordinary sentences that contain an alias do not resolve`() {
        listOf(
            "check my flight status",
            "what's the speed of light",
            "is lightning dangerous",
            "is it cloudy today?",
            "the music is too loud",
            "how long is my commute",
            "how do I unlock my phone",
            "block this number",
            "I can't sleep",
            "remember that my locker code is 4512",
            "why is my wifi so slow",
            "what is my wifi password",
            "explain what a hotspot is",
            "tell me about clipboard managers",
            "is it safe to clear cache"
        ).forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `generic single words only match as a command`() {
        listOf("sleep tips", "silent night lyrics", "light is too bright", "set wifi password").forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `question-shaped input skips exact and dynamic matches`() {
        listOf(
            "flash?",
            "lock?",
            "volume 70?",
            "how do i set brightness to 30",
            "does clear cache delete my photos",
            "can i lock my phone with my face",
            "can you explain what a hotspot is",
            "can you tell me about clipboard managers"
        ).forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `imperative commands still resolve`() {
        assertEquals("TOGGLE_FLASHLIGHT", AliasResolver.resolve("turn on my flashlight")?.action)
        assertEquals("TOGGLE_WIFI", AliasResolver.resolve("toggle wifi")?.action)
        assertEquals("GET_CLIPBOARD", AliasResolver.resolve("open clipboard")?.action)
        assertEquals("LOCK_SCREEN", AliasResolver.resolve("lock screen")?.action)
        assertEquals("LOCK_SCREEN", AliasResolver.resolve("lock my phone")?.action)
        assertEquals("SET_VOLUME", AliasResolver.resolve("mute phone")?.action)
        assertEquals("TAKE_SCREENSHOT", AliasResolver.resolve("screenshot now")?.action)
    }

    @Test
    fun `polite requests are commands even with a question mark`() {
        assertEquals("off", AliasResolver.resolve("can you turn off the flashlight?")?.baseParams?.get("state"))
        assertEquals("TAKE_SCREENSHOT", AliasResolver.resolve("can you take a screenshot?")?.action)
        assertEquals("LOCK_SCREEN", AliasResolver.resolve("would you lock my phone")?.action)
    }

    @Test
    fun `aliases that are themselves questions still resolve`() {
        assertEquals("GET_WEATHER", AliasResolver.resolve("what's the weather?")?.action)
        assertEquals("GET_WEATHER", AliasResolver.resolve("what's the weather today")?.action)
        assertEquals("ANALYZE_SCREENSHOT", AliasResolver.resolve("what's on my screen?")?.action)
        assertEquals("TOGGLE_DND", AliasResolver.resolve("do not disturb")?.action)
    }

    @Test
    fun `questions about do not disturb do not toggle it`() {
        listOf(
            "how do i turn on do not disturb",
            "what is do not disturb",
            "is do not disturb on?",
            "do not disturb?"
        ).forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `do not disturb still resolves as a command`() {
        listOf(
            "do not disturb",
            "do not disturb mode",
            "turn on do not disturb",
            "can you turn on do not disturb?"
        ).forEach { query ->
            assertEquals("\"$query\" is a command", "TOGGLE_DND", AliasResolver.resolve(query)?.action)
        }
    }

    @Test
    fun `negated commands do not run the action they name`() {
        assertNull(AliasResolver.resolve("do not turn on the flashlight"))
    }

    @Test
    fun `a question alias must open the question`() {
        listOf(
            "how do i see what's in clipboard",
            "is what's on my screen private"
        ).forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `polite requests that are not commands stay questions`() {
        listOf(
            "can you check if silent mode is on?",
            "could you see if do not disturb is on?",
            "can you verify silent mode is on?",
            "can you describe silent mode?",
            "would you check silent mode"
        ).forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `input with an if or whether clause never runs an alias`() {
        listOf(
            "check if silent mode is on",
            "check whether do not disturb is on",
            "let me know if silent mode is on",
            "turn on wifi if i am home"
        ).forEach { query ->
            assertNull("\"$query\" must reach the LLM", AliasResolver.resolve(query))
        }
    }

    @Test
    fun `question words inside a youtube query do not block the youtube shortcut`() {
        listOf(
            "play what if on youtube" to "what if",
            "youtube search whether to buy tesla" to "whether to buy tesla",
            "open youtube and watch why we sleep" to "why we sleep"
        ).forEach { (input, query) ->
            val hint = AliasResolver.resolve(input)
            assertEquals("\"$input\" is a youtube command", "PLAY_YOUTUBE", hint?.action)
            assertEquals(query, hint?.baseParams?.get("query"))
        }
    }

    @Test
    fun `polite commands reach the dynamic and partial steps`() {
        assertEquals("40", AliasResolver.resolve("can you set volume to 40?")?.baseParams?.get("level"))
        assertEquals("CLEAR_BROWSER_DATA", AliasResolver.resolve("can you clear the cache for me")?.action)
        assertEquals("on", AliasResolver.resolve("can you turn the flashlight on?")?.baseParams?.get("state"))
        assertEquals("cats", AliasResolver.resolve("could you play cats on youtube")?.baseParams?.get("query"))
    }

    @Test
    fun `multi-word aliases match on word boundaries`() {
        assertEquals("CLEAR_BROWSER_DATA", AliasResolver.resolve("clear cache for me")?.action)
        assertNull(AliasResolver.resolve("nuclear cachet"))
    }

    // ── Alarm helpers ───────────────────────────────────────────────────

    @Test
    fun `isAlarmRequest detects alarm phrases`() {
        assertTrue(AliasResolver.isAlarmRequest("wake me up at 7"))
        assertTrue(AliasResolver.isAlarmRequest("set an alarm for 6:30"))
    }

    @Test
    fun `isAlarmRequest is false for non-alarm input`() {
        org.junit.Assert.assertFalse(AliasResolver.isAlarmRequest("what time is it"))
    }

    @Test
    fun `extractAlarmTime isolates the time portion`() {
        assertEquals("7", AliasResolver.extractAlarmTime("wake me up at 7"))
    }

    @Test
    fun `extractAlarmTime returns null when no time remains`() {
        assertNull(AliasResolver.extractAlarmTime("set alarm"))
    }

    @Test
    fun `extractAlarmTime keeps the words that belong to the time`() {
        assertEquals("quarter to 8 pm", AliasResolver.extractAlarmTime("wake me up at quarter to 8 pm"))
        assertEquals("quarter to 8 pm", AliasResolver.extractAlarmTime("set alarm for quarter to 8 pm"))
        assertEquals("half past 6 pm", AliasResolver.extractAlarmTime("wake me up at half past 6 pm"))
        assertEquals("half past 9 at night", AliasResolver.extractAlarmTime("set alarm for half past 9 at night"))
    }

    @Test
    fun `extractAlarmTime still drops the filler around the time`() {
        assertEquals("7", AliasResolver.extractAlarmTime("set alarm for 7 tomorrow"))
        assertEquals("7", AliasResolver.extractAlarmTime("set alarm to 7"))
        assertEquals("7", AliasResolver.extractAlarmTime("set alarm for tomorrow at 7"))
        assertEquals("7", AliasResolver.extractAlarmTime("set alarm at 7 for today"))
        assertEquals("6:30", AliasResolver.extractAlarmTime("alarm at 6:30"))
    }

    @Test
    fun `extractAlarmTime leaves a request it cannot read to the planner`() {
        // The shortcut can't carry a schedule, so these must not become one-time alarms.
        assertNull(AliasResolver.extractAlarmTime("set alarm for 6 am every weekday"))
        assertNull(AliasResolver.extractAlarmTime("set alarm for half past 6 every weekday"))
    }

    @Test
    fun `no alias intercepts an alarm request before the shortcut`() {
        assertNull(AliasResolver.resolve("wake me up at half past 6 pm"))
        assertNull(AliasResolver.resolve("set alarm for quarter to 8 pm"))
        assertNull(AliasResolver.resolve("set alarm for 6 am every weekday"))
    }

    @Test
    fun `extracts timer duration from natural language`() {
        assertEquals(5, AliasResolver.extractTimerDuration("set a 5s timer"))
        assertEquals(5, AliasResolver.extractTimerDuration("set a 5 seconds timer"))
        assertEquals(120, AliasResolver.extractTimerDuration("set a 2 minute timer"))
    }

    @Test
    fun `extracts decimal timer durations from natural language`() {
        assertEquals(5400, AliasResolver.extractTimerDuration("set a timer for 1.5 hours"))
        assertEquals(30, AliasResolver.extractTimerDuration("set a 0.5 min timer"))
        assertEquals(180, AliasResolver.extractTimerDuration("start a timer for 2m59.56s"))
    }

    @Test
    fun `resolves contextual turn it off after flashlight`() {
        val hint = AliasResolver.resolveContextual(
            "turn it off",
            "TOGGLE_FLASHLIGHT",
            mapOf("state" to "on")
        )
        assertEquals("TOGGLE_FLASHLIGHT", hint?.action)
        assertEquals("off", hint?.baseParams?.get("state"))
    }

    @Test
    fun `contextual resolver returns null without prior action`() {
        assertNull(AliasResolver.resolveContextual("turn it off", null, null))
    }

    // ── Read & Remember / Screen Understanding ─────────────────────────

    @Test
    fun `read screen and remember resolves to ActionHint`() {
        val hint = AliasResolver.resolve("read screen and remember")
        assertNotNull(hint)
        assertEquals("READ_AND_REMEMBER_SCREEN", hint!!.action)
        assertEquals("important information", hint.baseParams["topic"])
    }

    @Test
    fun `remember this resolves to ActionHint`() {
        val hint = AliasResolver.resolve("remember this")
        assertNotNull(hint)
        assertEquals("READ_AND_REMEMBER_SCREEN", hint!!.action)
    }

    @Test
    fun `read my notes resolves to ActionHint`() {
        val hint = AliasResolver.resolve("read my notes")
        assertNotNull(hint)
        assertEquals("READ_NOTES", hint!!.action)
    }

    @Test
    fun `isReadAndRememberRequest detects screen saving phrases`() {
        assertTrue(AliasResolver.isReadAndRememberRequest("read this screen and save the important information to my notes"))
        assertTrue(AliasResolver.isReadAndRememberRequest("read this whatsapp message and save the meeting details"))
        assertTrue(AliasResolver.isReadAndRememberRequest("remember this screen"))
        assertTrue(AliasResolver.isReadAndRememberRequest("save this information to my notes"))
        assertTrue(AliasResolver.isReadAndRememberRequest("add this to my notes"))
    }

    @Test
    fun `extractTopicForReadAndRemember extracts meeting details and notes`() {
        assertEquals("meeting details", AliasResolver.extractTopicForReadAndRemember("read this whatsapp message and save the meeting details"))
        assertEquals("important information", AliasResolver.extractTopicForReadAndRemember("read this screen and save the important information to my notes"))
        assertEquals("notes", AliasResolver.extractTopicForReadAndRemember("add this to my notes"))
    }

    @Test
    fun `isRecallMemoryRequest and extractRecallQuery match user queries`() {
        assertTrue(AliasResolver.isRecallMemoryRequest("what did i save about marketing meeting?"))
        assertEquals("marketing meeting", AliasResolver.extractRecallQuery("what did i save about marketing meeting?"))
        assertEquals("friday meeting", AliasResolver.extractRecallQuery("what did i save about friday meeting"))
        assertEquals("doctor appointment", AliasResolver.extractRecallQuery("what do you remember about doctor appointment?"))
    }

    // ── Negated and questioning follow-ups ─────────────────────────────

    @Test
    fun `negated or questioning follow-ups do not flip the last toggle`() {
        val inputs = listOf(
            "don't turn it off", "don't stop it", "leave it, don't switch it off",
            "do not turn it off", "never turn it off",
            "should I turn it off?", "did you turn it off?", "did you turn it off",
            "how do I disable it permanently", "why did you turn it on", "can I turn it off"
        )
        for (input in inputs) {
            assertNull(input, AliasResolver.resolveContextual(input, "TOGGLE_FLASHLIGHT", mapOf("state" to "on")))
            assertNull(input, AliasResolver.resolveContextual(input, "SET_VOLUME", mapOf("type" to "media")))
        }
    }

    @Test
    fun `plain follow-ups still flip the last toggle`() {
        for (input in listOf("turn it off", "stop it", "Turn it off.", "ok, turn it off", "no, stop it", "please switch it off")) {
            val hint = AliasResolver.resolveContextual(input, "TOGGLE_FLASHLIGHT", mapOf("state" to "on"))
            assertEquals(input, "off", hint?.baseParams?.get("state"))
        }
        assertEquals("on", AliasResolver.resolveContextual("turn it on", "TOGGLE_DND", null)?.baseParams?.get("state"))
        assertEquals("0", AliasResolver.resolveContextual("turn it off", "SET_VOLUME", mapOf("type" to "media"))?.baseParams?.get("level"))
    }

    @Test
    fun `contextual phrase inside a longer sentence does not match`() {
        assertNull(AliasResolver.resolveContextual("turn it off and on again later", "TOGGLE_WIFI", null))
    }

    // ── Facts dictated after "remember this" ───────────────────────────

    @Test
    fun `remember this with a dictated fact does not read the screen`() {
        val inputs = listOf(
            "remember this: my locker code is 4512",
            "save this information: parking spot B12",
            "add this to my notes: buy milk",
            "remember this and then text mom"
        )
        for (input in inputs) {
            assertNull(input, AliasResolver.resolve(input))
            org.junit.Assert.assertFalse(input, AliasResolver.isReadAndRememberRequest(input))
        }
    }

    @Test
    fun `bare remember this phrases still read the screen`() {
        for (input in listOf("remember this screen", "remember this", "remember this for me", "save this information", "add this to my notes")) {
            assertEquals(input, "READ_AND_REMEMBER_SCREEN", AliasResolver.resolve(input)?.action)
            assertTrue(input, AliasResolver.isReadAndRememberRequest(input))
        }
    }

    // ── Alarm time extraction ──────────────────────────────────────────

    @Test
    fun `extractAlarmTime keeps letters inside words`() {
        assertEquals("quarter to 8", AliasResolver.extractAlarmTime("set alarm for quarter to 8"))
        assertEquals("7", AliasResolver.extractAlarmTime("set alarm for 7 tomorrow"))
        assertEquals("7:30 am", AliasResolver.extractAlarmTime("set an alarm at 7:30 am"))
        assertEquals("6", AliasResolver.extractAlarmTime("set alarm to 6"))
    }

    @Test
    fun `extractAlarmTime falls through when the remainder is not a time`() {
        assertNull(AliasResolver.extractAlarmTime("set alarm for saturday at 7"))
        assertNull(AliasResolver.extractAlarmTime("set alarm for 9 later today"))
        assertNull(AliasResolver.extractAlarmTime("set alarm for 7 tonight"))
        assertNull(AliasResolver.extractAlarmTime("set alarm for quarter to 8 tonight"))
    }
}
