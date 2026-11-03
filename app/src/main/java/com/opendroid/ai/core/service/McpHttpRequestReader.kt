package com.opendroid.ai.core.service

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

internal class McpHttpRequestHead(val requestLine: String, val headers: Map<String, String>)

/** The request is refused before it is authenticated; [status] is the HTTP status to answer with. */
internal class McpRequestRejectedException(val status: Int, message: String) : IOException(message)

/**
 * Reads the request line and headers of one HTTP request. Any local app can connect, so the
 * head is capped in line count and total bytes; past a cap the request is refused with 431.
 */
internal object McpHttpRequestReader {

    /** Returns null when the client closes the connection before the head is complete. */
    fun readHead(input: InputStream): McpHttpRequestHead? {
        val budget = intArrayOf(MAX_HEADER_BYTES)
        val requestLine = readAsciiLine(input, budget) ?: return null
        val headers = mutableMapOf<String, String>()
        var lines = 0
        while (true) {
            val line = readAsciiLine(input, budget) ?: return null
            if (line.isEmpty()) break
            if (++lines > MAX_HEADER_COUNT) {
                throw McpRequestRejectedException(431, "Too many HTTP header lines")
            }
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
        }
        return McpHttpRequestHead(requestLine, headers)
    }

    /** Reads one line, spending its bytes (line ending included) from [budget]. */
    private fun readAsciiLine(input: InputStream, budget: IntArray): String? {
        val limit = minOf(MAX_HEADER_LINE_BYTES, budget[0])
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val value = input.read()
            if (value < 0) return if (count == 0) null else String(bytes, 0, count, StandardCharsets.US_ASCII)
            budget[0]--
            if (value == '\n'.code) {
                val end = if (count > 0 && bytes[count - 1] == '\r'.code.toByte()) count - 1 else count
                return String(bytes, 0, end, StandardCharsets.US_ASCII)
            }
            bytes[count++] = value.toByte()
        }
        val reason = if (limit == MAX_HEADER_LINE_BYTES) "HTTP header line is too long" else "HTTP headers are too large"
        throw McpRequestRejectedException(431, reason)
    }

    const val MAX_HEADER_LINE_BYTES = 8192
    const val MAX_HEADER_COUNT = 64
    const val MAX_HEADER_BYTES = 16_384
}

/**
 * Bounds the time a whole request may take to arrive, not just each read: before every read
 * the remaining time is applied through [applyReadTimeout], and once it is spent the read fails.
 */
internal class DeadlineInputStream(
    private val input: InputStream,
    private val deadlineAtMs: Long,
    private val clock: () -> Long,
    private val applyReadTimeout: (Int) -> Unit
) : InputStream() {

    override fun read(): Int {
        beforeRead()
        try {
            return input.read()
        } catch (e: SocketTimeoutException) {
            throw deadlineOr(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        beforeRead()
        try {
            return input.read(buffer, offset, length)
        } catch (e: SocketTimeoutException) {
            throw deadlineOr(e)
        }
    }

    private fun beforeRead() {
        val remaining = deadlineAtMs - clock()
        if (remaining <= 0) throw expired()
        applyReadTimeout(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * A read timeout set to the time left on the deadline fires at the deadline, before the next
     * [beforeRead] can notice it; that is an expired request, not a failed read.
     */
    private fun deadlineOr(timeout: SocketTimeoutException): IOException =
        if (deadlineAtMs - clock() <= DEADLINE_TOLERANCE_MS) expired() else timeout

    private fun expired() = McpRequestRejectedException(408, "Request took too long to arrive")

    override fun available(): Int = input.available()

    override fun close() = input.close()

    private companion object {
        /** Clock granularity and timer slack between a socket timeout firing and the clock reading. */
        const val DEADLINE_TOLERANCE_MS = 50L
    }
}
