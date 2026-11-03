package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ActionDispatcherSafetyTest {
    private val context: Context = ContextWrapper(null)

    private fun handler(block: () -> ActionResult) = object : Action {
        override val name = "TEST"
        override suspend fun execute(params: Map<String, String>, context: Context) = block()
    }

    @Test
    fun `a cancel inside a handler is rethrown, not turned into a failure`() = runBlocking {
        try {
            ActionDispatcher.safeExecute(handler { throw CancellationException("stopped") }, emptyMap(), context, "TEST")
            fail("CancellationException was swallowed")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }
    }

    @Test
    fun `an ordinary handler exception still becomes a failure`() = runBlocking {
        val result = ActionDispatcher.safeExecute(handler { error("boom") }, emptyMap(), context, "TEST")
        assertFalse(result.success)
        assertEquals("boom", result.error)
    }

    @Test
    fun `an invalid enum value asks the user to pick one of the options`() {
        val (validation, _) = ActionSchema.validateParams("AUTO_REPLY_TOGGLE", mapOf("state" to "banana"))
        val prompt = ActionDispatcher.invalidParamPrompt(validation as ActionSchema.ValidationResult.InvalidParam)

        assertTrue(prompt is ActionResult.NeedsInput)
        prompt as ActionResult.NeedsInput
        assertEquals(listOf("on", "off"), prompt.options)
        assertEquals("state", prompt.metadata["param"])
        assertTrue(prompt.question, "banana" in prompt.question)
    }
}
