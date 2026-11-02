package com.opendroid.ai.core.service

import android.util.Log
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Hands accepted connections to [handler] on a small bounded set of threads, so one slow
 * client cannot hold up the accept loop. Each connection is closed once its handler returns;
 * one that arrives while every worker is busy and the queue is full is closed at once.
 *
 * Handlers run tools through [runExclusive], one at a time across every pool that shares
 * [dispatchLock], so a pool from a previous server run cannot overlap the current one.
 */
internal class McpConnectionPool(
    workers: Int,
    queued: Int,
    threadName: String,
    private val dispatchLock: ReentrantLock,
    private val handler: McpConnectionPool.(Socket) -> Unit
) {

    private val threadCount = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        workers, workers, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queued)
    ) { task -> Thread(task, "$threadName-${threadCount.incrementAndGet()}") }

    fun submit(client: Socket) {
        try {
            executor.execute(Connection(client))
        } catch (rejected: RejectedExecutionException) {
            client.close()
        }
    }

    /**
     * Runs [block] while holding the dispatch lock. A wait for the lock ends when the pool is
     * shut down, and a pool that is shut down never starts [block]; both answer 503.
     */
    fun <T> runExclusive(block: () -> T): T {
        try {
            dispatchLock.lockInterruptibly()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw McpRequestRejectedException(503, "MCP server is stopping")
        }
        try {
            if (executor.isShutdown) throw McpRequestRejectedException(503, "MCP server is stopping")
            return block()
        } finally {
            dispatchLock.unlock()
        }
    }

    /** Stops taking connections, closes the ones still waiting for a worker and cancels lock waits. */
    fun shutdown() {
        executor.shutdownNow().forEach { pending -> (pending as? Connection)?.client?.close() }
    }

    private inner class Connection(val client: Socket) : Runnable {
        override fun run() {
            try {
                client.use { handler(this@McpConnectionPool, it) }
            } catch (error: Exception) {
                Log.e(TAG, "MCP request failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "McpServer"
    }
}
