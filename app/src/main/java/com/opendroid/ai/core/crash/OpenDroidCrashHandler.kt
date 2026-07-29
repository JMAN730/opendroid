package com.opendroid.ai.core.crash

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uncaught exception handler that persists the crash and then lets the system
 * handler do its normal job of killing the process.
 *
 * Not delegating would leave the app in a zombie state - a live process with a
 * dead main thread - which is worse than the crash it is logging.
 */
class OpenDroidCrashHandler(
    private val recorder: CrashRecorder,
    private val delegate: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    /**
     * One-shot latch: only the *first* uncaught throwable in the process is
     * ever recorded, and it is never reset.
     *
     * That is deliberate rather than a re-entry guard proper. The delegate is
     * the system handler, which kills the process, so a second uncaught
     * throwable only arrives when something is already badly wrong - either a
     * re-entrant crash on this thread or a simultaneous one on another. In both
     * cases the first crash is the diagnosable one, and a second write would be
     * blocking on a database that has just been shown to be unreliable.
     *
     * The delegate still runs for every throwable, recorded or not.
     */
    private val recordedFirstCrash = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (recordedFirstCrash.compareAndSet(false, true)) {
                recorder.record(thread, throwable)
            }
        } catch (t: Throwable) {
            // A CrashRecorder is not supposed to throw, but this is the last
            // line of defence before the process dies - take no chances.
        } finally {
            delegate?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /**
         * Installs the handler in front of whatever is currently registered.
         * Idempotent: calling it twice does not chain two handlers together.
         */
        fun install(recorder: CrashRecorder) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is OpenDroidCrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(OpenDroidCrashHandler(recorder, current))
        }
    }
}
