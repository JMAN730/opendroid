package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ActionSchema] — the typed action registry used to validate
 * LLM-produced params, inject defaults, and feed the planning prompt.
 *
 * Pure logic, no Android dependencies.
 */
class ActionSchemaTest {

    // ── validateParams ──────────────────────────────────────────────────

    @Test
    fun `valid when required param is present`() {
        val (result, enriched) = ActionSchema.validateParams("OPEN_APP", mapOf("appName" to "Camera"))
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("Camera", enriched["appName"])
    }

    @Test
    fun `a placeholder or unresolved step reference counts as a missing message`() {
        listOf(
            "...", "…", "null", "", "   ", "<message>", "[s1.result]", "<result of s1>",
            "Weather: {{s1.result}}", "{{s1}}", "\${s1}", "\$\$s2","Forecast: \$s2"
        ).forEach { message ->
            val (result, enriched) = ActionSchema.validateParams(
                "SEND_SMS", mapOf("contact" to "+15551234567", "message" to message)
            )
            assertEquals(message, ActionSchema.ValidationResult.MissingParams(listOf("message")), result)
            assertFalse(message, enriched.containsKey("message"))
        }
    }

    @Test
    fun `a placeholder contact, body or group is missing on every send action`() {
        val cases = listOf(
            Triple("SEND_WHATSAPP", mapOf("contact" to "<contact>", "message" to "hi"), "contact"),
            Triple("SEND_WHATSAPP_GROUP", mapOf("groupName" to "Family", "message" to "..."), "message"),
            Triple("SEND_TELEGRAM", mapOf("contact" to "@bob", "message" to "{{s1}}"), "message"),
            Triple("SEND_EMAIL", mapOf("to" to "a@b.c", "subject" to "Hi", "body" to "null"), "body")
        )
        cases.forEach { (action, params, missing) ->
            val (result, _) = ActionSchema.validateParams(action, params)
            assertEquals(action, ActionSchema.ValidationResult.MissingParams(listOf(missing)), result)
        }
    }

    @Test
    fun `real message text is still valid, dollar amounts and brackets inside included`() {
        listOf("Weather: Sunny", "I owe you \$5", "Running late [again]", "a < b").forEach { message ->
            val (result, enriched) = ActionSchema.validateParams(
                "SEND_SMS", mapOf("contact" to "Mom", "message" to message)
            )
            assertTrue(message, result is ActionSchema.ValidationResult.Valid)
            assertEquals(message, enriched["message"])
        }
    }

    @Test
    fun `a JSON steps array for a macro is a value, not a bracket placeholder`() {
        listOf(
            """[{"action":"OPEN_APP","params":{"appName":"Spotify"}}]""",
            """[{"action":"SEND_SMS","params":{"contact":"Mom","message":"${'$'}${'$'}s1"}}]"""
        ).forEach { steps ->
            val (result, enriched) = ActionSchema.validateParams(
                "CREATE_MACRO", mapOf("name" to "Morning", "steps" to steps)
            )
            assertEquals(steps, ActionSchema.ValidationResult.Valid, result)
            assertEquals(steps, enriched["steps"])
        }
    }

    @Test
    fun `missing required param is reported`() {
        val (result, _) = ActionSchema.validateParams("OPEN_APP", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.MissingParams)
        assertTrue((result as ActionSchema.ValidationResult.MissingParams).params.contains("appName"))
    }

