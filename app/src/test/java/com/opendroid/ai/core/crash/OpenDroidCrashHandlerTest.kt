package com.opendroid.ai.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroidCrashHandlerTest {

    private class RecordingRecorder(var failWith: Throwable? = null) : CrashRecorder {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun record(thread: Thread, throwable: Throwable) {
            calls += thread to throwable
            failWith?.let { throw it }
        }
    }

    private class RecordingDelegate : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            calls += thread to throwable
        }
    }

    @Test
    fun `records the crash`() {
        val recorder = RecordingRecorder()
        val handler = OpenDroidCrashHandler(recorder, RecordingDelegate())
        val crash = IllegalStateException("boom")
        val thread = Thread.currentThread()

        handler.uncaughtException(thread, crash)

        assertEquals(1, recorder.calls.size)
        assertSame(thread, recorder.calls[0].first)
        assertSame(crash, recorder.calls[0].second)
    }

    @Test
    fun `delegates to the system handler so the process still dies`() {
        val delegate = RecordingDelegate()
        val handler = OpenDroidCrashHandler(RecordingRecorder(), delegate)
        val crash = IllegalStateException("boom")
        val thread = Thread.currentThread()

        handler.uncaughtException(thread, crash)

        assertEquals(1, delegate.calls.size)
        assertSame(thread, delegate.calls[0].first)
        assertSame(crash, delegate.calls[0].second)
    }

    @Test
    fun `records before delegating - the delegate kills the process`() {
        val order = mutableListOf<String>()
        val recorder = object : CrashRecorder {
            override fun record(thread: Thread, throwable: Throwable) { order += "record" }
        }
        val delegate = Thread.UncaughtExceptionHandler { _, _ -> order += "delegate" }

        OpenDroidCrashHandler(recorder, delegate)
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(listOf("record", "delegate"), order)
    }

    @Test
    fun `still delegates when the recorder throws`() {
        val delegate = RecordingDelegate()
        val recorder = RecordingRecorder(failWith = IllegalStateException("recorder is broken"))

        OpenDroidCrashHandler(recorder, delegate)
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, delegate.calls.size)
    }

    @Test
    fun `a throwing recorder does not propagate`() {
        val recorder = RecordingRecorder(failWith = OutOfMemoryError("no heap"))

        OpenDroidCrashHandler(recorder, RecordingDelegate())
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))
    }

    @Test
    fun `tolerates a missing delegate`() {
        val recorder = RecordingRecorder()

        OpenDroidCrashHandler(recorder, null)
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, recorder.calls.size)
    }

    @Test
    fun `a crash raised while handling a crash is not recorded twice`() {
        // A re-entrant crash (the delegate itself failing) must not loop back
        // into recording - that is how a crash handler hangs a dying process.
        val recorder = RecordingRecorder()
        lateinit var handler: OpenDroidCrashHandler
        var reentered = false
        val reentrantDelegate = Thread.UncaughtExceptionHandler { t, _ ->
            if (!reentered) {
                reentered = true
                handler.uncaughtException(t, RuntimeException("secondary"))
            }
        }
        handler = OpenDroidCrashHandler(recorder, reentrantDelegate)

        handler.uncaughtException(Thread.currentThread(), RuntimeException("primary"))

        assertEquals(1, recorder.calls.size)
        assertEquals("primary", recorder.calls[0].second.message)
    }

    @Test
    fun `install returns a handler wrapping the previous default`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val delegate = RecordingDelegate()
            Thread.setDefaultUncaughtExceptionHandler(delegate)
            val recorder = RecordingRecorder()

            OpenDroidCrashHandler.install(recorder)

            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertTrue(installed is OpenDroidCrashHandler)
            installed!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
            assertEquals(1, recorder.calls.size)
            assertEquals(1, delegate.calls.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `install is idempotent - it never wraps itself`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val delegate = RecordingDelegate()
            Thread.setDefaultUncaughtExceptionHandler(delegate)
            val recorder = RecordingRecorder()

            OpenDroidCrashHandler.install(recorder)
            OpenDroidCrashHandler.install(recorder)

            val installed = Thread.getDefaultUncaughtExceptionHandler() as OpenDroidCrashHandler
            installed.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

            // Wrapping itself would record and delegate twice.
            assertEquals(1, recorder.calls.size)
            assertEquals(1, delegate.calls.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
