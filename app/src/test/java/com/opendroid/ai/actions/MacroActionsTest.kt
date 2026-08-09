package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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

    private class InMemoryMacroDao(vararg initial: MacroEntity) : MacroDao {
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
            macros.removeAll { it.id == id }
            flow.value = macros.toList()
        }
        override suspend fun clearAllMacros() {
            macros.clear()
            flow.value = emptyList()
        }
    }
}
