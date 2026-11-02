package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.providers.LiteRTLMProvider
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetCollectCappedTest {

    @Test
    fun `stops the upstream once the output reaches the cap`() = runBlocking {
        var emitted = 0
        val chunks = flow {
            repeat(10_000) {
                emitted++
                emit("word ")
            }
        }

        PromptBudget.collectCapped(chunks, cap = 50) {}

        assertTrue("upstream emitted $emitted chunks", emitted < 300)
    }

    @Test
    fun `stops an upstream that never ends by itself`() = runBlocking {
        val chunks = flow {
            while (true) {
                emit("word ")
                delay(1)
            }
        }

        val text = withTimeout(10_000) { PromptBudget.collectCapped(chunks, cap = 5) {} }

        assertTrue(PromptBudget.estimateTokens(text) >= 5)
    }

    @Test
    fun `measures the cap on the whole output, not the latest chunk`() = runBlocking {
        // LiteRT-LM emits deltas, so one short chunk never reaches the cap on its own.
        var emitted = 0
        val chunks = flow {
            repeat(10_000) {
                emitted++
                emit("a")
            }
        }

        val text = PromptBudget.collectCapped(chunks, cap = 5) {}

        assertEquals("a".repeat(emitted), text)
        assertEquals(5, PromptBudget.estimateTokens(text))
    }

    @Test
    fun `returns the whole answer when it ends under the cap`() = runBlocking {
        val seen = mutableListOf<String>()

        val text = PromptBudget.collectCapped(flowOf("Rome ", "was ", "built"), cap = 500) { seen += it }

        assertEquals("Rome was built", text)
        assertEquals(listOf("Rome ", "was ", "built"), seen)
    }

    @Test
    fun `hands over the chunk that crosses the cap`() = runBlocking {
        val seen = mutableListOf<String>()

        val text = PromptBudget.collectCapped(flowOf("abc", "def", "ghi"), cap = 2) { seen += it }

        assertEquals("abcdef", text)
        assertEquals(listOf("abc", "def"), seen)
    }

    @Test
    fun `cancelling the caller stops the collection`() = runBlocking {
        val collecting = CompletableDeferred<Unit>()
        var cancelled = false
        val job = launch(Dispatchers.Default) {
            try {
                PromptBudget.collectCapped(
                    flow {
                        emit("word ")
                        awaitCancellation()
                    },
                    cap = 500
                ) { collecting.complete(Unit) }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        collecting.await()
        withTimeout(10_000) { job.cancelAndJoin() }

        assertTrue(cancelled)
    }

    @Test
    fun `complete path suspends instead of blocking under the provider's monitor`() {
        // The engine is native, so the provider cannot run on the JVM. A synchronized blocking method
        // here is what kept on-device planning uncancellable while it held the provider's lock.
        val inference = LiteRTLMProvider::class.java.declaredMethods.single { it.name == "invokeLiteRTInference" }

        assertFalse(Modifier.isSynchronized(inference.modifiers))
        assertEquals(Continuation::class.java, inference.parameterTypes.last())
    }
}
