package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage for the OpenCode Go gateway contract: the chat request
 * must carry the agent User-Agent and a well-formed session id in addition to
 * the bearer token. A generic OpenAI-compatible request is rejected upstream.
 */
class OpencodeGoProviderNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val provider = OpencodeGoProvider(AppModule.provideOkHttpClient(), newSettingsRepository())

    /** Never logged or asserted on directly; only its absence from failures is asserted. */
    private val apiKey = "oc_sk_test_0123456789abcdef"
    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a completion carries the bearer token agent user agent and session`() = runBlocking {
        server.enqueue(successResponse())

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
        assertEquals("beam-free", response.model)
        assertEquals("Opencode-Go", response.provider)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer $apiKey", recorded.headers["Authorization"])
        assertEquals(OpencodeGoProtocol.userAgent(), recorded.headers["User-Agent"])
        assertTrue(
            "session header was ${recorded.headers["x-opencode-session"]}",
            SESSION_PATTERN.matches(recorded.headers["x-opencode-session"].orEmpty())
        )
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body.contains("\"model\":\"beam-free\""))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
    }

    @Test
    fun `the session id is stable for the process`() = runBlocking {
        server.enqueue(successResponse())
        server.enqueue(successResponse())

        provider.complete(newRequest())
        provider.complete(newRequest())

        val first = server.takeRequest().headers["x-opencode-session"]
        val second = server.takeRequest().headers["x-opencode-session"]

        assertEquals(first, second)
    }

    @Test
    fun `generated session ids match the gateway shape`() {
        repeat(32) {
            assertTrue(SESSION_PATTERN.matches(OpencodeGoProtocol.newSessionId()))
        }
    }

    @Test
    fun `a non-2xx response becomes a redacted LLMException`() {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/json")
                .body(
                    """
                    {"error":{"type":"invalid_request_error","code":"invalid_api_key",
                     "message":"Incorrect API key provided: $apiKey. Prompt was: $prompt"}}
                    """.trimIndent()
                )
                .build()
        )

        val failure = assertThrows(LLMException::class.java) {
            runBlocking { provider.complete(newRequest()) }
        }

        assertEquals(LLMError.AuthInvalid, failure.error)
        assertEquals(401, failure.status)
        assertEquals("invalid_request_error", failure.detail?.vendorType)
        assertEquals("invalid_api_key", failure.detail?.vendorCode)

        val rendered = "${failure.message} ${failure.detail} ${failure.error.code}"
        assertFalse(rendered.contains(apiKey))
        assertFalse(rendered.contains(prompt))
        assertFalse(rendered.contains(server.hostName))
        assertFalse(rendered.contains("Incorrect API key provided"))
    }

    private fun successResponse() = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            """
            {
              "choices": [{"message": {"content": "pong"}}],
              "usage": {"total_tokens": 42}
            }
            """.trimIndent()
        )
        .build()

    private fun newRequest() = LLMRequest(
        systemPrompt = "you are a test",
        messages = listOf(ChatMessage("1", prompt, ChatMessage.Sender.USER)),
        model = "beam-free",
        providerConfig = ProviderRequestConfig(
            apiKey = apiKey,
            endpoint = server.url("/v1").toString()
        )
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-opencode-test")
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

    private companion object {
        val SESSION_PATTERN = Regex("^ses_[0-9a-f]{32}$")
    }
}
