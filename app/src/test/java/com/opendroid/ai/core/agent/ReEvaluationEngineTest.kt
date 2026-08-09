package com.opendroid.ai.core.agent

import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMProviderResolver
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.StreamChunk
import com.opendroid.ai.core.llm.ToolDefinition
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReEvaluationEngineTest {
    @Test
    fun `malformed local re-evaluation escalates once to explicit available fallback`() = runBlocking {
        val local = FakeProvider("On-Device AI", "not json")
        val remote = FakeProvider("OpenAI", """{"speech":"fixed","decision":"CONTINUE"}""")
        val resolver = FakeResolver(local, remote)
        val engine = ReEvaluationEngine(resolver, unusedDao())

        val result = engine.evaluateStepResult(
            originalGoal = "read the weather",
            completedSteps = emptyList(),
            failedSteps = emptyList(),
            remainingSteps = listOf(step("GET_SYSTEM_INFO")),
            planId = "plan-1"
        )

        assertEquals("CONTINUE", result.decision)
        assertEquals(1, local.calls)
        assertEquals(1, remote.calls)
        assertEquals(listOf("On-Device AI"), resolver.excludedProviders)
    }

    @Test
    fun `malformed local re-evaluation fails clearly without fallback`() = runBlocking {
        val resolver = FakeResolver(FakeProvider("On-Device AI", "not json"), null)
        val engine = ReEvaluationEngine(resolver, unusedDao())

        val failure = runCatching {
            engine.evaluateStepResult(
                originalGoal = "read the weather",
                completedSteps = emptyList(),
                failedSteps = emptyList(),
                remainingSteps = listOf(step("GET_SYSTEM_INFO")),
                planId = "plan-1"
            )
        }.exceptionOrNull()

        assertTrue(failure is LLMException)
        assertEquals(LLMError.NoSafeFallback, (failure as LLMException).error)
    }

    @Test
    fun `malformed remote response preserves the existing non-retryable error contract`() = runBlocking {
        val remote = FakeProvider("OpenAI", "not json")
        val resolver = FakeResolver(remote, FakeProvider("Google Gemini", """{"speech":"fallback","decision":"CONTINUE"}"""))
        val engine = ReEvaluationEngine(resolver, unusedDao())

        val failure = runCatching {
            engine.evaluateStepResult(
                originalGoal = "read the weather",
                completedSteps = emptyList(),
                failedSteps = emptyList(),
                remainingSteps = listOf(step("GET_SYSTEM_INFO")),
                planId = "plan-1"
            )
        }.exceptionOrNull()

        assertTrue(failure is LLMException)
        assertEquals(LLMError.MalformedResponse, (failure as LLMException).error)
        assertTrue(!failure.retryable)
        assertTrue(resolver.excludedProviders.isEmpty())
    }

    @Test
    fun `same provider returned as fallback is rejected`() = runBlocking {
        val local = FakeProvider("On-Device AI", "not json")
        val engine = ReEvaluationEngine(FakeResolver(local, local), unusedDao())

        val failure = runCatching {
            engine.evaluateStepResult(
                originalGoal = "read the weather",
                completedSteps = emptyList(),
                failedSteps = emptyList(),
                remainingSteps = listOf(step("GET_SYSTEM_INFO")),
                planId = "plan-1"
            )
        }.exceptionOrNull()

        assertTrue(failure is LLMException)
        assertEquals(LLMError.NoSafeFallback, (failure as LLMException).error)
        assertEquals(1, local.calls)
    }

    @Test
    fun `decoded plan with unknown action is treated as unusable and escalated`() = runBlocking {
        val local = FakeProvider(
            "On-Device AI",
            """{"speech":"fixed","decision":"MODIFY","updatedPlan":{"planId":"p","goal":"g","estimatedDuration":"now","estimatedSteps":1,"steps":[{"stepId":"s1","order":1,"description":"bad","action":"NOT_REGISTERED"}]}}"""
        )
        val remote = FakeProvider("OpenAI", """{"speech":"fixed","decision":"CONTINUE"}""")
        val engine = ReEvaluationEngine(FakeResolver(local, remote), unusedDao())

        val result = engine.evaluateStepResult(
            originalGoal = "read the weather",
            completedSteps = emptyList(),
            failedSteps = emptyList(),
            remainingSteps = listOf(step("GET_SYSTEM_INFO")),
            planId = "plan-1"
        )

        assertEquals("CONTINUE", result.decision)
        assertEquals(1, local.calls)
        assertEquals(1, remote.calls)
    }

    private fun step(action: String) = PlanStep("s1", 1, action, action)

    private fun unusedDao(): dagger.Lazy<UnknownActionDao> = object : dagger.Lazy<UnknownActionDao> {
        override fun get(): UnknownActionDao = error("DAO is not used by evaluateStepResult tests")
    }

    private class FakeResolver(
        private val active: FakeProvider,
        private val fallback: FakeProvider?
    ) : LLMProviderResolver {
        val excludedProviders = mutableListOf<String>()

        override suspend fun getActiveProvider(actionNames: Collection<String>): LLMProvider = active

        override suspend fun getExplicitFallbackProvider(excludedProvider: String): LLMProvider? {
            excludedProviders += excludedProvider
            return fallback?.takeIf { it.name != excludedProvider && it.available }
        }
    }

    private class FakeProvider(
        override val name: String,
        private val content: String,
        val available: Boolean = true
    ) : LLMProvider {
        var calls: Int = 0
        override val availableModels: List<String> = listOf("test-model")

        override suspend fun complete(request: LLMRequest): LLMResponse {
            calls++
            return LLMResponse(content, 1, "test-model", name, 3L)
        }

        override fun streamComplete(request: LLMRequest): Flow<String> = flow { emit(content) }
        override suspend fun isAvailable(): Boolean = available
        override suspend fun generate(messages: List<com.opendroid.ai.data.models.ChatMessage>, tools: List<ToolDefinition>): Flow<StreamChunk> =
            flow { emit(StreamChunk.Content(content)) }
    }
}
