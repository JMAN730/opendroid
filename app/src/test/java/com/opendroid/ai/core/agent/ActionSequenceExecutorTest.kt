package com.opendroid.ai.core.agent

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.data.models.PlanStep
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
    fun `failed fallback does not stop an independent later step`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = ActionSequenceExecutor(
            executeAction = { action, _, _ ->
                calls += action
                if (action == "LATER") ActionResult.Success(mapOf("message" to "later completed"))
                else ActionResult.Failure("$action failed")
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
        assertTrue(result.error!!.contains("1 completed"))
        assertTrue(result.error!!.contains("1 failed"))
        assertTrue(result.error!!.contains("FALLBACK"))
        assertEquals(listOf("PRIMARY", "FALLBACK", "LATER"), calls)
    }

    @Test
    fun `failed step skips dependents but continues independent work`() = runBlocking {
        val calls = mutableListOf<String>()
        val executor = executor { action, _, _ ->
            calls += action
            if (action == "PRIMARY") ActionResult.Failure("failed")
            else ActionResult.Success(mapOf("message" to "done"))
        }

        val result = executor.execute(
            steps = listOf(
                step("primary", order = 1, action = "PRIMARY"),
                step("dependent", order = 2, action = "DEPENDENT", dependsOn = listOf("primary")),
                step("independent", order = 3, action = "INDEPENDENT")
            ),
            context = context
        )

        assertFalse(result.success)
        assertEquals(listOf("PRIMARY", "INDEPENDENT"), calls)
        assertTrue(result.error!!.contains("1 failed"))
        assertTrue(result.error!!.contains("1 skipped"))
        assertTrue(result.error!!.contains("dependencies"))
    }

    private fun executor(
        executeAction: suspend (String, Map<String, String>, Context) -> ActionResult
    ) = ActionSequenceExecutor(executeAction, hasAction = { true })

    private fun step(
        id: String,
        order: Int,
        action: String,
        params: Map<String, String> = emptyMap(),
        dependsOn: List<String> = emptyList(),
        fallback: String = ""
    ) = PlanStep(
        stepId = id,
        order = order,
        description = action,
        action = action,
        params = params,
        dependsOn = dependsOn,
        fallback = fallback
    )
}
