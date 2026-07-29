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
     * Guards against re-entry. If anything downstream of the first crash throws
     * again, recording a second time risks blocking on a database that is
     * already known to be unhappy; the delegate still runs either way.
     */
    private val handling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (handling.compareAndSet(false, true)) {
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
