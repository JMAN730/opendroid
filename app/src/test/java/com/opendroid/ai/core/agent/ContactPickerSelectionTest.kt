package com.opendroid.ai.core.agent

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `the tapped contact travels with the reply that answers the picker`() {
        // Two taps race to answer one prompt. Only the reply that completes it may decide
        // the contact, so the recorded answer and the contact acted on always agree.
        val prompt = CompletableDeferred<UserReply>()
        prompt.complete(UserReply("Alice Smith", contact = alice))
        assertFalse(prompt.complete(UserReply("Bob Jones", contact = bob)))

        val reply = prompt.getCompleted()
        assertEquals("Alice Smith", reply.text)
        assertEquals(alice, ContactPickerSelection.resolve(reply, listOf(alice, bob)))
    }

    @Test
    fun `a typed reply carries no contact and resolves by its text`() {
        assertEquals(bob, ContactPickerSelection.resolve(UserReply("2"), listOf(alice, bob)))
    }
}
