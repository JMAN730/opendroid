package com.opendroid.ai.core.service

import android.util.Log
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands accepted connections to [handler] on a small bounded set of threads, so one slow
 * client cannot hold up the accept loop. Each connection is closed once its handler returns;
 * one that arrives while every worker is busy and the queue is full is closed at once.
 */
internal class McpConnectionPool(
    workers: Int,
    queued: Int,
    threadName: String,
    private val handler: (Socket) -> Unit
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

    /** Stops taking connections and closes the ones still waiting for a worker. */
    fun shutdown() {
        executor.shutdownNow().forEach { pending -> (pending as? Connection)?.client?.close() }
    }

    private inner class Connection(val client: Socket) : Runnable {
        override fun run() {
            try {
                client.use(handler)
            } catch (error: Exception) {
                Log.e(TAG, "MCP request failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "McpServer"
    }
}
