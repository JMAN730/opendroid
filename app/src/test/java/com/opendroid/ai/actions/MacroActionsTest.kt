package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.agent.ActionCategory
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroActionsTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun `run macro dispatches decoded steps instead of returning raw JSON`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity(
                id = "macro-1",
                name = "morning",
                trigger = "manual",
                stepsJson = "[{\"stepId\":\"one\",\"order\":1,\"description\":\"First\",\"action\":\"FIRST\"}]",
                isSystem = false,
                isEnabled = true
            )
        )
        val calls = mutableListOf<String>()
        val actions = MacroActions(
            macroDao = dao,
            actionSequenceExecutor = ActionSequenceExecutor(
                executeAction = { action, _, _ ->
                    calls += action
                    com.opendroid.ai.actions.base.ActionResult.Success()
                },
                hasAction = { true }
            )
        )

        val result = actions.getActions().first { it.name == "RUN_MACRO" }
            .execute(mapOf("macroName" to "morning"), context)

        assertTrue(result.success)
        assertTrue(result.data!!.contains("completed successfully"))
        assertFalse(result.data!!.contains("stepsJson"))
        assertTrue(calls == listOf("FIRST"))
    }

    @Test
    fun `run macro reports missing and malformed data truthfully`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity("bad", "bad", "manual", "not-json", false, true),
            MacroEntity("empty", "empty", "manual", "", false, true)
        )
        val actions = MacroActions(
            dao,
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "RUN_MACRO" }

        val missing = actions.execute(mapOf("macroName" to "missing"), context)
        val malformed = actions.execute(mapOf("macroName" to "bad"), context)
        val empty = actions.execute(mapOf("macroName" to "empty"), context)

        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("not found"))
        assertFalse(malformed.success)
        assertTrue(malformed.error!!.contains("invalid step data"))
        assertFalse(empty.success)
        assertTrue(empty.error!!.contains("no step data"))
    }

    @Test
    fun `delete macro removes a found custom macro`() = runBlocking {
        val dao = InMemoryMacroDao(MacroEntity("macro-1", "morning", "manual", "[]", false, true))
        val action = MacroActions(
            dao,
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "morning"), context)

        assertTrue(result.success)
        assertEquals("Macro 'morning' deleted.", result.data)
        assertNull(dao.getMacroByName("morning"))
    }

    @Test
    fun `delete macro reports when the requested macro does not exist`() = runBlocking {
        val dao = InMemoryMacroDao()
        val action = MacroActions(
            dao,
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "missing"), context)

        assertFalse(result.success)
        assertEquals("Macro with name 'missing' not found.", result.error)
    }

    @Test
    fun `delete macro refuses to remove a system macro`() = runBlocking {
        val dao = InMemoryMacroDao(MacroEntity("system-1", "startup", "manual", "[]", true, true))
        val action = MacroActions(
            dao,
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "startup"), context)

        assertFalse(result.success)
        assertEquals("System macro 'startup' cannot be deleted.", result.error)
        assertTrue(dao.getMacroByName("startup") != null)
    }

    @Test
    fun `delete macro reports failure when the DAO leaves the macro in place`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity("macro-1", "morning", "manual", "[]", false, true),
            deleteSucceeds = false
        )
        val action = MacroActions(
            dao,
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "DELETE_MACRO" }

        val result = action.execute(mapOf("macroName" to "morning"), context)

        assertFalse(result.success)
        assertEquals("Couldn't delete macro 'morning'.", result.error)
        assertTrue(dao.getMacroByName("morning") != null)
    }

    @Test
    fun `list macros reports when none are saved`() = runBlocking {
        val action = MacroActions(
            InMemoryMacroDao(),
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "LIST_MACROS" }

        val result = action.execute(emptyMap(), context)

        assertTrue(result.success)
        assertEquals("No macros found.", result.data)
    }

    @Test
    fun `list macros returns names without exposing stored step data`() = runBlocking {
        val dao = InMemoryMacroDao(
            MacroEntity("macro-2", "evening", "manual", "secret-evening-steps", false, true),
            MacroEntity("macro-1", "morning", "manual", "secret-morning-steps", false, true)
        )
        val action = MacroActions(
            dao,
            ActionSequenceExecutor({ _, _, _ -> error("must not dispatch") }, { true })
        ).getActions().first { it.name == "LIST_MACROS" }

        val result = action.execute(emptyMap(), context)

        assertTrue(result.success)
        assertEquals("Saved macros:\n- evening\n- morning", result.data)
        assertFalse(result.data!!.contains("secret-"))
    }

    @Test
    fun `macro management actions are registered with the expected schemas`() {
        val delete = ActionSchema.getAction("DELETE_MACRO")
        val list = ActionSchema.getAction("LIST_MACROS")

        assertEquals(ActionCategory.MACRO, delete!!.category)
        assertEquals(listOf("macroName"), delete.params.map { it.name })
        assertTrue(delete.params.single().required)
        assertTrue(delete.neverAutoApprove)
        val (missingDeleteParam, _) = ActionSchema.validateParams("DELETE_MACRO", emptyMap())
        assertTrue(missingDeleteParam is ActionSchema.ValidationResult.MissingParams)

        assertEquals(ActionCategory.MACRO, list!!.category)
        assertTrue(list.params.isEmpty())
    }

    private class InMemoryMacroDao(
        vararg initial: MacroEntity,
        private val deleteSucceeds: Boolean = true
    ) : MacroDao {
        private val macros = initial.toMutableList()
        private val flow = MutableStateFlow(macros.toList())

        override fun getAllMacrosFlow(): Flow<List<MacroEntity>> = flow
        override suspend fun getAllMacros(): List<MacroEntity> = macros.toList()
        override suspend fun getMacroById(id: String): MacroEntity? = macros.firstOrNull { it.id == id }
        override suspend fun getMacroByName(name: String): MacroEntity? = macros.firstOrNull { it.name == name }
        override suspend fun insertMacro(macro: MacroEntity) {
            macros.removeAll { it.id == macro.id }
            macros += macro
            flow.value = macros.toList()
        }
        override suspend fun deleteMacro(id: String) {
            if (!deleteSucceeds) return
            macros.removeAll { it.id == id }
            flow.value = macros.toList()
        }
        override suspend fun clearAllMacros() {
            macros.clear()
            flow.value = emptyList()
        }
    }
}
