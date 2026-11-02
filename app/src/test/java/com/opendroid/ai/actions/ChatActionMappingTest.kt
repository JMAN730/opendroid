package com.opendroid.ai.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatActionMappingTest {

    @Test
    fun `CHAT has no handler, so it resolves through its alias`() {
        val mapping = ActionAutoMapper().mapAction("CHAT", emptyMap(), setOf("DISPLAY_INFO"))

        assertEquals("DISPLAY_INFO", mapping.mappedAction)
        assertTrue(mapping.wasMapped)
    }

    @Test
    fun `the response the schema asks CHAT for reaches DISPLAY_INFO as its message`() {
        val mapping = ActionAutoMapper().mapAction("CHAT", mapOf("response" to "Hi there"), setOf("DISPLAY_INFO"))

        assertEquals(mapOf("message" to "Hi there"), mapping.mappedParams)
    }

    @Test
    fun `a schema action that has a handler is not redirected by an alias`() {
        val mapping = ActionAutoMapper().mapAction("CHAT", emptyMap(), setOf("CHAT", "DISPLAY_INFO"))

        assertEquals("CHAT", mapping.mappedAction)
        assertFalse(mapping.wasMapped)
    }
}
