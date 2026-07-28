package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable

/**
 * Plan auto-approval mode. AUTO approves a plan only when every step's action
 * is in the granted allowlist and none is flagged neverAutoApprove; YOLO
 * approves everything (in-action destructive confirmations still fire).
 */
@Serializable
enum class AutoMode {
    OFF, AUTO, YOLO;

    companion object {
        /**
         * Allowlist seeded on first run: read-only or reversible on-device
         * actions with no external side effects. Connectivity toggles are
         * deliberately excluded — reversible, but can strand the device
         * offline mid-plan.
         */
        val DEFAULT_GRANTS: Set<String> = setOf(
            // INFORMATION
            "WEB_SEARCH", "GET_WEATHER", "GET_NEWS", "CALCULATE", "TRANSLATE",
            "DEFINE_WORD", "CONVERT_UNITS", "CURRENCY_CONVERT", "CHECK_STOCK",
            "SUMMARIZE_URL", "FACT_CHECK",
            // Reversible SYSTEM
            "TOGGLE_FLASHLIGHT", "TOGGLE_DND", "SET_BRIGHTNESS", "SET_VOLUME",
            "SET_RINGER_MODE", "TAKE_SCREENSHOT", "GET_SYSTEM_INFO"
        )
    }
}
