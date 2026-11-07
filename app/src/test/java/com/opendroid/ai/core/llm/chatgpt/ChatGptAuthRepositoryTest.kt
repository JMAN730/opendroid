package com.opendroid.ai.core.llm.chatgpt

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAuthRepositoryTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private var now = 10_000_000L
    private val delays = mutableListOf<Long>()
    private val store = InMemoryCredentialStore()
    private val repository = ChatGptAuthRepository(
        store = store,
        oauth = ChatGptOAuthClient(AppModule.provideOkHttpClient(), server.url("").toString().trimEnd('/')) { now },
        nowMillis = { now },
        delayMillis = { millis -> delays += millis; now += millis }
    )

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `without a sign-in a request fails as missing auth and the status is signed out`() {
        val failure = assertThrows(LLMException::class.java) {
            runBlocking { repository.validSession() }
        }

        assertEquals(LLMError.AuthMissing, failure.error)
        assertEquals("ChatGPT", failure.provider)
        assertEquals(ChatGptAccountStatus.SignedOut, repository.status.value)
    }

    @Test
    fun `unreadable storage is reported as unavailable, not as signed out`() = runBlocking {
        store.unreadable = true

        assertEquals(ChatGptAccountStatus.Unavailable, repository.reloadStatus())
    }

    @Test
    fun `a session that is not near expiry is used as stored`() = runBlocking {
        save(session(access = "access-1", expiresAt = now + 10 * 60_000))

        assertEquals("access-1", repository.validSession().access)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a session within a minute of expiry is refreshed and the new one is saved`() = runBlocking {
        save(session(access = "access-1", expiresAt = now + 30_000))
        server.enqueue(json("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""))

        val fresh = repository.validSession()

        assertEquals("access-2", fresh.access)
        assertEquals("refresh-2", stored()?.refresh)
        assertEquals("acct-1", stored()?.accountId)
    }

    @Test
    fun `concurrent refreshes of the same rejected token use one refresh token exchange`() = runBlocking {
        val rejected = session(access = "access-1", expiresAt = now + 10 * 60_000)
        save(rejected)
        server.enqueue(json("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""))

        val results = (1..3).map { async { repository.refresh(rejected) } }.awaitAll()

        assertTrue(results.all { it.access == "access-2" })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rejected refresh is an auth failure and keeps the stored session`() = runBlocking {
        val current = session(access = "access-1", expiresAt = now)
        save(current)
        server.enqueue(MockResponse.Builder().code(401).build())

        val failure = runCatching { repository.validSession() }.exceptionOrNull()

        assertTrue("got $failure", failure is LLMException)
        assertEquals(LLMError.AuthInvalid, (failure as LLMException).error)
        assertEquals(401, failure.status)
        assertEquals(current, stored())
    }

    @Test
    fun `a refresh server error is retryable`() = runBlocking {
        save(session(access = "access-1", expiresAt = now))
        server.enqueue(MockResponse.Builder().code(503).build())

        val failure = runCatching { repository.validSession() }.exceptionOrNull() as LLMException

        assertEquals(LLMError.ServerError, failure.error)
        assertTrue(failure.retryable)
    }

    @Test
    fun `a device login polls at its interval until approved and then saves the session`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(json("""{"authorization_code":"code-1","code_verifier":"verifier-1"}"""))
        server.enqueue(json("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""))

        val outcome = repository.completeDeviceLogin(start(intervalMillis = 2_000))

        assertEquals(DeviceLoginOutcome.SignedIn, outcome)
        assertEquals(listOf(2_000L, 2_000L), delays)
        assertEquals("access-1", stored()?.access)
        assertEquals(ChatGptAccountStatus.SignedIn(null), repository.status.value)
    }

    @Test
    fun `a device login that is never approved expires`() = runBlocking {
        repeat(20) { server.enqueue(MockResponse.Builder().code(403).build()) }

        val outcome = repository.completeDeviceLogin(start(intervalMillis = 60_000, ttlMillis = 5 * 60_000))

        assertEquals(DeviceLoginOutcome.Failed("This login request expired. Start again."), outcome)
        assertNull(stored())
    }

    @Test
    fun `a device login whose session cannot be saved reports it`() = runBlocking {
        store.failWrites = true
        server.enqueue(json("""{"authorization_code":"code-1","code_verifier":"verifier-1"}"""))
        server.enqueue(json("""{"access_token":"access-1","refresh_token":"refresh-1"}"""))

        val outcome = repository.completeDeviceLogin(start(intervalMillis = 1_000))

        assertTrue(outcome is DeviceLoginOutcome.Failed)
        assertEquals(ChatGptAccountStatus.SignedOut, repository.status.value)
    }

    @Test
    fun `sign out removes the session`() = runBlocking {
        save(session(access = "access-1", expiresAt = now + 600_000))
        repository.reloadStatus()

        assertTrue(repository.signOut())

        assertNull(stored())
        assertEquals(ChatGptAccountStatus.SignedOut, repository.status.value)
    }

    @Test
    fun `an undecodable stored session is treated as signed out`() = runBlocking {
        store.values[ProviderCredentialId.ChatGptSession] = "not json"

        assertEquals(ChatGptAccountStatus.SignedOut, repository.reloadStatus())
        assertFalse(runCatching { repository.validSession() }.isSuccess)
    }

    private fun session(access: String, expiresAt: Long) = ChatGptSession(
        access = access,
        refresh = "refresh-1",
        expiresAt = expiresAt,
        accountId = "acct-1",
        planType = null
    )

    private fun save(session: ChatGptSession) {
        store.values[ProviderCredentialId.ChatGptSession] =
            Json.encodeToString(ChatGptSession.serializer(), session)
    }

    private fun stored(): ChatGptSession? = store.values[ProviderCredentialId.ChatGptSession]
        ?.let { Json.decodeFromString(ChatGptSession.serializer(), it) }

    private fun start(intervalMillis: Long, ttlMillis: Long = ChatGptProtocol.FLOW_TTL_MILLIS) = DeviceLoginStart(
        userCode = "ABCD-1234",
        verificationUrl = "https://example.invalid/codex/device",
        intervalMillis = intervalMillis,
        expiresAtMillis = now + ttlMillis,
        deviceAuthId = "dev-1"
    )

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
