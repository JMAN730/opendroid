package com.opendroid.ai.core.crash

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Turns a [Throwable] into the plain strings that get stored in the crash log.
 *
 * Deliberately free of Android APIs so it stays unit testable, and free of any
 * allocation-heavy work beyond the stack trace itself - this runs on a thread
 * that is already on its way down.
 */
object CrashReportFormatter {

    /**
     * Stack traces are stored in SQLite and rendered in a Compose list, so an
     * unbounded trace (deep recursion produces megabytes) is a real hazard.
     */
    const val MAX_STACK_TRACE_CHARS = 16_000

    /**
     * Internal rather than private so tests can assert on the marker itself.
     * Matching a bare word like "truncated" gives false positives: a stack
     * trace captured inside a backtick-named test contains that test's own
     * name as a frame.
     */
    internal const val TRUNCATION_MARKER = "\n... [stack trace truncated]"

    /**
     * Redaction happens before truncation, not after: truncating first could cut
     * a secret in half and leave the prefix - which is still key material - on
     * the wrong side of a pattern that no longer matches.
     */
    fun stackTraceOf(throwable: Throwable, maxChars: Int = MAX_STACK_TRACE_CHARS): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return truncate(CrashLogRedactor.redact(writer.toString()), maxChars)
    }

    /**
     * Keeps the *head* of [text]. The top frames name the code that actually
     * failed; the tail is framework plumbing that is the same for every crash.
     */
    fun truncate(text: String, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive, was $maxChars" }
        if (text.length <= maxChars) return text
        return text.take(maxChars) + TRUNCATION_MARKER
    }

    fun exceptionClassOf(throwable: Throwable): String = throwable.javaClass.name

    /**
     * Single-line message, or null if the throwable carries nothing useful.
     *
     * Redacted, because several LLM providers build their failure messages
     * straight out of the response body - see [CrashLogRedactor].
     */
    fun messageOf(throwable: Throwable): String? =
        throwable.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { CrashLogRedactor.redact(it) }
}
