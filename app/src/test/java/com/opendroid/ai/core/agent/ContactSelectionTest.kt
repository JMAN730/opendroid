package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSelectionTest {

    private val chrises = listOf("Chris Evans", "Christopher Lee")

    @Test
    fun `a refusal selects nobody even when a name contains it`() {
        assertNull(selectContact("stop", chrises))
        assertNull(selectContact("no", listOf("Johnny Noble", "John Smith")))
        assertNull(selectContact("cancel", listOf("Cancellara Rossi", "Fabian Cancel")))
        assertNull(selectContact("never mind", listOf("Never Mind", "Chris Evans")))
    }

    @Test
    fun `a fragment of a name selects nobody`() {
        listOf("chris", "Evans", "is", "e", "lee").forEach { reply ->
            assertNull(reply, selectContact(reply, chrises))
        }
    }

    @Test
    fun `an index selects only inside the list`() {
        assertEquals(0, selectContact("1", chrises))
        assertEquals(1, selectContact(" 2 ", chrises))
        assertEquals(1, selectContact("number 2", chrises))
        listOf("0", "3", "99999999999", "-1", "-2", "number -1", "−1", "1-2").forEach { reply ->
            assertNull(reply, selectContact(reply, chrises))
        }
    }

    @Test
    fun `an ordinal selects only as a whole word`() {
        assertEquals(0, selectContact("first", chrises))
        assertEquals(1, selectContact("The second one, please.", chrises))
        assertNull(selectContact("third", chrises))
        assertNull(selectContact("firstly", chrises))
        assertNull(selectContact("not the first one", chrises))
        assertNull(selectContact("the first or the second", chrises))
    }

    @Test
    fun `a full name selects that contact`() {
        assertEquals(1, selectContact("christopher lee", chrises))
        assertEquals(0, selectContact("Chris Evans, please", chrises))
        assertEquals(0, selectContact("the first", listOf("The First", "Chris Evans")))
    }

    @Test
    fun `a name that does not stand alone or is not unique selects nobody`() {
        assertNull(selectContact("not Chris Evans", chrises))
        assertNull(selectContact("Chris Evans or Christopher Lee", chrises))
        assertNull(selectContact("John Smith", listOf("John Smith", "john smith")))
    }

    @Test
    fun `an empty reply or a contact with no name selects nobody`() {
        assertNull(selectContact("", chrises))
        assertNull(selectContact("  ?! ", chrises))
        assertNull(selectContact("hello", listOf("", "Chris Evans")))
    }

    @Test
    fun `a refusal is told apart from a reply that was not understood`() {
        listOf(
            "stop", "no", "No.", "cancel", "never mind", "nevermind", "forget it",
            "no, stop", "please cancel", "not him"
        ).forEach { reply ->
            assertTrue(reply, isContactPickerRefusal(reply))
        }
        listOf("purple", "chris", "the third one", "").forEach { reply ->
            assertFalse(reply, isContactPickerRefusal(reply))
        }
    }
}
