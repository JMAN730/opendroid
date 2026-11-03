package com.opendroid.ai.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpHttpRequestReaderTest {

    @Test
    fun `reads request line and headers`() {
        val head = McpHttpRequestReader.readHead(stream(
            "POST /mcp HTTP/1.1\r\nX-OpenDroid-Token: abc\r\nContent-Length: 12\r\n\r\n{}"
        ))!!

        assertEquals("POST /mcp HTTP/1.1", head.requestLine)
        assertEquals(mapOf("x-opendroid-token" to "abc", "content-length" to "12"), head.headers)
    }

    @Test
    fun `returns null when the client hangs up mid head`() {
        assertNull(McpHttpRequestReader.readHead(stream("POST /mcp HTTP/1.1\r\nHost: x\r\n")))
    }

    @Test
    fun `endless distinct header lines are refused once the header count cap is hit`() {
        val request = StringBuilder("POST /mcp HTTP/1.1\r\n")
        repeat(10_000) { index -> request.append("X-Header-$index: v\r\n") }
        request.append("\r\n")
        val input = CountingInputStream(stream(request.toString()))

        val rejected = assertThrows(McpRequestRejectedException::class.java) {
            McpHttpRequestReader.readHead(input)
        }

        assertEquals(431, rejected.status)
        // Reading stops at the cap instead of buffering all 10,000 lines into the map.
        assertTrue("read ${input.count} bytes", input.count <= McpHttpRequestReader.MAX_HEADER_BYTES)
    }

    @Test
    fun `large header lines are refused once the total header bytes cap is hit`() {
        val line = "X-Big-%d: " + "a".repeat(4_000) + "\r\n"
        val request = StringBuilder("POST /mcp HTTP/1.1\r\n")
        repeat(20) { index -> request.append(line.format(index)) }
        request.append("\r\n")
        val input = CountingInputStream(stream(request.toString()))

        val rejected = assertThrows(McpRequestRejectedException::class.java) {
            McpHttpRequestReader.readHead(input)
        }

        assertEquals(431, rejected.status)
        assertTrue("read ${input.count} bytes", input.count <= McpHttpRequestReader.MAX_HEADER_BYTES)
    }

    @Test
    fun `an overlong single header line is refused with 431`() {
        val rejected = assertThrows(McpRequestRejectedException::class.java) {
            McpHttpRequestReader.readHead(stream("POST /mcp HTTP/1.1\r\nX: " + "a".repeat(9_000) + "\r\n\r\n"))
        }

        assertEquals(431, rejected.status)
    }

    @Test
    fun `a client trickling one byte per read cannot outlast the request deadline`() {
        var now = 0L
        val appliedTimeouts = mutableListOf<Int>()
        // Every read takes 14 s, just under the old per-read timeout, and never ends the head.
        val trickle = object : InputStream() {
            override fun read(): Int {
                now += 14_000
                return 'a'.code
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = read().toByte()
                return 1
            }
        }
        val input = BufferedInputStream(DeadlineInputStream(trickle, deadlineAtMs = 30_000, clock = { now }) {
            appliedTimeouts += it
        })

        val rejected = assertThrows(McpRequestRejectedException::class.java) {
            McpHttpRequestReader.readHead(input)
        }

        assertEquals(408, rejected.status)
        assertTrue("clock at $now", now <= 42_000)
        assertEquals(listOf(30_000, 16_000, 2_000), appliedTimeouts)
    }

    @Test
    fun `a socket timeout fired by the request deadline is refused with 408`() {
        var now = 0L
        // The socket timeout was set to the 2 s left on the deadline, so the stalled read throws then.
        val stalled = object : InputStream() {
            override fun read(): Int {
                now += 1_999
                throw SocketTimeoutException("Read timed out")
            }
        }
        val input = DeadlineInputStream(stalled, deadlineAtMs = 2_000, clock = { now }) {}

        val rejected = assertThrows(McpRequestRejectedException::class.java) {
            McpHttpRequestReader.readHead(input)
        }

        assertEquals(408, rejected.status)
    }

    @Test
    fun `a socket timeout well before the request deadline is left alone`() {
        var now = 0L
        val stalled = object : InputStream() {
            override fun read(): Int {
                now += 15_000
                throw SocketTimeoutException("Read timed out")
            }
        }
        val input = DeadlineInputStream(stalled, deadlineAtMs = 60_000, clock = { now }) {}

        assertThrows(SocketTimeoutException::class.java) { McpHttpRequestReader.readHead(input) }
    }

    @Test
    fun `a stalled connection does not stop the next client from being served`() {
        ServerSocket(0).use { server ->
            val served = CountDownLatch(1)
            val pool = McpConnectionPool(workers = 2, queued = 2, threadName = "test-mcp", dispatchLock = java.util.concurrent.locks.ReentrantLock()) { socket ->
                val first = socket.getInputStream().read()
                if (first == 'B'.code) served.countDown()
            }
            val accepter = Thread {
                repeat(2) { pool.submit(server.accept()) }
            }.apply { isDaemon = true; start() }
            Socket("127.0.0.1", server.localPort).use { stalled ->
                Socket("127.0.0.1", server.localPort).use { next ->
                    next.getOutputStream().write('B'.code)
                    next.getOutputStream().flush()

                    assertTrue("second client was never handled", served.await(5, TimeUnit.SECONDS))
                }
            }
            accepter.join(5_000)
            pool.shutdown()
        }
    }

    @Test
    fun `connections beyond the workers and queue are closed instead of piling up`() {
        ServerSocket(0).use { server ->
            val release = CountDownLatch(1)
            val pool = McpConnectionPool(workers = 1, queued = 1, threadName = "test-mcp", dispatchLock = java.util.concurrent.locks.ReentrantLock()) {
                release.await(5, TimeUnit.SECONDS)
            }
            val clients = List(3) { Socket("127.0.0.1", server.localPort) }
            try {
                val accepted = List(3) { server.accept() }
                accepted.forEach(pool::submit)

                // The overflow connection is closed at once; the client sees end of stream.
                clients[2].soTimeout = 5_000
                assertEquals(-1, clients[2].getInputStream().read())
                // The connection being worked on stays open while its handler runs.
                clients[0].soTimeout = 200
                assertThrows(SocketTimeoutException::class.java) { clients[0].getInputStream().read() }
            } finally {
                release.countDown()
                clients.forEach(Socket::close)
                pool.shutdown()
            }
        }
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(StandardCharsets.US_ASCII))

    private class CountingInputStream(private val input: InputStream) : InputStream() {
        var count = 0
            private set

        override fun read(): Int = input.read().also { if (it >= 0) count++ }
    }
}
