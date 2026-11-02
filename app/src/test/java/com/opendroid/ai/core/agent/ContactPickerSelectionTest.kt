package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactPickerSelectionTest {

    private val alice = mapOf("name" to "Alice Smith", "phone" to "+14155550101", "type" to "Mobile")
    private val aliceWork = mapOf("name" to "Alice Smith", "phone" to "+14155550199", "type" to "Mobile")
    private val bob = mapOf("name" to "Bob Jones", "phone" to "+14155550202", "type" to "Mobile")

    @Test
    fun `a tapped contact resolves to that same contact, not to a position`() {
        val selected = ContactPickerSelection.resolve(
            selection = "Alice Smith",
            matches = listOf(alice, aliceWork),
            tapped = aliceWork
        )
        assertEquals(aliceWork, selected)
    }

    @Test
    fun `a tapped contact from an older picker never picks someone from the pending one`() {
        // Old card listed [alice, aliceWork]; the pending picker lists [bob, alice].
        // Under the old bare-index tap, tapping aliceWork (2nd) answered "2" -> alice.
        val selected = ContactPickerSelection.resolve(
            selection = "Alice Smith",
            matches = listOf(bob, alice),
            tapped = aliceWork
        )
        assertNull(selected)
    }

    @Test
    fun `typed answers still resolve by number, ordinal and name`() {
        val matches = listOf(alice, bob)
        assertEquals(bob, ContactPickerSelection.resolve("2", matches))
        assertEquals(alice, ContactPickerSelection.resolve("the first one", matches))
        assertEquals(bob, ContactPickerSelection.resolve("bob jones", matches))
        assertNull(ContactPickerSelection.resolve("7", matches))
    }
}
