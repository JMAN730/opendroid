package com.opendroid.ai.core.llm.chatgpt

import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptOAuthClientTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val issuer = server.url("").toString().trimEnd('/')
    private var now = 1_000_000L
    private val client = ChatGptOAuthClient(AppModule.provideOkHttpClient(), issuer) { now }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `start requests a user code for the Codex client and reports where to enter it`() = runBlocking {
        server.enqueue(json("""{"device_auth_id":"dev-1","user_code":"ABCD-1234","interval":"7"}"""))

        val start = client.startDeviceLogin()

        assertEquals("ABCD-1234", start.userCode)
        assertEquals("$issuer/codex/device", start.verificationUrl)
        assertEquals(7_000L, start.intervalMillis)
        assertEquals(now + ChatGptProtocol.FLOW_TTL_MILLIS, start.expiresAtMillis)
        val recorded = server.takeRequest()
        assertEquals("/api/accounts/deviceauth/usercode", recorded.target)
        assertEquals("opendroid", recorded.headers["originator"])
        assertEquals("""{"client_id":"${ChatGptProtocol.CLIENT_ID}"}""", recorded.body?.utf8())
    }

    @Test
    fun `start accepts the legacy usercode field and clamps the interval`() = runBlocking {
        server.enqueue(json("""{"device_auth_id":"dev-1","usercode":"WXYZ","interval":0}"""))

        val start = client.startDeviceLogin()

        assertEquals("WXYZ", start.userCode)
        assertEquals(1_000L, start.intervalMillis)
    }

    @Test
    fun `start without an interval polls every five seconds`() = runBlocking {
        server.enqueue(json("""{"device_auth_id":"dev-1","user_code":"WXYZ"}"""))

        assertEquals(5_000L, client.startDeviceLogin().intervalMillis)
    }

    @Test
    fun `start fails with the status when device authorization is unavailable`() {
        server.enqueue(MockResponse.Builder().code(503).body("upstream detail").build())

        val failure = assertThrows(ChatGptAuthException::class.java) {
            runBlocking { client.startDeviceLogin() }
        }

        assertEquals(503, failure.httpStatus)
        assertFalse(failure.message.orEmpty().contains("upstream detail"))
    }

    @Test
    fun `start rejects a response without a device id or code`() {
        server.enqueue(json("""{"user_code":"ABCD"}"""))

        assertThrows(ChatGptAuthException::class.java) {
            runBlocking { client.startDeviceLogin() }
        }
    }

    @Test
    fun `403 and 404 while polling mean the user has not approved yet`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(DevicePollResult.Pending, client.pollDeviceLogin(start()))
        assertEquals(DevicePollResult.Pending, client.pollDeviceLogin(start()))
        val recorded = server.takeRequest()
        assertEquals("/api/accounts/deviceauth/token", recorded.target)
        assertEquals("""{"device_auth_id":"dev-1","user_code":"ABCD-1234"}""", recorded.body?.utf8())
    }

    @Test
    fun `a network failure while polling is retried rather than failing the login`() = runBlocking {
        server.close()

        assertEquals(DevicePollResult.Pending, client.pollDeviceLogin(start()))
    }

    @Test
    fun `any other poll error fails the login`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())

        assertEquals(
            DevicePollResult.Failed("Device authorization failed (HTTP 500)."),
            client.pollDeviceLogin(start())
        )
    }

    @Test
    fun `an expired login fails without contacting the server`() = runBlocking {
        val start = start()
        now = start.expiresAtMillis

        assertEquals(
            DevicePollResult.Failed("This login request expired. Start again."),
            client.pollDeviceLogin(start)
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an approved login exchanges the authorization code for a session`() = runBlocking {
        val idToken = fakeJwt(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-9","chatgpt_plan_type":"plus"}}"""
        )
        server.enqueue(json("""{"authorization_code":"code-1","code_verifier":"verifier-1"}"""))
        server.enqueue(
            json("""{"access_token":"access-1","refresh_token":"refresh-1","id_token":"$idToken","expires_in":600}""")
        )

        val result = client.pollDeviceLogin(start())

        assertEquals(
            DevicePollResult.Approved(
                ChatGptSession(
                    access = "access-1",
                    refresh = "refresh-1",
                    expiresAt = now + 600_000,
                    accountId = "acct-9",
                    planType = "plus"
                )
            ),
            result
        )
        server.takeRequest()
        val exchange = server.takeRequest()
        assertEquals("/oauth/token", exchange.target)
        val form = exchange.body?.utf8().orEmpty().split('&').toSet()
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=code-1"))
        assertTrue(form.contains("code_verifier=verifier-1"))
        assertTrue(form.contains("client_id=${ChatGptProtocol.CLIENT_ID}"))
        assertTrue(form.contains("redirect_uri=" + java.net.URLEncoder.encode("$issuer/deviceauth/callback", "UTF-8")))
    }

    @Test
    fun `a rejected code exchange fails the login`() = runBlocking {
        server.enqueue(json("""{"authorization_code":"code-1","code_verifier":"verifier-1"}"""))
        server.enqueue(MockResponse.Builder().code(400).build())

        assertEquals(
            DevicePollResult.Failed("Token exchange failed. Start again."),
            client.pollDeviceLogin(start())
        )
    }

    @Test
    fun `a code exchange missing the refresh token fails the login`() = runBlocking {
        server.enqueue(json("""{"authorization_code":"code-1","code_verifier":"verifier-1"}"""))
        server.enqueue(json("""{"access_token":"access-1"}"""))

        assertEquals(
            DevicePollResult.Failed("Token exchange failed. Start again."),
            client.pollDeviceLogin(start())
        )
    }

    @Test
    fun `refresh keeps the account and refresh token the response omits`() = runBlocking {
        server.enqueue(json("""{"access_token":"access-2"}"""))
        val current = ChatGptSession("access-1", "refresh-1", now, accountId = "acct-9", planType = "pro")

        val fresh = client.refresh(current)

        assertEquals("access-2", fresh.access)
        assertEquals("refresh-1", fresh.refresh)
        assertEquals("acct-9", fresh.accountId)
        assertEquals("pro", fresh.planType)
        assertEquals(now + 3_600_000, fresh.expiresAt)
        val form = server.takeRequest().body?.utf8().orEmpty().split('&').toSet()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
    }

    @Test
    fun `a rejected refresh reports the status`() {
        server.enqueue(MockResponse.Builder().code(401).build())

        val failure = assertThrows(ChatGptAuthException::class.java) {
            runBlocking { client.refresh(ChatGptSession("a", "r", now)) }
        }

        assertEquals(401, failure.httpStatus)
    }

    @Test
    fun `token claims fall back from the plan claim to the organization id`() {
        val claims = ChatGptTokenClaims.parse(fakeJwt("""{"organizations":[{"id":"org-1"}],"exp":2000}"""))

        assertEquals("org-1", claims.accountId)
        assertNull(claims.planType)
        assertEquals(2_000_000L, claims.expiresAtMillis)
    }

    @Test
    fun `malformed tokens yield empty claims`() {
        assertEquals(ChatGptClaims(null, null, null), ChatGptTokenClaims.parse("not-a-jwt"))
        assertEquals(ChatGptClaims(null, null, null), ChatGptTokenClaims.parse("a.!!!.c"))
        assertEquals(ChatGptClaims(null, null, null), ChatGptTokenClaims.parse(null))
    }

    @Test
    fun `a session never prints its tokens`() {
        val text = ChatGptSession("secret-access", "secret-refresh", 1).toString()

        assertFalse(text.contains("secret-access"))
        assertFalse(text.contains("secret-refresh"))
    }

    private fun start() = DeviceLoginStart(
        userCode = "ABCD-1234",
        verificationUrl = "$issuer/codex/device",
        intervalMillis = 1_000,
        expiresAtMillis = now + ChatGptProtocol.FLOW_TTL_MILLIS,
        deviceAuthId = "dev-1"
    )

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
