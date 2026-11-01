package com.opendroid.ai.core.llm

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCapTest {

    @Test
    fun `collectCapped stops the upstream once the cap is reached`() = runBlocking {
        var emitted = 0
        val result = OutputCap.collectCapped(
            flow { repeat(10_000) { emitted++; emit("word ") } },
            cap = 50
        ) {}

        // "word " is 5 chars ~= 1.25 tokens; the cap trips after ~40 emissions.
        // With the old return@collect pattern every one of the 10,000 emissions ran.
        assertTrue("upstream kept producing: $emitted emissions", emitted < 300)
        assertTrue(result.truncated)
        assertTrue(PromptBudget.estimateTokens(result.text) >= 50)
    }

    @Test
    fun `collectCapped forwards every delta up to and including the one that hits the cap`() = runBlocking {
        val forwarded = mutableListOf<String>()
        val result = OutputCap.collectCapped(
            flow { repeat(10_000) { emit("abcd") } },
            cap = 3
        ) { forwarded += it }

        assertEquals(listOf("abcd", "abcd", "abcd"), forwarded)
        assertEquals("abcdabcdabcd", result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun `collectCapped reports no truncation when the flow completes under the cap`() = runBlocking {
        val forwarded = mutableListOf<String>()
        val result = OutputCap.collectCapped(flowOf("Hello", ", ", "world"), cap = 1000) { forwarded += it }

        assertEquals(listOf("Hello", ", ", "world"), forwarded)
        assertEquals("Hello, world", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `snapshotDeltas emits only newly appended text`() = runBlocking {
        val deltas = OutputCap.snapshotDeltas(
            flowOf("He", "He", "Hello", "Hello, ", "Hello, world")
        ).toList()

        assertEquals(listOf("He", "llo", ", ", "world"), deltas)
    }

    @Test
    fun `cap applies to cumulative snapshots through snapshotDeltas`() = runBlocking {
        var emitted = 0
        val snapshots = flow {
            val sb = StringBuilder()
            repeat(10_000) {
                emitted++
                sb.append("word ")
                emit(sb.toString())
            }
        }

        val result = OutputCap.collectCapped(OutputCap.snapshotDeltas(snapshots), cap = 50) {}

        assertTrue("upstream kept producing: $emitted emissions", emitted < 300)
        assertTrue(result.truncated)
        assertEquals("word ".repeat(emitted), result.text)
    }
}