    @Test
    fun `missing optional param with default is auto-applied and stays valid`() {
        val (result, enriched) = ActionSchema.validateParams("TOGGLE_FLASHLIGHT", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("toggle", enriched["state"])
    }

    @Test
    fun `check balance app is optional and restricted to supported payment apps`() {
        val app = ActionSchema.getAction("CHECK_BALANCE")!!.params.single()

        assertEquals("app", app.name)
        assertEquals(ParamType.ENUM, app.type)
        assertFalse(app.required)
        assertEquals(listOf("gpay", "phonepe", "paytm"), app.enumValues)
        assertEquals("gpay", app.defaultValue)

        val (defaultResult, defaultParams) = ActionSchema.validateParams("CHECK_BALANCE", emptyMap())
        assertTrue(defaultResult is ActionSchema.ValidationResult.Valid)
        assertEquals("gpay", defaultParams["app"])

        listOf("gpay", "phonepe", "paytm").forEach { value ->
            val (result, enriched) = ActionSchema.validateParams(
                "CHECK_BALANCE",
                mapOf("app" to value)
            )
            assertTrue(result is ActionSchema.ValidationResult.Valid)
            assertEquals(value, enriched["app"])
        }

        val (invalidResult, _) = ActionSchema.validateParams(
            "CHECK_BALANCE",
            mapOf("app" to "paypal")
        )
        assertTrue(invalidResult is ActionSchema.ValidationResult.InvalidParam)
    }

    @Test
    fun `enum synonym is corrected to a canonical value`() {
        // "enable" is a synonym for "on" in the on/off/toggle enum
        val (result, enriched) = ActionSchema.validateParams("TOGGLE_WIFI", mapOf("state" to "enable"))
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("on", enriched["state"])
    }

    @Test
    fun `unrecognized enum value with no synonym is reported as invalid, with its options`() {
        val (result, _) = ActionSchema.validateParams("SET_RINGER_MODE", mapOf("mode" to "banana"))
        val invalid = result as ActionSchema.ValidationResult.InvalidParam
        assertEquals("mode", invalid.param)
        assertEquals("banana", invalid.value)
        assertEquals(ActionSchema.getAction("SET_RINGER_MODE")!!.params.first { it.name == "mode" }.enumValues, invalid.options)
    }

    @Test
    fun `an invalid value for a param with a default is not treated as missing`() {
        // Folding it into "missing" let the dispatcher apply defaults to the original
        // params, so the bad value reached a handler that reads anything but "off" as flip.
        val (result, _) = ActionSchema.validateParams("AUTO_REPLY_TOGGLE", mapOf("state" to "banana"))
        assertTrue(result is ActionSchema.ValidationResult.InvalidParam)
    }

    @Test
    fun `enabled and disabled are synonyms for on and off`() {
        val (disabled, offParams) = ActionSchema.validateParams("AUTO_REPLY_TOGGLE", mapOf("state" to "disabled"))
        assertTrue(disabled is ActionSchema.ValidationResult.Valid)
        assertEquals("off", offParams["state"])

        val (enabled, onParams) = ActionSchema.validateParams("AUTO_REPLY_TOGGLE", mapOf("state" to "Enabled"))
        assertTrue(enabled is ActionSchema.ValidationResult.Valid)
        assertEquals("on", onParams["state"])
    }

    @Test
    fun `unknown action yields InvalidAction`() {
        val (result, _) = ActionSchema.validateParams("NONEXISTENT_ACTION", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.InvalidAction)
    }

    // ── applyDefaults ───────────────────────────────────────────────────

    @Test
    fun `applyDefaults fills in missing defaulted params`() {
        val enriched = ActionSchema.applyDefaults("TOGGLE_FLASHLIGHT", emptyMap())
        assertEquals("toggle", enriched["state"])
    }

    @Test
    fun `applyDefaults leaves provided params untouched`() {
        val enriched = ActionSchema.applyDefaults("OPEN_APP", mapOf("appName" to "Spotify"))
        assertEquals("Spotify", enriched["appName"])
    }

    // ── Lookup utilities ────────────────────────────────────────────────

    @Test
    fun `getAction finds known actions and misses unknown ones`() {
        assertNotNull(ActionSchema.getAction("OPEN_APP"))
        assertNull(ActionSchema.getAction("NOPE"))
    }

    @Test
    fun `isValid reflects schema membership`() {
        assertTrue(ActionSchema.isValid("CHAT"))
        assertFalse(ActionSchema.isValid("NOPE"))
    }

    @Test
    fun `getAllActionNames is non-empty and sorted`() {
        val names = ActionSchema.getAllActionNames()
        assertTrue(names.isNotEmpty())
        assertTrue(names.contains("OPEN_APP"))
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `getSimpleActions separates simple from compound actions`() {
        val simple = ActionSchema.getSimpleActions()
        assertTrue(simple.contains("TOGGLE_FLASHLIGHT")) // isSimple = true
        assertFalse(simple.contains("SEND_EMAIL"))       // isSimple = false
    }

    @Test
    fun `read and remember screen action has defaults applied`() {
        assertNotNull(ActionSchema.getAction("READ_AND_REMEMBER_SCREEN"))
        val (result, params) = ActionSchema.validateParams("READ_AND_REMEMBER_SCREEN", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("important information", params["topic"])
        assertEquals("note", params["save_as"])
    }

    @Test
    fun `recall memory requires query parameter`() {
        assertNotNull(ActionSchema.getAction("RECALL_MEMORY"))
        val (invalid, _) = ActionSchema.validateParams("RECALL_MEMORY", emptyMap())
        assertTrue(invalid is ActionSchema.ValidationResult.MissingParams)

        val (valid, params) = ActionSchema.validateParams("RECALL_MEMORY", mapOf("query" to "marketing meeting"))
        assertTrue(valid is ActionSchema.ValidationResult.Valid)
        assertEquals("marketing meeting", params["query"])
    }

    @Test
    fun `query knowledge graph applies defaults`() {
        assertNotNull(ActionSchema.getAction("QUERY_KNOWLEDGE_GRAPH"))
        val (result, params) = ActionSchema.validateParams("QUERY_KNOWLEDGE_GRAPH", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("", params["query"])
        assertEquals("ALL", params["category"])
        assertEquals("ALL", params["tier"])
    }

    @Test
    fun `update preference validates required key and value`() {
        assertNotNull(ActionSchema.getAction("UPDATE_PREFERENCE"))
        val (invalid, _) = ActionSchema.validateParams("UPDATE_PREFERENCE", mapOf("key" to "music_app"))
        assertTrue(invalid is ActionSchema.ValidationResult.MissingParams)

        val (valid, params) = ActionSchema.validateParams(
            "UPDATE_PREFERENCE",
            mapOf("key" to "music_app", "value" to "Spotify")
        )
        assertTrue(valid is ActionSchema.ValidationResult.Valid)
        assertEquals("USER_PREFERENCE", params["category"])
    }

    @Test
    fun `save sensitive info validates required key and secret`() {
        assertNotNull(ActionSchema.getAction("SAVE_SENSITIVE_INFO"))
        val (invalid, _) = ActionSchema.validateParams("SAVE_SENSITIVE_INFO", emptyMap())
        assertTrue(invalid is ActionSchema.ValidationResult.MissingParams)

        val (valid, params) = ActionSchema.validateParams(
            "SAVE_SENSITIVE_INFO",
            mapOf("key" to "wifi_password", "secret" to "secret123")
        )
        assertTrue(valid is ActionSchema.ValidationResult.Valid)
        assertEquals("wifi_password", params["key"])
        assertEquals("secret123", params["secret"])
    }

    @Test
    fun `blank required path params are reported as missing`() {
        val cases = mapOf(
            "LIST_FILES" to mapOf("path" to ""),
            "READ_FILE" to mapOf("filePath" to ""),
            "WRITE_FILE" to mapOf("filePath" to "  ", "content" to "x"),
            "DELETE_FILE" to mapOf("filePath" to ""),
            "CREATE_DIRECTORY" to mapOf("path" to " "),
            "COPY_FILE" to mapOf("sourcePath" to "a", "destPath" to ""),
            "MOVE_FILE" to mapOf("sourcePath" to "", "destPath" to "b"),
            "ZIP_FILES" to mapOf("sourcePath" to "a", "zipFilePath" to ""),
            "UNZIP_FILE" to mapOf("zipFilePath" to "a.zip", "destDirPath" to ""),
        )

        for ((action, params) in cases) {
            val (result, _) = ActionSchema.validateParams(action, params)
            assertTrue(action, result is ActionSchema.ValidationResult.MissingParams)
        }
    }

    @Test
    fun `a blank path is reported by name and a filled one stays valid`() {
        val (blank, _) = ActionSchema.validateParams("COPY_FILE", mapOf("sourcePath" to "a.txt", "destPath" to " "))
        val (filled, _) = ActionSchema.validateParams("WRITE_FILE", mapOf("filePath" to "a.txt", "content" to "x"))

        assertEquals(ActionSchema.ValidationResult.MissingParams(listOf("destPath")), blank)
        assertTrue(filled is ActionSchema.ValidationResult.Valid)
    }

    @Test
    fun `the path rule names only blank path params`() {
        // A blank required non-path param is caught by the placeholder rule, not the path rule.
        val (result, _) = ActionSchema.validateParams("WRITE_FILE", mapOf("filePath" to "a.txt", "content" to ""))

        assertEquals(ActionSchema.ValidationResult.MissingParams(listOf("content")), result)
    }
}
