package com.opendroid.ai.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogRedactorTest {

    private fun assertRedacted(secret: String, text: String) {
        val result = CrashLogRedactor.redact(text)
        assertFalse("secret survived redaction: $result", result.contains(secret))
        assertTrue("nothing was redacted: $result", result.contains(CrashLogRedactor.REDACTED))
    }

    // --- The exposures issue #40 confirmed ------------------------------------

    @Test
    fun `redacts the gemini key carried in the request url`() {
        assertRedacted(
            "AIzaSyD-1234567890abcdefghijklmnop",
            "java.io.IOException: GET https://generativelanguage.googleapis.com/v1beta/" +
                "models/gemini-pro:generateContent?key=AIzaSyD-1234567890abcdefghijklmnop failed"
        )
    }

    @Test
    fun `keeps the url query parameter name so the message still reads`() {
        val result = CrashLogRedactor.redact("https://example.com/v1?key=AIzaSyD-1234567890abcdefg&alt=sse")
        assertEquals("https://example.com/v1?key=${CrashLogRedactor.REDACTED}&alt=sse", result)
    }

    @Test
    fun `redacts the server-masked key openai echoes back in a 401 body`() {
        // Confirmed in #40: OpenAI's 401 body echoes the real first 8 and last 4
        // characters of the submitted key. Still key material.
        assertRedacted(
            "sk-inval**********6789",
            """java.io.IOException: OpenAI request failed: Code 401 - {"error":{"message":""" +
                """"Incorrect API key provided: sk-inval**********6789."}}"""
        )
    }

    @Test
    fun `redacts a whole openai style key`() {
        assertRedacted(
            "sk-proj-AbCdEf1234567890XyZ",
            "IOException: request failed with key sk-proj-AbCdEf1234567890XyZ"
        )
    }

    @Test
    fun `redacts an anthropic style key`() {
        assertRedacted("sk-ant-api03-Ab1Cd2Ef3", "auth failed for sk-ant-api03-Ab1Cd2Ef3")
    }

    @Test
    fun `redacts a groq style key`() {
        assertRedacted("gsk_AbCdEf1234567890", "Groq request failed: gsk_AbCdEf1234567890 rejected")
    }

    @Test
    fun `redacts an xai style key`() {
        assertRedacted("xai-AbCdEf1234567890", "xai-AbCdEf1234567890 is not valid")
    }

    // --- Header and body shapes ----------------------------------------------

    @Test
    fun `redacts a bearer token`() {
        val result = CrashLogRedactor.redact("Authorization: Bearer abcdef123456.token")
        assertFalse(result.contains("abcdef123456.token"))
        assertTrue(result.contains("Bearer ${CrashLogRedactor.REDACTED}"))
    }

    @Test
    fun `redacts an x-goog-api-key header`() {
        assertRedacted("AIzaTotallyRealKey123", "x-goog-api-key: AIzaTotallyRealKey123")
    }

    @Test
    fun `redacts an api key field in an echoed json body`() {
        assertRedacted(
            "abcdef1234567890",
            """Cohere request failed: Code 401 - {"api_key":"abcdef1234567890","message":"invalid"}"""
        )
    }

    // --- Must not shred ordinary crash text -----------------------------------

    @Test
    fun `leaves an ordinary stack frame alone`() {
        val frame = "\tat com.opendroid.ai.core.llm.providers.OpenAIProvider.execute(OpenAIProvider.kt:65)"
        assertEquals(frame, CrashLogRedactor.redact(frame))
    }

    @Test
    fun `leaves an ordinary exception message alone`() {
        val message = "java.lang.IllegalStateException: Model file at '/data/models/gemma.task' is empty"
        assertEquals(message, CrashLogRedactor.redact(message))
    }

    @Test
    fun `leaves a url without credentials alone`() {
        val url = "https://api.openai.com/v1/chat/completions?stream=true&model=gpt-4o"
        assertEquals(url, CrashLogRedactor.redact(url))
    }

    @Test
    fun `is a no-op on empty text`() {
        assertEquals("", CrashLogRedactor.redact(""))
    }

    // --- Applied at capture time, not at share time ---------------------------

    @Test
    fun `formatter redacts the exception message`() {
        val message = CrashReportFormatter.messageOf(
            IllegalStateException("OpenAI request failed: Code 401 - sk-proj-Ab1Cd2Ef3Gh4Ij5")
        )
        assertFalse(message!!.contains("sk-proj-Ab1Cd2Ef3Gh4Ij5"))
        assertTrue(message.contains(CrashLogRedactor.REDACTED))
    }

    @Test
    fun `formatter redacts the stack trace`() {
        val trace = CrashReportFormatter.stackTraceOf(
            IllegalStateException("failed for key=AIzaSyD-1234567890abcdefg")
        )
        assertFalse(trace.contains("AIzaSyD-1234567890abcdefg"))
        assertTrue(trace.contains(CrashLogRedactor.REDACTED))
    }

    @Test
    fun `redaction runs before truncation so a secret cannot be split across the cut`() {
        // The secret straddles the truncation boundary. Redacting after
        // truncating would leave its head in the stored text.
        val padding = "x".repeat(40)
        val trace = CrashReportFormatter.stackTraceOf(
            IllegalStateException("$padding key=AIzaSyD-1234567890abcdefghij"),
            maxChars = 60
        )
        assertFalse(trace.contains("AIzaSyD"))
    }

    @Test
    fun `recorded crashes reach the sink already redacted`() {
        val recorded = mutableListOf<CrashLogRecord>()
        val sink = object : CrashLogSink {
            override fun record(record: CrashLogRecord) { recorded += record }
            override fun prune(keepMostRecent: Int) = Unit
        }
        val recorder = CrashLogRecorder(
            sink = sink,
            metadata = DeviceMetadata("1.0", 1L, "14", 34, "Google", "Pixel")
        )

        recorder.record(
            Thread.currentThread(),
            IllegalStateException("Gemini request failed: ?key=AIzaSyD-1234567890abcdefg")
        )

        val stored = recorded.single()
        assertFalse(stored.message!!.contains("AIzaSyD-1234567890abcdefg"))
        assertFalse(stored.stackTrace.contains("AIzaSyD-1234567890abcdefg"))
    }
}
