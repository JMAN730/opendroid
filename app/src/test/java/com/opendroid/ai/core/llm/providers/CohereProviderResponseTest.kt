package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression coverage for the Cohere v2 `chat` bodies that used to crash the
 * provider with a raw Gson exception, and for the token count that v2 reports
 * as `input_tokens`/`output_tokens` rather than a total.
 */
class CohereProviderResponseTest {

    private val transport = CannedTransport()
    private val provider = CohereProvider(transport.client, CannedTransport.newSettingsRepository())

    private val apiKey = "co-test-0123456789abcdef"

    @Test
    fun `tokens are the sum of v2 input and output counts`() = runBlocking {
        transport.enqueue(
            """
            {"id":"r1","finish_reason":"COMPLETE",
             "message":{"role":"assistant","content":[{"type":"text","text":"pong"}]},
             "usage":{"billed_units":{"input_tokens":1,"output_tokens":1},
                      "tokens":{"input_tokens":12,"output_tokens":30}}}
            """.trimIndent()
        )

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
        assertEquals("command-r-plus", response.model)
        assertEquals("Cohere", response.provider)
        assertEquals("Bearer $apiKey", transport.recordedRequests.single().header("Authorization"))
    }

    @Test
    fun `a null tokens block does not fail a good completion`() = runBlocking {
        transport.enqueue(
            """{"message":{"content":[{"type":"text","text":"pong"}]},"usage":{"tokens":null}}"""
        )

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `bodies without a text block are malformed not unknown`() {
        val bodies = listOf(
            // Tool-call turn: empty content list.
            """{"message":{"role":"assistant","content":[],"tool_calls":[{"id":"c1"}]}}""",
            """{"message":{"role":"assistant"}}""",
            """{"message":null}""",
            """{"text":"legacy v1 shape"}""",
            ""
        )

        bodies.forEach { body ->
            transport.enqueue(body)
            val failure = assertThrows(LLMException::class.java) {
                runBlocking { provider.complete(newRequest()) }
            }
            assertEquals(body, LLMError.MalformedResponse, failure.error)
        }
    }

    private fun newRequest() = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        model = null,
        providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = "")
    )
}
