package com.opendroid.ai.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class TerminalSessionInfo(
    val id: String,
    val backend: CommandBackend
)

@Singleton
class PersistentTerminalManager @Inject constructor(
    private val commandExecutor: PrivilegedCommandExecutor
) {

    private val sessions = ConcurrentHashMap<String, Process>()
    private val backends = ConcurrentHashMap<String, CommandBackend>()

    suspend fun create(): TerminalSessionInfo = withContext(Dispatchers.IO) {
        val (backend, process) = commandExecutor.startShell()
        val id = UUID.randomUUID().toString()
        sessions[id] = process
        backends[id] = backend
        TerminalSessionInfo(id, backend)
    }

    suspend fun write(id: String, command: String) = withContext(Dispatchers.IO) {
        require(command.length <= MAX_COMMAND_LENGTH) { "Command is too long" }
        val process = requireSession(id)
        // Write bytes directly so we never close the process's long-lived output stream.
        val stream = process.outputStream
        stream.write((command + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    suspend fun read(id: String): String = withContext(Dispatchers.IO) {
        val process = requireSession(id)
        val output = StringBuilder()
        readAvailable(listOf(process.inputStream, process.errorStream), output)
        output.toString()
    }

    fun list(): List<TerminalSessionInfo> = sessions.keys().asSequence().map { id ->
        TerminalSessionInfo(id, backends[id] ?: CommandBackend.UNAVAILABLE)
    }.toList()

    fun close(id: String) {
        sessions.remove(id)?.destroy()
        backends.remove(id)
    }

    fun closeAll() {
        sessions.keys().toList().forEach(::close)
    }

    private fun requireSession(id: String): Process = sessions[id]
        ?: throw IllegalArgumentException("Unknown terminal session: $id")

    private fun readAvailable(streams: List<InputStream>, output: StringBuilder) {
        val deadline = System.nanoTime() + READ_TIMEOUT_MS * NANOS_PER_MILLISECOND
        val buffer = ByteArray(8192)
        while (output.length < MAX_OUTPUT_BYTES && System.nanoTime() < deadline) {
            var readAny = false
            streams.forEach { stream ->
                val available = stream.available().coerceAtMost(MAX_OUTPUT_BYTES - output.length)
                if (available > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, available))
                    if (count > 0) {
                        output.append(String(buffer, 0, count, Charsets.UTF_8))
                        readAny = true
                    }
                }
            }
            if (!readAny) {
                try {
                    Thread.sleep(READ_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private companion object {
        const val MAX_COMMAND_LENGTH = 4096
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val READ_TIMEOUT_MS = 1000L
        const val READ_POLL_INTERVAL_MS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
