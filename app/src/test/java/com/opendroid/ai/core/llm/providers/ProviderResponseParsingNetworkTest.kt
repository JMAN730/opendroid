package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Runs the real providers against a socket with the 200 responses that used to
 * crash them. A response without usable text must be a typed malformed failure,
 * never `Unknown` or `Network`, and a bad token count must not cost a good reply.
 *
 * These providers hard-code their hosts, so the client reroutes each request to
 * the loopback server without changing its path.
 */
class ProviderResponseParsingNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val loopback = server.url("/")
    private val client: OkHttpClient = AppModule.provideOkHttpClient().newBuilder()
        .addInterceptor(
            Interceptor { chain ->
                val routed = chain.request().url.newBuilder()
                    .scheme(loopback.scheme)
                    .host(loopback.host)
                    .port(loopback.port)
                    .build()
                chain.proceed(chain.request().newBuilder().url(routed).build())
            }
        )
        .build()
    private val settings = newSettingsRepository()

    private val openAiFamily: List<LLMProvider> = listOf(
        OpenAIProvider(client, settings),
        GroqProvider(client, settings),
        MistralProvider(client, settings),
        TogetherAIProvider(client, settings),
        DeepSeekProvider(client, settings),
        OpenRouterProvider(client, settings),
        CustomOpenAIProvider(client, settings)
    )

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `openai family reports a completion without text as malformed`() {
        val bodies = listOf(
            """{"choices":[{"message":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"1"}]}}]}""",
            """{"choices":[{"message":{"content":null,"refusal":null}}]}""",
            """{"choices":[{"message":{"content":["part"]}}]}""",
            """{"choices":[{"finish_reason":"stop"}]}""",
            """{"choices":[{"message":null}]}""",
            """{"choices":[]}""",
            """{"choices":null}""",
            """{"error":{"message":"Upstream rate limit","code":429}}""",
            "not json at all",
            "null"
        )
        openAiFamily.forEach { provider ->
            bodies.forEach { body ->
                assertMalformed("${provider.name}: $body", provider, body, familyRequest())
            }
        }
    }

    @Test
    fun `openai family keeps a good completion when usage is unusable`() = runBlocking {
        val usages = listOf(
            """"usage":null""",
            """"usage":{"total_tokens":null}""",
            """"usage":"unknown"""",
            """"usage":{"total_tokens":"many"}""",
            """"usage":{"total_tokens":1e99999999999}"""
        )
        openAiFamily.forEach { provider ->
            usages.forEach { usage ->
                server.enqueue(okResponse("""{"choices":[{"message":{"content":"pong"}}],$usage}"""))

                val response = provider.complete(familyRequest())

                assertEquals("${provider.name}: $usage", "pong", response.content)
                assertEquals("${provider.name}: $usage", 0, response.tokensUsed)
            }
        }
    }

    @Test
    fun `openai family still reads content and total tokens`() = runBlocking {
        openAiFamily.forEach { provider ->
            server.enqueue(
                okResponse(
                    """{"choices":[{"message":{"content":"pong"}}],"usage":{"total_tokens":42}}"""
                )
            )

            val response = provider.complete(familyRequest())

            assertEquals(provider.name, "pong", response.content)
            assertEquals(provider.name, 42, response.tokensUsed)
        }
    }

    @Test
    fun `openai family surfaces a refusal as the reply`() = runBlocking {
        openAiFamily.forEach { provider ->
            server.enqueue(
                okResponse(
                    """{"choices":[{"message":{"content":null,"refusal":"I can't help with that."}}]}"""
                )
            )

            val response = provider.complete(familyRequest())

            assertEquals(provider.name, "I can't help with that.", response.content)
        }
    }

    @Test
    fun `claude joins text blocks and skips the other block types`() = runBlocking {
        val provider = ClaudeProvider(client, settings)
        server.enqueue(
            okResponse(
                """
                {"content":[
                  {"type":"thinking","thinking":"hmm","signature":"s"},
                  {"type":"text","text":"Hello "},
                  {"type":"tool_use","id":"1","name":"t","input":{}},
                  {"type":"text","text":"world"}
                ],"usage":{"input_tokens":30,"output_tokens":12}}
                """.trimIndent()
            )
        )

        val response = provider.complete(claudeRequest())

        assertEquals("Hello world", response.content)
        assertEquals(42, response.tokensUsed)
    }

    @Test
    fun `claude reports a message without text as malformed`() {
        val provider = ClaudeProvider(client, settings)
        listOf(
            """{"content":[{"type":"thinking","thinking":"hmm"}]}""",
            """{"content":[{"type":"tool_use","id":"1","name":"t","input":{}}]}""",
            """{"content":[{"type":"text","text":null}]}""",
            """{"content":[]}""",
            """{"content":null}""",
            """{"type":"message"}""",
            "not json at all",
            ""
        ).forEach { body ->
            assertMalformed("Claude: $body", provider, body, claudeRequest())
        }
    }

    @Test
    fun `claude keeps a good message when usage is unusable`() = runBlocking {
        val provider = ClaudeProvider(client, settings)
        listOf(
            """"usage":null""",
            """"usage":{"input_tokens":null,"output_tokens":null}"""
        ).forEach { usage ->
            server.enqueue(okResponse("""{"content":[{"type":"text","text":"pong"}],$usage}"""))

            val response = provider.complete(claudeRequest())

            assertEquals(usage, "pong", response.content)
            assertEquals(usage, 0, response.tokensUsed)
        }
    }

    @Test
    fun `claude streaming reports a bad response as malformed instead of network`() {
        val provider = ClaudeProvider(client, settings)
        listOf(
            "",
            "not json at all",
            """{"content":null}""",
            """{"content":[{"type":"tool_use","id":"1","name":"t","input":{}}]}"""
        ).forEach { body ->
            server.enqueue(okResponse(body))

            val failure = assertThrows("Claude stream: $body", LLMException::class.java) {
                runBlocking { provider.streamComplete(claudeRequest()).toList() }
            }

            assertEquals("Claude stream: $body", LLMError.MalformedResponse, failure.error)
        }
    }

    @Test
    fun `claude streaming passes typed api errors through`() {
        val provider = ClaudeProvider(client, settings)
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .setHeader("Content-Type", "application/json")
                .body("""{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""")
                .build()
        )

        val failure = assertThrows(LLMException::class.java) {
            runBlocking { provider.streamComplete(claudeRequest()).toList() }
        }

        assertEquals(LLMError.RateLimited, failure.error)
    }

    @Test
    fun `claude streaming still reports a real io failure as network`() {
        val provider = ClaudeProvider(client, settings)
        server.close()

        val failure = assertThrows(IOException::class.java) {
            runBlocking { provider.streamComplete(claudeRequest()).toList() }
        }

        assertEquals(
            LLMError.Network,
            LLMErrorMapper.fromThrowable("Claude", "model", failure).error
        )
    }

    @Test
    fun `cohere totals the documented v2 token counts`() = runBlocking {
        val provider = CohereProvider(client, settings)
        server.enqueue(
            okResponse(
                """
                {"message":{"role":"assistant","content":[{"type":"text","text":"pong"}]},
                 "usage":{"billed_units":{"input_tokens":3,"output_tokens":1},
                          "tokens":{"input_tokens":30,"output_tokens":12}}}
                """.trimIndent()
            )
        )

        val response = provider.complete(familyRequest())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
    }

    @Test
    fun `cohere reports a message without text as malformed`() {
        val provider = CohereProvider(client, settings)
        listOf(
            """{"message":{"role":"assistant","content":[]}}""",
            """{"message":{"role":"assistant","tool_calls":[{"id":"1"}]}}""",
            """{"message":{"content":null}}""",
            """{"message":{"content":[{"type":"thinking","thinking":"hmm"}]}}""",
            """{"message":null}""",
            """{"usage":{}}""",
            "not json at all"
        ).forEach { body ->
            assertMalformed("Cohere: $body", provider, body, familyRequest())
        }
    }

    @Test
    fun `cohere keeps a good message when usage is unusable`() = runBlocking {
        val provider = CohereProvider(client, settings)
        listOf(
            """"usage":null""",
            """"usage":{"tokens":null}""",
            """"usage":{"tokens":{"input_tokens":null,"output_tokens":null}}"""
        ).forEach { usage ->
            server.enqueue(
                okResponse("""{"message":{"content":[{"type":"text","text":"pong"}]},$usage}""")
            )

            val response = provider.complete(familyRequest())

            assertEquals(usage, "pong", response.content)
            assertEquals(usage, 0, response.tokensUsed)
        }
    }

    private fun assertMalformed(
        label: String,
        provider: LLMProvider,
        body: String,
        request: LLMRequest
    ) {
        server.enqueue(okResponse(body))
        val thrown = assertThrows(label, Throwable::class.java) {
            runBlocking { provider.complete(request) }
        }
        // Through the mapper, because that is what WrappedLLMProvider shows the caller.
        val failure = LLMErrorMapper.fromThrowable(provider.name, request.model.orEmpty(), thrown)
        assertEquals(label, LLMError.MalformedResponse, failure.error)
        assertFalse("$label must not be retried", failure.retryable)
    }

    private fun okResponse(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun familyRequest() = newRequest(model = "test-model")

    /** Claude only accepts catalog model ids, so it uses its default. */
    private fun claudeRequest() = newRequest(model = null)

    private fun newRequest(model: String?) = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", "hello", ChatMessage.Sender.USER)),
        model = model,
        providerConfig = ProviderRequestConfig(
            apiKey = "sk-test-0123456789abcdef",
            endpoint = "${loopback}v1"
        )
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-response-parsing-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = EmptyProviderCredentialStore(),
        runStartupMigration = false
    )

    /** The request under test carries its own credential, so nothing is stored. */
    private class EmptyProviderCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
