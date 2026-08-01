package com.opendroid.ai.data.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto mode persistence on [LLMConfig]: legacy-flag migration and
 * default-allowlist seeding. Pure logic, no Android dependencies.
 */
class AutoModeConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unset mode with legacy autoConfirmPlans false resolves to OFF`() {
        assertEquals(AutoMode.OFF, LLMConfig().resolvedAutoMode())
    }

    @Test
    fun `unset mode with legacy autoConfirmPlans true resolves to YOLO`() {
        assertEquals(AutoMode.YOLO, LLMConfig(autoConfirmPlans = true).resolvedAutoMode())
    }

    @Test
    fun `explicit mode wins over legacy flag`() {
        assertEquals(AutoMode.OFF, LLMConfig(autoConfirmPlans = true, autoMode = AutoMode.OFF).resolvedAutoMode())
        assertEquals(AutoMode.AUTO, LLMConfig(autoMode = AutoMode.AUTO).resolvedAutoMode())
    }

    @Test
    fun `unseeded grants resolve to the default allowlist with timestamp zero`() {
        val grants = LLMConfig().effectiveGrantedActions()
        assertEquals(AutoMode.DEFAULT_GRANTS, grants.keys)
        assertTrue(grants.values.all { it == 0L })
    }

    @Test
    fun `seeded grants are returned verbatim - even when empty`() {
        // User revoked everything: empty map must NOT fall back to defaults.
        assertEquals(emptyMap<String, Long>(), LLMConfig(grantedActions = emptyMap()).effectiveGrantedActions())
        val custom = mapOf("WEB_SEARCH" to 123L)
        assertEquals(custom, LLMConfig(grantedActions = custom).effectiveGrantedActions())
    }

    @Test
    fun `default allowlist is exactly the spec's 18 actions`() {
        assertEquals(
            setOf(
                "WEB_SEARCH", "GET_WEATHER", "GET_NEWS", "CALCULATE", "TRANSLATE",
                "DEFINE_WORD", "CONVERT_UNITS", "CURRENCY_CONVERT", "CHECK_STOCK",
                "SUMMARIZE_URL", "FACT_CHECK",
                "TOGGLE_FLASHLIGHT", "TOGGLE_DND", "SET_BRIGHTNESS", "SET_VOLUME",
                "SET_RINGER_MODE", "GET_SYSTEM_INFO"
            ),
            AutoMode.DEFAULT_GRANTS
        )
    }

    @Test
    fun `old persisted JSON without new fields still decodes`() {
        val config = json.decodeFromString<LLMConfig>("""{"activeProvider":"Google Gemini","autoConfirmPlans":true}""")
        assertEquals(AutoMode.YOLO, config.resolvedAutoMode())
        assertFalse(config.effectiveGrantedActions().isEmpty())
    }
}
