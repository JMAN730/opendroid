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
        assertTrue(invalidResult is ActionSchema.ValidationResult.MissingParams)
    }

    @Test
    fun `enum synonym is corrected to a canonical value`() {
        // "enable" is a synonym for "on" in the on/off/toggle enum
        val (result, enriched) = ActionSchema.validateParams("TOGGLE_WIFI", mapOf("state" to "enable"))
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("on", enriched["state"])
    }

    @Test
    fun `unrecognized enum value with no synonym is reported as missing`() {
        val (result, _) = ActionSchema.validateParams("SET_RINGER_MODE", mapOf("mode" to "banana"))
        assertTrue(result is ActionSchema.ValidationResult.MissingParams)
        assertTrue((result as ActionSchema.ValidationResult.MissingParams).params.contains("mode"))
    }

    @Test
    fun `unknown action yields InvalidAction`() {
        val (result, _) = ActionSchema.validateParams("NONEXISTENT_ACTION", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.InvalidAction)
    }

    // ── Blank file paths ────────────────────────────────────────────────

    @Test
    fun `blank file path params are reported as missing`() {
        // "" resolves to the workspace root, so a blank path must never validate for
        // actions that read, write, delete or restructure files.
        val allCases = listOf(
            "READ_FILE" to "filePath",
            "WRITE_FILE" to "filePath",
            "DELETE_FILE" to "filePath",
            "CREATE_DIRECTORY" to "path",
            "COPY_FILE" to "sourcePath",
            "COPY_FILE" to "destPath",
            "MOVE_FILE" to "sourcePath",
            "MOVE_FILE" to "destPath",
            "ZIP_FILES" to "sourcePath",
            "ZIP_FILES" to "zipFilePath",
            "UNZIP_FILE" to "zipFilePath",
            "UNZIP_FILE" to "destDirPath"
        )

        allCases.forEach { (action, param) ->
            listOf("", "   ").forEach { blank ->
                // Fill every other param with a real value so only the blank one can fail.
                val params = ActionSchema.getAction(action)!!.params
                    .associate { it.name to (if (it.name == param) blank else "something") }
                val (result, _) = ActionSchema.validateParams(action, params)
                assertTrue(
                    "$action with blank '$param' should be MissingParams",
                    result is ActionSchema.ValidationResult.MissingParams
                )
                assertTrue(
                    "$action should name '$param' as missing",
                    (result as ActionSchema.ValidationResult.MissingParams).params.contains(param)
                )
            }
        }
    }

    @Test
    fun `blank is still accepted where the schema allows it`() {
        // Writing an empty file is legitimate: only the path is blank-rejected, not the content.
        val (result, enriched) = ActionSchema.validateParams(
            "WRITE_FILE",
            mapOf("filePath" to "notes/empty.txt", "content" to "")
        )
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("", enriched["content"])

        // Non-file actions are unaffected by the blank rule.
        val (openResult, _) = ActionSchema.validateParams("OPEN_APP", mapOf("appName" to ""))
        assertTrue(openResult is ActionSchema.ValidationResult.Valid)
    }

    @Test
    fun `delete file param definition rejects blank`() {
        val filePath = ActionSchema.getAction("DELETE_FILE")!!.params.single()
        assertEquals("filePath", filePath.name)
        assertTrue(filePath.required)
        assertFalse(filePath.allowBlank)

        // LIST_FILES keeps the default: a blank path just lists the workspace root.
        assertTrue(ActionSchema.getAction("LIST_FILES")!!.params.single().allowBlank)
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
}
