package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the typed outcome of every 2xx body shape that used to escape as a raw
 * Gson exception (and therefore as a non-retryable `Unknown` error).
 */
class CloudResponseParsersTest {

    // ---- OpenAI-compatible chat completions -------------------------------

    @Test
    fun `openai reads content and total tokens`() {
        val parsed = CloudResponseParsers.openAiChatCompletion(
            """{"choices":[{"message":{"content":"pong"}}],"usage":{"total_tokens":42}}""",
            PROVIDER,
            MODEL
        )

        assertEquals(ParsedCompletion("pong", 42), parsed)
    }

    @Test
    fun `openai surfaces a refusal as the reply`() {
        val parsed = CloudResponseParsers.openAiChatCompletion(
            """{"choices":[{"message":{"content":null,"refusal":"I can't help with that."}}]}""",
            PROVIDER,
            MODEL
        )

        assertEquals("I can't help with that.", parsed.content)
    }

    @Test
    fun `openai never fails a completion over the usage block`() {
        val bodies = mapOf(
            """{"choices":[{"message":{"content":"pong"}}],"usage":null}""" to 0,
            """{"choices":[{"message":{"content":"pong"}}],"usage":{"total_tokens":null}}""" to 0,
            """{"choices":[{"message":{"content":"pong"}}],"usage":{"total_tokens":"many"}}""" to 0,
            """{"choices":[{"message":{"content":"pong"}}]}""" to 0,
            """{"choices":[{"message":{"content":"pong"}}],"usage":{"prompt_tokens":3,"completion_tokens":4}}""" to 7
        )

        bodies.forEach { (body, expectedTokens) ->
            val parsed = CloudResponseParsers.openAiChatCompletion(body, PROVIDER, MODEL)
            assertEquals(body, "pong", parsed.content)
            assertEquals(body, expectedTokens, parsed.tokensUsed)
        }
    }

    @Test
    fun `openai bodies without reply text are malformed not unknown`() {
        val bodies = listOf(
            // Tool-call turn: no content, no refusal.
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"c1"}]}}]}""",
            """{"choices":[{"message":{"role":"assistant"}}]}""",
            """{"choices":[{"message":{"content":null,"refusal":null}}]}""",
            // OpenRouter reports upstream failures as HTTP 200 with an error object.
            """{"error":{"code":502,"message":"upstream failed"}}""",
            """{"choices":[{"index":0}]}""",
            """{"choices":[]}""",
            """{"choices":null}""",
            """{"choices":"nope"}""",
            """[]""",
            "\"just a string\"",
            """not json at all""",
            """   """
        )

        assertAllMalformed(bodies) { body ->
            CloudResponseParsers.openAiChatCompletion(body, PROVIDER, MODEL)
        }
    }

    // ---- Anthropic messages -------------------------------------------------

    @Test
    fun `claude joins the text blocks and skips thinking and tool_use`() {
        val parsed = CloudResponseParsers.claudeMessage(
            """
            {"content":[
               {"type":"thinking","thinking":"private"},
               {"type":"text","text":"Hello, "},
               {"type":"tool_use","id":"t1","name":"open_app","input":{}},
               {"type":"text","text":"world."}
             ],
             "usage":{"input_tokens":10,"output_tokens":5}}
            """.trimIndent(),
            PROVIDER,
            MODEL
        )

        assertEquals(ParsedCompletion("Hello, world.", 15), parsed)
    }

    @Test
    fun `claude usage null or missing means zero tokens`() {
        listOf(
            """{"content":[{"type":"text","text":"pong"}],"usage":null}""",
            """{"content":[{"type":"text","text":"pong"}]}""",
            """{"content":[{"type":"text","text":"pong"}],"usage":{"input_tokens":null,"output_tokens":"x"}}"""
        ).forEach { body ->
            assertEquals(body, ParsedCompletion("pong", 0), CloudResponseParsers.claudeMessage(body, PROVIDER, MODEL))
        }
    }

    @Test
    fun `claude bodies without a text block are malformed`() {
        val bodies = listOf(
            """{"content":[{"type":"tool_use","id":"t1","name":"open_app","input":{}}]}""",
            """{"content":[]}""",
            """{"content":null}""",
            """{"id":"msg_1","usage":{"input_tokens":1,"output_tokens":1}}""",
            """{"content":"pong"}""",
            """{"content":[{"type":"text","text":null}]}""",
            """<html>busy</html>"""
        )

        assertAllMalformed(bodies) { body ->
            CloudResponseParsers.claudeMessage(body, PROVIDER, MODEL)
        }
    }

    // ---- Cohere v2 chat ------------------------------------------------------

    @Test
    fun `cohere sums input and output tokens`() {
        val parsed = CloudResponseParsers.cohereChat(
            """
            {"message":{"role":"assistant","content":[{"type":"text","text":"pong"}]},
             "usage":{"billed_units":{"input_tokens":1,"output_tokens":1},
                      "tokens":{"input_tokens":12,"output_tokens":30}}}
            """.trimIndent(),
            PROVIDER,
            MODEL
        )

        assertEquals(ParsedCompletion("pong", 42), parsed)
    }

    @Test
    fun `cohere usage without tokens means zero`() {
        listOf(
            """{"message":{"content":[{"type":"text","text":"pong"}]},"usage":{"tokens":null}}""",
            """{"message":{"content":[{"type":"text","text":"pong"}]},"usage":null}""",
            """{"message":{"content":[{"type":"text","text":"pong"}]}}"""
        ).forEach { body ->
            assertEquals(body, ParsedCompletion("pong", 0), CloudResponseParsers.cohereChat(body, PROVIDER, MODEL))
        }
    }

    @Test
    fun `cohere bodies without a text block are malformed`() {
        val bodies = listOf(
            // Tool-call turn.
            """{"message":{"role":"assistant","content":[],"tool_calls":[{"id":"c1"}]}}""",
            """{"message":{"role":"assistant"}}""",
            """{"message":null}""",
            """{"text":"legacy v1 shape"}""",
            """{"message":{"content":[{"type":"tool_plan","text":"planning"}]}}"""
        )

        assertAllMalformed(bodies) { body ->
            CloudResponseParsers.cohereChat(body, PROVIDER, MODEL)
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun assertAllMalformed(bodies: List<String>, parse: (String) -> ParsedCompletion) {
        bodies.forEach { body ->
            val failure = assertThrows(body, LLMException::class.java) { parse(body) }
            assertEquals(body, LLMError.MalformedResponse, failure.error)
            assertFalse(body, failure.retryable)
        }
    }

    private companion object {
        const val PROVIDER = "OpenAI"
        const val MODEL = "gpt-4o"
    }
}
