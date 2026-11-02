package com.opendroid.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FindAndTypeTest {

    private val traversal = AccessibilityNodeTraversal()
    private val written = mutableMapOf<String, String>()
    private val root: AccessibilityNodeInfo = AccessibilityNodeInfo.obtain()

    private fun field(
        name: String,
        text: String = "",
        hint: String? = null,
        configure: AccessibilityNodeInfo.() -> Unit = {},
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        node.hintText = hint
        if (text.isEmpty() && hint != null) {
            // An empty EditText reports its hint as the node text.
            node.text = hint
            node.isShowingHintText = true
        } else {
            node.text = text
        }
        shadowOf(node).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                written[name] = args!!.getCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
                ).toString()
            }
            true
        }
        node.configure()
        shadowOf(root).addChild(node)
        return node
    }

    @Test
    fun `empty field is found by its hint`() {
        field("search", hint = "Search settings")
        field("note", text = "bread\nmilk\nbutter")

        val outcome = traversal.findAndType(root, "Search settings", "battery")

        assertTrue(outcome.typed)
        assertEquals("battery", written["search"])
        assertNull(outcome.overwrittenText)
    }

    @Test
    fun `text inside a field is swapped without wiping the rest of the field`() {
        field("search", hint = "Search settings")
        field("note", text = "bread\nmilk\nbutter")

        val outcome = traversal.findAndType(root, "milk", "oat milk")

        assertTrue(outcome.typed)
        assertEquals("bread\noat milk\nbutter", written["note"])
        assertNull(written["search"])
        assertNull(outcome.overwrittenText)
    }

    @Test
    fun `match in the field text is case-insensitive and only the first occurrence changes`() {
        field("note", text = "Milk\nmilk chocolate")

        traversal.findAndType(root, "milk", "oat milk")

        assertEquals("oat milk\nmilk chocolate", written["note"])
    }

    @Test
    fun `a field's label wins over another field that contains the text`() {
        field("note", text = "Search the attic")
        field("search", hint = "Search")

        traversal.findAndType(root, "Search", "boxes")

        assertEquals("boxes", written["search"])
        assertNull(written["note"])
    }

    @Test
    fun `field is found by the label that points at it`() {
        val label = AccessibilityNodeInfo.obtain().apply { text = "Email address" }
        field("email") { shadowOf(this).setLabeledBy(label) }

        assertTrue(traversal.findAndType(root, "Email address", "a@b.c").typed)
        assertEquals("a@b.c", written["email"])
    }

    @Test
    fun `field is found by its content description`() {
        field("query") { contentDescription = "Search query" }

        assertTrue(traversal.findAndType(root, "Search query", "weather").typed)
        assertEquals("weather", written["query"])
    }

    @Test
    fun `replacing a labelled field that held text reports the old text`() {
        field("search", text = "wifi", hint = "Search settings")

        val outcome = traversal.findAndType(root, "Search settings", "battery")

        assertEquals("battery", written["search"])
        assertEquals("wifi", outcome.overwrittenText)
    }

    @Test
    fun `nothing is typed when no field matches`() {
        field("note", text = "bread")

        assertFalse(traversal.findAndType(root, "milk", "oat milk").typed)
        assertTrue(written.isEmpty())
    }
}
