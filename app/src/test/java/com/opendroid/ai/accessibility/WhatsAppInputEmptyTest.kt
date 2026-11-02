package com.opendroid.ai.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppInputEmptyTest {

    @Test
    fun `localized hint shown in an empty field counts as empty`() {
        assertTrue(WhatsAppAutomator.isInputEmpty("Mensaje", showingHint = true))
        assertTrue(WhatsAppAutomator.isInputEmpty("Nachricht", showingHint = true))
    }

    @Test
    fun `typed text that matches its own hint is not empty when the hint is not showing`() {
        assertFalse(WhatsAppAutomator.isInputEmpty("Mensaje", showingHint = false))
    }

    @Test
    fun `blank text counts as empty`() {
        assertTrue(WhatsAppAutomator.isInputEmpty("", showingHint = false))
        assertTrue(WhatsAppAutomator.isInputEmpty("  ", showingHint = false))
    }

    @Test
    fun `typed message is not empty`() {
        assertFalse(WhatsAppAutomator.isInputEmpty("running late", showingHint = false))
    }

    @Test
    fun `typed text that matches the English hint is not empty in another language`() {
        assertFalse(WhatsAppAutomator.isInputEmpty("Message", showingHint = false))
    }
}
