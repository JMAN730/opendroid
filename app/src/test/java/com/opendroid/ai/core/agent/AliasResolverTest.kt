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
    fun `extracts timer duration from natural language`() {
        assertEquals(5, AliasResolver.extractTimerDuration("set a 5s timer"))
        assertEquals(5, AliasResolver.extractTimerDuration("set a 5 seconds timer"))
        assertEquals(120, AliasResolver.extractTimerDuration("set a 2 minute timer"))
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
}
