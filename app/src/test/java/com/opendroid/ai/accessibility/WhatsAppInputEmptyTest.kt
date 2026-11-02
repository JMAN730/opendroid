package com.opendroid.ai.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppInputEmptyTest {

    @Test
    fun `localized hint shown in an empty field counts as empty`() {
        assertTrue(WhatsAppAutomator.isInputEmpty("Mensaje", "Mensaje", showingHint = true))
        assertTrue(WhatsAppAutomator.isInputEmpty("Nachricht", "Nachricht", showingHint = true))
    }

    @Test
    fun `node text equal to its hint counts as empty when the hint flag is not set`() {
        assertTrue(WhatsAppAutomator.isInputEmpty("Mensagem", "Mensagem", showingHint = false))
    }

    @Test
    fun `blank text counts as empty`() {
        assertTrue(WhatsAppAutomator.isInputEmpty("", "Message", showingHint = false))
        assertTrue(WhatsAppAutomator.isInputEmpty("  ", null, showingHint = false))
    }

    @Test
    fun `typed message is not empty`() {
        assertFalse(WhatsAppAutomator.isInputEmpty("running late", "Mensaje", showingHint = false))
    }

    @Test
    fun `typed text that matches the English hint is not empty in another language`() {
        assertFalse(WhatsAppAutomator.isInputEmpty("Message", "Mensaje", showingHint = false))
    }
}
