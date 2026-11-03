package com.opendroid.ai.core.llm

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@Suppress("DEPRECATION")
class OnDeviceEngineReleaseMonitorTest {

    /** Runs dispatched work only when the test drains it, so each step is deterministic. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runCurrent() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val dispatcher = QueueDispatcher()
    // OpenDroidApp's scope has no handler, so an exception reaching it would crash the app.
    private val uncaught = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> uncaught += error }
    )
    private var releases = 0
    private val monitor = OnDeviceEngineReleaseMonitor(scope) { releases++ }

    @Test
    fun `switching from On-Device AI to a cloud provider releases the engine`() {
        val activeProvider = MutableStateFlow("On-Device AI")
        monitor.watch(activeProvider)
        dispatcher.runCurrent()
        assertEquals(0, releases)

        activeProvider.value = "Anthropic Claude"
        dispatcher.runCurrent()

        assertEquals(1, releases)
    }

    @Test
    fun `moving between on-device providers keeps the engine`() {
        val activeProvider = MutableStateFlow("On-Device AI")
        monitor.watch(activeProvider)
        dispatcher.runCurrent()

        activeProvider.value = "LiteRT-LM (On-device)"
        dispatcher.runCurrent()
        activeProvider.value = "Gemma 4 (On-device)"
        dispatcher.runCurrent()

        assertEquals(0, releases)
    }

    @Test
    fun `moving between cloud providers releases only once`() {
        val activeProvider = MutableStateFlow("On-Device AI")
        monitor.watch(activeProvider)
        dispatcher.runCurrent()

        activeProvider.value = "OpenAI"
        dispatcher.runCurrent()
        activeProvider.value = "Groq"
        dispatcher.runCurrent()

        assertEquals(1, releases)
    }

    @Test
    fun `a failing settings read stops the watch without crashing`() {
        val job = monitor.watch(flow<String> { throw IllegalStateException("settings unreadable") })
        dispatcher.runCurrent()

        assertEquals(true, job.isCompleted)
        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(0, releases)
    }

    @Test
    fun `memory pressure releases the engine`() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ).forEach(monitor::onTrimMemory)
        dispatcher.runCurrent()

        assertEquals(5, releases)
    }

    @Test
    fun `mild trims and a hidden UI keep the engine`() {
        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        dispatcher.runCurrent()

        assertEquals(0, releases)
    }

    @Test
    fun `a trim launches the release instead of waiting on it`() {
        // onTrimMemory runs on the main thread, and the release waits for a running session.
        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(0, releases)

        dispatcher.runCurrent()
        assertEquals(1, releases)
    }
}
