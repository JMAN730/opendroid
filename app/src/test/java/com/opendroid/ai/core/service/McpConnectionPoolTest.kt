package com.opendroid.ai.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class McpConnectionPoolTest {

    @Test
    fun `a request waiting for the dispatch lock never runs once the pool is shut down`() {
        val dispatchLock = ReentrantLock()
        val ran = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val pool = McpConnectionPool(1, 1, "test-mcp", dispatchLock) { _ ->
            try {
                runExclusive { ran.set(true) }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use {
                dispatchLock.lock()
                try {
                    pool.submit(server.accept())
                    waitUntil { dispatchLock.hasQueuedThreads() }
                    pool.shutdown()
                } finally {
                    dispatchLock.unlock()
                }
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            }
        }

        assertFalse("the tool ran after the pool stopped", ran.get())
        val rejected = failure.get() as McpRequestRejectedException
        assertEquals(503, rejected.status)
    }

    @Test
    fun `a request runs under the dispatch lock while the pool is live`() {
        val dispatchLock = ReentrantLock()
        val heldLock = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val pool = McpConnectionPool(1, 1, "test-mcp", dispatchLock) { _ ->
            runExclusive { heldLock.set(dispatchLock.isHeldByCurrentThread) }
            finished.countDown()
        }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use {
                pool.submit(server.accept())
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            }
        }
        pool.shutdown()

        assertTrue(heldLock.get())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            Thread.sleep(5)
        }
    }
}
