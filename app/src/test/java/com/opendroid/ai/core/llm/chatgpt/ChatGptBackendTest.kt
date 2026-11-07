package com.opendroid.ai.core.llm.chatgpt

import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.llm.providers.ChatGptProvider
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptBackendTest {

    private val codex = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val auth = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val now = 50_000_000L
    private val store = InMemoryCredentialStore()
    private val repository = ChatGptAuthRepository(
        store = store,
        oauth = ChatGptOAuthClient(AppModule.provideOkHttpClient(), auth.url("").toString().trimEnd('/')) { now },
        nowMillis = { now },
        delayMillis = {}
    )
    private val backend = ChatGptBackend(
        AppModule.provideOkHttpClient(),
        repository,
        codex.url("/backend-api/codex").toString()
    )
    private val provider = ChatGptProvider(backend, repository)
    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        codex.close()
        auth.close()
    }

    @Test
    fun `a completion streams the reply with the Codex headers and an unstored streamed body`() = runBlocking {
        signIn()
        codex.enqueue(
            sse(
                """{"type":"response.created","response":{}}""",
                """{"type":"response.output_text.delta","delta":"po"}""",
                """{"type":"response.output_text.delta","delta":"ng"}""",
                """{"type":"response.completed","response":{"usage":{"total_tokens":42}}}""",
                "[DONE]"
            )
        )

        val response = provider.complete(request())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
        assertEquals("gpt-5.4", response.model)
        assertEquals("ChatGPT", response.provider)

        val recorded = codex.takeRequest()
        assertEquals("/backend-api/codex/responses", recorded.target)
        assertEquals("Bearer access-1", recorded.headers["Authorization"])
        assertEquals("acct-1", recorded.headers["chatgpt-account-id"])
        assertEquals("codex_cli_rs", recorded.headers["originator"])
        assertEquals("responses=experimental", recorded.headers["OpenAI-Beta"])

        val body = JsonParser.parseString(recorded.body?.utf8()).asJsonObject
        assertEquals("gpt-5.4", body["model"].asString)
        assertEquals("be brief", body["instructions"].asString)
        assertTrue(body["stream"].asBoolean)
        assertFalse(body["store"].asBoolean)
        val input = body["input"].asJsonArray
        assertEquals(2, input.size())
        assertEquals("user", input[0].asJsonObject["role"].asString)
        val userContent = input[0].asJsonObject["content"].asJsonArray[0].asJsonObject
        assertEquals("input_text", userContent["type"].asString)
        assertEquals(prompt, userContent["text"].asString)
        val assistantContent = input[1].asJsonObject["content"].asJsonArray[0].asJsonObject
        assertEquals("assistant", input[1].asJsonObject["role"].asString)
        assertEquals("output_text", assistantContent["type"].asString)
    }

    @Test
    fun `streamComplete emits each delta as it arrives`() = runBlocking {
        signIn()
        codex.enqueue(
            sse(
                """{"type":"response.output_text.delta","delta":"Hello"}""",
                """{"type":"response.output_text.delta","delta":" world"}"""
            )
        )

        assertEquals(listOf("Hello", " world"), provider.streamComplete(request()).toList())
    }

    @Test
    fun `an image is sent as an input image`() = runBlocking {
        signIn()
        codex.enqueue(sse("""{"type":"response.output_text.delta","delta":"ok"}"""))

        provider.complete(
            request().copy(
                messages = listOf(ChatMessage("1", "what is this", ChatMessage.Sender.USER, imageBase64 = "AAAA"))
            )
        )

        val content = JsonParser.parseString(codex.takeRequest().body?.utf8()).asJsonObject["input"]
            .asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals("input_image", content[1].asJsonObject["type"].asString)
        assertEquals("data:image/jpeg;base64,AAAA", content[1].asJsonObject["image_url"].asString)
    }

    @Test
    fun `a 401 refreshes the session once and retries with the new token`() = runBlocking {
        signIn()
        codex.enqueue(MockResponse.Builder().code(401).build())
        auth.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}""")
                .build()
        )
        codex.enqueue(sse("""{"type":"response.output_text.delta","delta":"pong"}"""))

        assertEquals("pong", provider.complete(request()).content)

        assertEquals("Bearer access-1", codex.takeRequest().headers["Authorization"])
        assertEquals("Bearer access-2", codex.takeRequest().headers["Authorization"])
        assertEquals("refresh-2", stored()?.refresh)
    }

    @Test
    fun `an in-stream failure before any text is a typed error without the provider message`() = runBlocking {
        signIn()
        codex.enqueue(
            sse("""{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"secret detail"}}}""")
        )

        val failure = runCatching { provider.complete(request()) }.exceptionOrNull()

        assertTrue("got $failure", failure is LLMException)
        assertEquals(LLMError.RateLimited, (failure as LLMException).error)
        assertFalse(failure.message.orEmpty().contains("secret detail"))
    }

    @Test
    fun `text that arrived before an in-stream failure is still returned`() = runBlocking {
        signIn()
        codex.enqueue(
            sse(
                """{"type":"response.output_text.delta","delta":"partial"}""",
                """{"type":"error","message":"boom"}"""
            )
        )

        assertEquals("partial", provider.complete(request()).content)
    }

    @Test
    fun `an empty stream is a malformed response`() = runBlocking {
        signIn()
        codex.enqueue(sse("""{"type":"response.completed","response":{}}"""))

        val failure = runCatching { provider.complete(request()) }.exceptionOrNull() as LLMException

        assertEquals(LLMError.MalformedResponse, failure.error)
    }

    @Test
    fun `an HTTP failure is classified without leaking tokens, prompt, or body`() = runBlocking {
        signIn()
        codex.enqueue(
            MockResponse.Builder().code(429)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":{"type":"usage_limit_reached","message":"limit for access-1 $prompt"}}""")
                .build()
        )

        val failure = runCatching { provider.complete(request()) }.exceptionOrNull() as LLMException

        assertEquals(LLMError.RateLimited, failure.error)
        assertEquals(429, failure.status)
        val rendered = "${failure.message} ${failure.detail}"
        assertFalse(rendered.contains("access-1"))
        assertFalse(rendered.contains(prompt))
        assertFalse(rendered.contains(codex.hostName))
    }

    @Test
    fun `without a sign-in nothing is sent`() = runBlocking {
        val failure = runCatching { provider.complete(request()) }.exceptionOrNull() as LLMException

        assertEquals(LLMError.AuthMissing, failure.error)
        assertEquals(0, codex.requestCount)
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `the model list is read from the account and the preferred model is recommended`() = runBlocking {
        signIn()
        codex.enqueue(
            MockResponse.Builder().code(200).body(
                """{"models":[{"slug":"gpt-5.5","display_name":"GPT-5.5"},{"slug":"gpt-5.4"},{"slug":"gpt-5.5"}]}"""
            ).build()
        )

        val models = backend.listModels()

        assertEquals(listOf("gpt-5.5", "gpt-5.4"), models.map { it.id })
        assertEquals("GPT-5.5", models[0].displayName)
        assertEquals(listOf(false, true), models.map { it.isRecommended })
        val recorded = codex.takeRequest()
        assertEquals("/backend-api/codex/models?client_version=1.0.0", recorded.target)
        assertEquals("Bearer access-1", recorded.headers["Authorization"])
    }

    @Test
    fun `a session with no account id sends no account header`() = runBlocking {
        signIn(accountId = null)
        codex.enqueue(sse("""{"type":"response.output_text.delta","delta":"ok"}"""))

        provider.complete(request())

        assertNull(codex.takeRequest().headers["chatgpt-account-id"])
    }

    private fun signIn(accountId: String? = "acct-1") {
        store.values[ProviderCredentialId.ChatGptSession] = Json.encodeToString(
            ChatGptSession.serializer(),
            ChatGptSession("access-1", "refresh-1", now + 3_600_000, accountId, "plus")
        )
    }

    private fun stored(): ChatGptSession? = store.values[ProviderCredentialId.ChatGptSession]
        ?.let { Json.decodeFromString(ChatGptSession.serializer(), it) }

    private fun request() = LLMRequest(
        systemPrompt = "be brief",
        messages = listOf(
            ChatMessage("1", prompt, ChatMessage.Sender.USER),
            ChatMessage("2", "earlier reply", ChatMessage.Sender.AGENT)
        ),
        responseFormat = ResponseFormat.TEXT
    )

    private fun sse(vararg events: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { "data: $it\n\n" })
        .build()
}
