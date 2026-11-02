package com.opendroid.ai.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadPartialFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for the DAO progress write that a WorkManager stop cancels mid-flight. */
    private class ProgressWrites(private val cancelOnCall: Int) {
        var calls = 0
            private set

        suspend fun write() {
            calls++
            if (calls == cancelOnCall) throw CancellationException("Work stopped")
        }
    }

    @Test
    fun `a stop during a progress write keeps the partial file for resume`() {
        val partial = folder.newFile("model.download")
        val progress = ProgressWrites(cancelOnCall = 2)

        val stopped = runCatching {
            runBlocking {
                withPartialDownloadFile(partial) { _ ->
                    repeat(3) {
                        partial.appendBytes(ByteArray(CHUNK))
                        progress.write()
                    }
                }
            }
        }.exceptionOrNull()

        assertTrue(stopped is CancellationException)
        assertTrue(partial.exists())
        assertEquals(2L * CHUNK, partial.length())
    }

    @Test
    fun `a failed attempt deletes the partial file`() {
        val partial = folder.newFile("model.download")

        runCatching {
            runBlocking {
                withPartialDownloadFile(partial) { _ ->
                    partial.appendBytes(ByteArray(CHUNK))
                    throw IllegalStateException("Downloaded payload exceeded its published size")
                }
            }
        }

        assertFalse(partial.exists())
    }

    @Test
    fun `an attempt that asks to resume keeps the partial file`() = runBlocking {
        val partial = folder.newFile("model.download")

        withPartialDownloadFile(partial) { keepForResume ->
            partial.appendBytes(ByteArray(CHUNK))
            keepForResume()
        }

        assertTrue(partial.exists())
    }

    @Test
    fun `a finished attempt deletes the partial file`() = runBlocking {
        val partial = folder.newFile("model.download")

        withPartialDownloadFile(partial) { _ -> partial.appendBytes(ByteArray(CHUNK)) }

        assertFalse(partial.exists())
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
