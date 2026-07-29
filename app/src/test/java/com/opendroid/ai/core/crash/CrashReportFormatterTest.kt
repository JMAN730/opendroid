package com.opendroid.ai.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatterTest {

    @Test
    fun `stack trace contains the exception type and message`() {
        val trace = CrashReportFormatter.stackTraceOf(IllegalStateException("boom"))

        assertTrue(trace, trace.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `stack trace contains the frame the exception was thrown from`() {
        val trace = CrashReportFormatter.stackTraceOf(RuntimeException("nope"))

        assertTrue(trace, trace.contains("CrashReportFormatterTest"))
    }

    @Test
    fun `stack trace includes the whole cause chain`() {
        val root = IllegalArgumentException("root cause")
        val middle = IllegalStateException("middle", root)
        val top = RuntimeException("top", middle)

        val trace = CrashReportFormatter.stackTraceOf(top)

        assertTrue(trace, trace.contains("top"))
        assertTrue(trace, trace.contains("Caused by"))
        assertTrue(trace, trace.contains("middle"))
        assertTrue(trace, trace.contains("root cause"))
    }

    @Test
    fun `short stack traces are not truncated`() {
        val trace = CrashReportFormatter.stackTraceOf(RuntimeException("small"))

        // Assert on the marker, not on the word "truncated": this method's own
        // name is a frame in the trace it just captured.
        assertTrue(trace, !trace.contains(CrashReportFormatter.TRUNCATION_MARKER))
    }

    @Test
    fun `oversized stack traces are truncated and marked`() {
        val trace = CrashReportFormatter.stackTraceOf(RuntimeException("x"), maxChars = 40)

        assertTrue(trace, trace.endsWith(CrashReportFormatter.TRUNCATION_MARKER))
        // The head is kept - the top frames are where the crash happened.
        assertTrue(trace, trace.startsWith("java.lang.RuntimeException: x"))
    }

    @Test
    fun `truncation keeps the head and appends the marker`() {
        val truncated = CrashReportFormatter.truncate("abcdefghij", maxChars = 4)

        assertTrue(truncated, truncated.startsWith("abcd"))
        assertTrue(truncated, truncated.endsWith(CrashReportFormatter.TRUNCATION_MARKER))
    }

    @Test
    fun `text at exactly the limit is left alone`() {
        assertEquals("abcd", CrashReportFormatter.truncate("abcd", maxChars = 4))
    }

    @Test
    fun `exception class is the fully qualified name`() {
        assertEquals(
            "java.lang.IllegalStateException",
            CrashReportFormatter.exceptionClassOf(IllegalStateException("boom"))
        )
    }

    @Test
    fun `message is null when the throwable has none`() {
        assertNull(CrashReportFormatter.messageOf(RuntimeException()))
    }

    @Test
    fun `message is null when the throwable message is blank`() {
        assertNull(CrashReportFormatter.messageOf(RuntimeException("   ")))
    }

    @Test
    fun `message is flattened to a single line`() {
        val message = CrashReportFormatter.messageOf(RuntimeException("line one\nline two"))

        assertEquals("line one line two", message)
    }

}
