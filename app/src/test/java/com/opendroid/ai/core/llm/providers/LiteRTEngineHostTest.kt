package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.OnDeviceBackend
import com.opendroid.ai.core.llm.OnDeviceModelSpec
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRTEngineHostTest {

    private val spec = OnDeviceModelSpec(
        id = "fake-model",
        displayName = "Fake Model",
        family = "Fake",
        sizeLabel = "1B",
        backend = OnDeviceBackend.LITERT_LM,
        contextWindow = 4096
    )

    /** Hands out [FakeEngine]s and shares their bookkeeping, so a swap of engines is seen too. */
    private class FakeRuntime(private val tokens: Int = 20, private val tokenDelayMs: Long = 5) {
        val engines = mutableListOf<FakeEngine>()
        val openSessions = AtomicInteger()
        val maxConcurrentSessions = AtomicInteger()
        @Volatile var closedWhileOpen = false

        fun open(@Suppress("UNUSED_PARAMETER") path: String, @Suppress("UNUSED_PARAMETER") spec: OnDeviceModelSpec): LiteRTEngine =
            FakeEngine().also { synchronized(engines) { engines += it } }

        inner class FakeEngine : LiteRTEngine {
            @Volatile var closed = false

            override fun openSession(temperature: Double): LiteRTSession {
                maxConcurrentSessions.accumulateAndGet(openSessions.incrementAndGet(), ::maxOf)
                return object : LiteRTSession {
                    override fun textChunks(prompt: String): Flow<String> = flow {
                        when (prompt) {
                            SESSION_FAILURE -> throw IOException("session failed")
                            JNI_FAILURE -> throw UnsatisfiedLinkError("native side gone")
                        }
                        repeat(tokens) {
                            delay(tokenDelayMs)
                            emit("tok ")
                        }
                    }

                    override fun cancelAndClose() {
                        openSessions.decrementAndGet()
                    }
                }
            }

            override fun close() {
                if (openSessions.get() > 0) closedWhileOpen = true
                closed = true
            }
        }
    }

    @Test
    fun `a failing complete during a stream neither shares nor closes the engine`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        val host = LiteRTEngineHost(runtime::open)
        val streaming = CompletableDeferred<Unit>()

        val stream = launch {
            host.stream(PATH, spec, PROMPT, 500, 0.5f).collect { streaming.complete(Unit) }
        }
        streaming.await()
        val failure = runCatching { host.complete(PATH, spec, SESSION_FAILURE, 500, 0.5f) }
        stream.join()

        assertTrue(failure.isFailure)
        assertEquals(1, runtime.maxConcurrentSessions.get())
        assertFalse(runtime.closedWhileOpen)
    }

    @Test
    fun `a stream delivers its whole reply while a complete waits for it`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime(tokens = 20)
        val host = LiteRTEngineHost(runtime::open)
        val streaming = CompletableDeferred<Unit>()
        val chunks = mutableListOf<String>()

        val stream = launch {
            host.stream(PATH, spec, PROMPT, 500, 0.5f).collect {
                chunks += it
                streaming.complete(Unit)
            }
        }
        streaming.await()
        val reply = host.complete(PATH, spec, PROMPT, 500, 0.5f)
        stream.join()

        assertEquals(20, chunks.size)
        assertEquals("tok ".repeat(20), reply)
        assertEquals(1, runtime.maxConcurrentSessions.get())
    }

    @Test
    fun `a model switch during a stream waits for the stream before closing the engine`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        val host = LiteRTEngineHost(runtime::open)
        val streaming = CompletableDeferred<Unit>()

        val stream = launch {
            host.stream(PATH, spec, PROMPT, 500, 0.5f).collect { streaming.complete(Unit) }
        }
        streaming.await()
        host.complete("other/model.litertlm", spec, PROMPT, 500, 0.5f)
        stream.join()

        assertFalse(runtime.closedWhileOpen)
        assertEquals(1, runtime.maxConcurrentSessions.get())
        assertEquals(2, runtime.engines.size)
        assertTrue(runtime.engines.first().closed)
    }

    @Test
    fun `concurrent first calls load one engine`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        val host = LiteRTEngineHost(runtime::open)

        List(4) { async { host.complete(PATH, spec, PROMPT, 500, 0.5f) } }.awaitAll()

        assertEquals(1, runtime.engines.size)
        assertEquals(1, runtime.maxConcurrentSessions.get())
    }

    @Test
    fun `a session error keeps the engine for the next call`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        val host = LiteRTEngineHost(runtime::open)

        val failure = runCatching { host.complete(PATH, spec, SESSION_FAILURE, 500, 0.5f) }.exceptionOrNull()
        val streamFailure = runCatching { host.stream(PATH, spec, SESSION_FAILURE, 500, 0.5f).toList() }.exceptionOrNull()
        host.complete(PATH, spec, PROMPT, 500, 0.5f)

        assertTrue(failure is IOException)
        assertTrue(streamFailure is IOException)
        assertEquals(1, runtime.engines.size)
        assertFalse(runtime.engines.single().closed)
        assertEquals(0, runtime.openSessions.get())
    }

    @Test
    fun `a prompt that is too long keeps the engine`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        val host = LiteRTEngineHost(runtime::open)
        host.complete(PATH, spec, PROMPT, 500, 0.5f)
        val tooLong = "word ".repeat(10_000)

        val failure = runCatching { host.complete(PATH, spec, tooLong, 500, 0.5f) }.exceptionOrNull()
        val streamFailure = runCatching { host.stream(PATH, spec, tooLong, 500, 0.5f).toList() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("too long for Fake Model"))
        assertTrue(streamFailure is IllegalStateException)
        assertEquals(1, runtime.engines.size)
        assertFalse(runtime.engines.single().closed)
    }

    @Test
    fun `a JNI failure closes the engine after its session and the next call reloads`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        val host = LiteRTEngineHost(runtime::open)

        val failure = runCatching { host.complete(PATH, spec, JNI_FAILURE, 500, 0.5f) }.exceptionOrNull()
        host.complete(PATH, spec, PROMPT, 500, 0.5f)

        assertTrue(failure is UnsatisfiedLinkError)
        assertFalse(runtime.closedWhileOpen)
        assertEquals(2, runtime.engines.size)
        assertTrue(runtime.engines.first().closed)
        assertFalse(runtime.engines.last().closed)
    }

    @Test
    fun `a failed engine load is retried by the next call`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime()
        var loads = 0
        val host = LiteRTEngineHost { path, loadedSpec ->
            if (loads++ == 0) throw IllegalArgumentException("bad model")
            runtime.open(path, loadedSpec)
        }

        val failure = runCatching { host.complete(PATH, spec, PROMPT, 500, 0.5f) }.exceptionOrNull()
        host.complete(PATH, spec, PROMPT, 500, 0.5f)

        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.startsWith("LiteRT Engine initialization failed"))
        assertEquals(2, loads)
    }

    @Test
    fun `cancelling a stream ends its session and frees the engine`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime(tokens = 100_000)
        val host = LiteRTEngineHost(runtime::open)
        val streaming = CompletableDeferred<Unit>()

        val stream = launch {
            host.stream(PATH, spec, PROMPT, 500, 0.5f).collect { streaming.complete(Unit) }
        }
        streaming.await()
        stream.cancelAndJoin()

        assertEquals(0, runtime.openSessions.get())
        withTimeout(10_000) { host.stream(PATH, spec, PROMPT, 500, 0.5f).first() }
        assertEquals(0, runtime.openSessions.get())
        assertEquals(1, runtime.maxConcurrentSessions.get())
    }

    @Test
    fun `a complete waiting on a stream can be cancelled`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime(tokens = 100_000)
        val host = LiteRTEngineHost(runtime::open)
        val streaming = CompletableDeferred<Unit>()

        val stream = launch {
            host.stream(PATH, spec, PROMPT, 100_000, 0.5f).collect { streaming.complete(Unit) }
        }
        streaming.await()
        val waiting = launch { host.complete(PATH, spec, PROMPT, 500, 0.5f) }
        delay(50)
        withTimeout(10_000) { waiting.cancelAndJoin() }

        assertTrue(stream.isActive)
        assertEquals(1, runtime.openSessions.get())
        stream.cancelAndJoin()
    }

    @Test
    fun `holds reports only the model the cached engine was loaded with`() = runBlocking {
        val host = LiteRTEngineHost(FakeRuntime(tokens = 1)::open)
        assertFalse(host.holds(PATH))

        host.complete(PATH, spec, PROMPT, 500, 0.5f)
        assertTrue(host.holds(PATH))
        assertFalse(host.holds("other/model.litertlm"))

        host.complete("other/model.litertlm", spec, PROMPT, 500, 0.5f)
        assertFalse(host.holds(PATH))
        assertTrue(host.holds("other/model.litertlm"))
    }

    @Test
    fun `closeIfLoadedFrom closes only an engine loaded from that model folder`() = runBlocking {
        val runtime = FakeRuntime(tokens = 1)
        val host = LiteRTEngineHost(runtime::open)
        host.complete(PATH, spec, PROMPT, 500, 0.5f)

        host.closeIfLoadedFrom(File("other"))
        assertTrue(host.holds(PATH))
        assertFalse(runtime.engines.single().closed)

        host.closeIfLoadedFrom(File(PATH).parentFile!!)
        assertFalse(host.holds(PATH))
        assertTrue(runtime.engines.single().closed)
    }

    @Test
    fun `close releases the cached engine once its running session ends`() = runBlocking(Dispatchers.Default) {
        val runtime = FakeRuntime(tokens = 20, tokenDelayMs = 5)
        val host = LiteRTEngineHost(runtime::open)
        val firstToken = CompletableDeferred<Unit>()

        val stream = launch {
            host.stream(PATH, spec, PROMPT, 500, 0.5f).collect { firstToken.complete(Unit) }
        }
        firstToken.await()

        withTimeout(5_000) { host.close() }
        stream.join()

        assertFalse(host.holds(PATH))
        assertTrue(runtime.engines.single().closed)
        assertFalse(runtime.closedWhileOpen)
    }

    @Test
    fun `close without a cached engine does nothing`() = runBlocking {
        val runtime = FakeRuntime(tokens = 1)
        val host = LiteRTEngineHost(runtime::open)

        host.close()

        assertTrue(runtime.engines.isEmpty())
    }

    private companion object {
        const val PATH = "models/fake.litertlm"
        const val PROMPT = "Model:"
        const val SESSION_FAILURE = "session failure"
        const val JNI_FAILURE = "jni failure"
    }
}
