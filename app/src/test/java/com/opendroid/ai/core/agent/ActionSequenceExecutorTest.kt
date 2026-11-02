package com.opendroid.ai.core.agent

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSequenceExecutorTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun `successful execution preserves order and runs every step`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            ActionResult.Success(mapOf("message" to "$action-result"))
        }

        val result = executor.execute(
            steps = listOf(
                step("second", order = 2, action = "SECOND"),
                step("first", order = 1, action = "FIRST")
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(listOf("FIRST", "SECOND"), calls)
        assertEquals("Macro completed successfully (2 steps).", result.data)
    }

    @Test
    fun `references use the completed result before dispatching the next step`() = runBlocking {
        val receivedParams = mutableListOf<Map<String, String>>()
        val executor = executor { action, params, _ ->
            receivedParams += params
            ActionResult.Success(mapOf("message" to if (action == "FIRST") "value" else "done"))
        }

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "FIRST"),
                step(
                    "second",
                    order = 2,
                    action = "SECOND",
                    params = mapOf("input" to ("$" + "first"), "legacy" to ("$" + "$" + "first"))
                )
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(mapOf("input" to "value", "legacy" to "value"), receivedParams[1])
    }

    @Test
    fun `a reference to a step's result field resolves to the result, not the result plus the suffix`() {
        val executor = executor { _, _, _ -> error("must not dispatch") }

        val resolved = executor.resolveParameters(
            mapOf("single" to "Weather: \$s1.result", "double" to "Weather: \$\$s1.result"),
            completedResults = mapOf("s1" to "Sunny")
        )

        assertEquals(mapOf("single" to "Weather: Sunny", "double" to "Weather: Sunny"), resolved)
    }

    @Test
    fun `an unresolved template reference is not sent, while a resolved one is`() {
        val executor = executor { _, _, _ -> error("must not dispatch") }
        val completed = mapOf("s1" to "Sunny")

        val templated = executor.resolveParameters(
            mapOf("contact" to "+15551234567", "message" to "Weather: {{s1.result}}"), completed
        )
        assertEquals(
            ActionSchema.ValidationResult.MissingParams(listOf("message")),
            ActionSchema.validateParams("SEND_SMS", templated).first
        )

        val referenced = executor.resolveParameters(
            mapOf("contact" to "+15551234567", "message" to "Weather: \$\$s1"), completed
        )
        val (validation, params) = ActionSchema.validateParams("SEND_SMS", referenced)
        assertEquals(ActionSchema.ValidationResult.Valid, validation)
        assertEquals("Weather: Sunny", params["message"])
    }

    @Test
    fun `failed primary action uses one fallback and continues only after fallback succeeds`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                calls += action
                if (action == "PRIMARY") ActionResult.Failure("primary failed")
                else ActionResult.Success(mapOf("message" to "fallback result"))
            },
            hasAction = { it == "FALLBACK" }
        )

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "PRIMARY", fallback = "FALLBACK"),
                step("second", order = 2, action = "SECOND")
            ),
            context = context
        )

        assertTrue(result.success)
        assertEquals(listOf("PRIMARY", "FALLBACK", "SECOND"), calls)
    }

    @Test
    fun `failed fallback stops the macro and does not claim later steps ran`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                calls += action
                ActionResult.Failure("$action failed")
            },
            hasAction = { it == "FALLBACK" }
        )

        val result = executor.execute(
            steps = listOf(
                step("first", order = 1, action = "PRIMARY", fallback = "FALLBACK"),
                step("later", order = 2, action = "LATER")
            ),
            context = context
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("step 1"))
        assertTrue(result.error!!.contains("FALLBACK"))
        assertFalse(result.error!!.contains("LATER"))
        assertEquals(listOf("PRIMARY", "FALLBACK"), calls)
    }

    @Test
    fun `a declined step never runs its fallback`() {
        val executor = executor { _, _, _ -> error("must not dispatch") }

        assertFalse(
            executor.shouldAttemptFallback(
                ActionResult.Declined("Action cancelled by user response: no"),
                step("confirm", order = 1, action = "CONFIRM_ACTION", fallback = "SEND_SMS")
            )
        )
    }

    @Test
    fun `a macro stops at a declined step and reports the decline`() = runBlocking {
        val calls = mutableListOf<String>()
        val declined = ActionResult.Declined("Action cancelled by user response: no")
        val executor = executor { action, _, _ ->
            calls += action
            declined
        }

        val result = executor.execute(
            steps = listOf(
                step("confirm", order = 1, action = "CONFIRM_ACTION"),
                step("send", order = 2, action = "SEND_SMS")
            ),
            context = context
        )

        assertEquals(declined, result)
        assertEquals(listOf("CONFIRM_ACTION"), calls)
    }

    @Test
    fun `a cancel during the primary action never fires its fallback`() {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            // A handler that swallows the cancel and reports an ordinary failure.
            currentCoroutineContext().job.cancel()
            ActionResult.Failure("interrupted")
        }

        runBlocking {
            val job = launch {
                executor.executeStep(
                    step("s1", order = 1, action = "ANALYZE_SCREENSHOT", fallback = "SEND_SMS"),
                    completedResults = emptyMap(),
                    context = context
                )
            }
            job.join()
            assertTrue(job.isCancelled)
        }

        assertEquals(listOf("ANALYZE_SCREENSHOT"), calls)
    }

    @Test
    fun `a completed step's result is the action's own data, with a placeholder when it has none`() {
        assertEquals("screen text", completedStepResult(ActionResult.Success(mapOf("message" to "screen text"))))
        assertEquals("Completed successfully.", completedStepResult(ActionResult.Success()))
    }

    @Test
    fun `composer left open for the user never falls back to another messaging app`() = runBlocking {
        listOf("SEND_WHATSAPP" to "SEND_SMS", "SEND_SMS" to "SEND_WHATSAPP").forEach { (primary, fallback) ->
            val calls = mutableListOf<String>()
            val executor = executor { action, _, _ ->
                calls += action
                if (action == primary) ActionResult.UserActionRequired("Chat is open with your message ready. Tap Send.")
                else ActionResult.Success(mapOf("message" to "Text sent to Mom!"))
            }
            val handOffStep = step("send", order = 1, action = primary, fallback = fallback)

            assertFalse(executor.shouldAttemptFallback(ActionResult.UserActionRequired("open"), handOffStep))

            val result = executor.execute(listOf(handOffStep), context)

            assertEquals(listOf(primary), calls)
            assertFalse(result.success)
            assertFalse(result.error.orEmpty().contains("Text sent"))
        }
    }

    private fun executor(
        executeAction: suspend (String, Map<String, String>, Context) -> ActionResult
    ) = ActionSequenceExecutor(executeAction, hasAction = { true })

    private fun step(
        id: String,
        order: Int,
        action: String,
        params: Map<String, String> = emptyMap(),
        fallback: String = ""
    ) = PlanStep(
        stepId = id,
        order = order,
        description = action,
        action = action,
        params = params,
        fallback = fallback
    )
}
