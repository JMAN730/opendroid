package com.opendroid.ai.core.net

import com.opendroid.ai.di.AppModule
import java.io.IOException
import java.net.InetAddress
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract of the shared OkHttp client after the OkHttp 5 upgrade.
 *
 * These assert the security properties the app relies on - redirect handling that
 * drops credentials when the origin changes, bounded redirect chains, cancellable
 * streaming reads, and certificate validation that is genuinely enforced - rather
 * than restating the builder configuration.
 *
 * The API key used here is a throwaway literal that never reaches a log or an
 * assertion message.
 */
class OkHttpNetworkStackTest {

    private val client = AppModule.provideOkHttpClient()
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    /**
     * TLS has to be configured before the socket is bound, so it is passed in here.
     * Binding IPv4 loopback explicitly keeps every server single-homed: on a
     * dual-stack host "localhost" yields two routes, and OkHttp's fallback to the
     * second one would mask the handshake failure the TLS tests assert on.
     */
    private fun newServer(https: SSLSocketFactory? = null): MockWebServer = MockWebServer().also {
        servers += it
        https?.let(it::useHttps)
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /**
     * The canonical name of 127.0.0.1 is whatever the host's resolver says, so URLs
     * for the TLS tests are pinned to the literal address the certificates name.
     */
    private fun MockWebServer.loopbackUrl(path: String): HttpUrl =
        url(path).newBuilder().host("127.0.0.1").build()

    @Test
    fun `same-origin redirects are followed and keep the Authorization header`() {
        val server = newServer()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/v1/final")
                .build()
        )
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val response = client.newCall(
            Request.Builder()
                .url(server.url("/v1/start"))
                .header("Authorization", "Bearer test-token")
                .build()
        ).execute()

        response.use { assertEquals(200, it.code) }
        server.takeRequest()
        val followUp = server.takeRequest()
        assertEquals("/v1/final", followUp.target)
        assertEquals("Bearer test-token", followUp.headers["Authorization"])
    }

    @Test
    fun `cross-origin redirects drop the Authorization header`() {
        val origin = newServer()
        val other = newServer()
        origin.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", other.url("/v1/final").toString())
                .build()
        )
        other.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val response = client.newCall(
            Request.Builder()
                .url(origin.url("/v1/start"))
                .header("Authorization", "Bearer test-token")
                .build()
        ).execute()

        response.use { assertEquals(200, it.code) }
        assertEquals("Bearer test-token", origin.takeRequest().headers["Authorization"])
        assertNull(other.takeRequest().headers["Authorization"])
    }

    @Test
    fun `redirect loops terminate instead of following forever`() {
        val server = newServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "/loop")
                    .build()
        }

        val failure = assertThrows(ProtocolException::class.java) {
            client.newCall(Request.Builder().url(server.url("/loop")).build()).execute()
        }
        assertTrue(failure.message.orEmpty().contains("Too many follow-up requests"))
    }

    @Test
    fun `cancelling a call aborts an in-flight streaming response`() {
        val server = newServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("x".repeat(256 * 1024))
                .throttleBody(1024, 200, TimeUnit.MILLISECONDS)
                .build()
        )

        val call = client.newCall(Request.Builder().url(server.url("/stream")).build())
        val response = call.execute()
        val source = response.body.source()
        source.require(1024)
        call.cancel()

        assertThrows(IOException::class.java) { source.readUtf8(256L * 1024L) }
        assertTrue(call.isCanceled())
    }

    @Test
    fun `an untrusted server certificate fails the handshake`() {
        val server = newServer(untrustedServerCertificates().sslSocketFactory())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        assertThrows(SSLHandshakeException::class.java) {
            client.newCall(Request.Builder().url(server.loopbackUrl("/v1/models")).build()).execute()
        }
    }

    @Test
    fun `a trusted server certificate completes the handshake over TLS`() {
        val certificateAuthority = HeldCertificate.Builder()
            .certificateAuthority(0)
            .build()
        val serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .signedBy(certificateAuthority)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificateAuthority.certificate)
            .build()

        val server = newServer(serverCertificates.sslSocketFactory())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val trustingClient = client.newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()

        trustingClient.newCall(Request.Builder().url(server.loopbackUrl("/v1/models")).build())
            .execute()
            .use { response ->
                assertEquals(200, response.code)
                assertEquals("https", response.request.url.scheme)
                assertTrue(response.handshake != null)
            }
    }

    /** A self-signed certificate the JVM's default trust store has never seen. */
    private fun untrustedServerCertificates(): HandshakeCertificates {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        return HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
    }
}
