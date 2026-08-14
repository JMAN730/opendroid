package com.opendroid.ai.core.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpActionAuthorizationTest {

    @Test
    fun `irreversible actions require the interactive app approval flow`() {
        assertFalse(McpActionAuthorization.canExecute("PAY_UPI"))
        assertFalse(McpActionAuthorization.canExecute("DELETE_MACRO"))
        assertFalse(McpActionAuthorization.canExecute("DISMISS_NOTIFICATION"))
    }

    @Test
    fun `non-destructive actions remain available to authenticated MCP clients`() {
        assertTrue(McpActionAuthorization.canExecute("GET_WEATHER"))
        assertTrue(McpActionAuthorization.canExecute("LIST_MACROS"))
    }
}
