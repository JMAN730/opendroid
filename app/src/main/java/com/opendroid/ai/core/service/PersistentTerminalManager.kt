package com.opendroid.ai.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
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
        OutputStreamWriter(process.outputStream, Charsets.UTF_8).also { writer ->
            writer.write(command)
            writer.newLine()
            writer.flush()
        }
    }

    suspend fun read(id: String): String = withContext(Dispatchers.IO) {
        val process = requireSession(id)
        val output = StringBuilder()
        readAvailable(process.inputStream, output)
        readAvailable(process.errorStream, output)
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

    private fun readAvailable(stream: java.io.InputStream, output: StringBuilder) {
        val available = stream.available().coerceAtMost(MAX_OUTPUT_BYTES - output.length)
        if (available <= 0) return
        val buffer = ByteArray(available)
        val count = stream.read(buffer)
        if (count > 0) output.append(String(buffer, 0, count, Charsets.UTF_8))
    }

    private companion object {
        const val MAX_COMMAND_LENGTH = 4096
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
