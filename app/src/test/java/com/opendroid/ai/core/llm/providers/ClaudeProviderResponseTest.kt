package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.ClaudeModelCatalog
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.ChatMessage
import java.net.ConnectException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the Messages API bodies that used to crash the
 * provider with a raw Gson exception, and for `streamComplete` disguising those
 * crashes as network failures.
 */
class ClaudeProviderResponseTest {

    private val transport = CannedTransport()
    private val provider = ClaudeProvider(transport.client, CannedTransport.newSettingsRepository())

    private val apiKey = "sk-ant-test-0123456789abcdef"

    @Test
    fun `a leading thinking block does not hide the text reply`() = runBlocking {
        transport.enqueue(
            """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"pong"}],
             "usage":{"input_tokens":10,"output_tokens":5}}
            """.trimIndent()
        )

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(15, response.tokensUsed)
        assertEquals(ClaudeModelCatalog.defaultModelId, response.model)
        assertEquals("Anthropic Claude", response.provider)
        assertEquals(apiKey, transport.recordedRequests.single().header("x-api-key"))
    }

    @Test
    fun `a null usage block does not fail a good completion`() = runBlocking {
        transport.enqueue("""{"content":[{"type":"text","text":"pong"}],"usage":null}""")

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `bodies without a text block are malformed not unknown`() {
        val bodies = listOf(
            """{"content":[{"type":"tool_use","id":"t1","name":"open_app","input":{}}]}""",
            """{"content":null,"usage":{"input_tokens":1,"output_tokens":1}}""",
            """{"id":"msg_1","usage":{"input_tokens":1,"output_tokens":1}}""",
            """{"content":[],"usage":null}""",
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

    @Test
    fun `streaming reports a non-JSON 200 as malformed rather than network`() {
        transport.enqueue("<html><body>Service busy</body></html>")

        val failure = assertThrows(LLMException::class.java) {
            runBlocking { provider.streamComplete(newRequest()).toList() }
        }

        assertEquals(LLMError.MalformedResponse, failure.error)
        assertFalse(failure.retryable)
    }

    @Test
    fun `streaming reports an empty 200 as malformed rather than network`() {
        transport.enqueue("")

        val failure = assertThrows(LLMException::class.java) {
            runBlocking { provider.streamComplete(newRequest()).toList() }
        }

        assertEquals(LLMError.MalformedResponse, failure.error)
    }

    @Test
    fun `streaming passes a transport failure through unwrapped`() {
        transport.enqueueFailure(ConnectException("Connection refused"))

        val failure = assertThrows(ConnectException::class.java) {
            runBlocking { provider.streamComplete(newRequest()).toList() }
        }

        assertTrue(failure.message.orEmpty().contains("refused"))
    }

    @Test
    fun `streaming reconstructs the text reply`() = runBlocking {
        transport.enqueue("""{"content":[{"type":"text","text":"hello world"}]}""")

        val streamed = provider.streamComplete(newRequest()).toList().joinToString("")

        assertEquals("hello world", streamed.trimEnd())
    }

    private fun newRequest() = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        model = null,
        providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = "")
    )
}
