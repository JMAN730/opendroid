package com.opendroid.ai.core.service

import android.content.pm.PackageManager
import android.util.Log
import dev.rikka.shizuku.Shizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class CommandBackend {
    SHIZUKU,
    ROOT,
    APP_SHELL,
    UNAVAILABLE
}

data class CommandExecutionResult(
    val backend: CommandBackend,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

@Singleton
class PrivilegedCommandExecutor @Inject constructor() {

    suspend fun execute(command: String): CommandExecutionResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command must not be empty" }
        require(command.length <= MAX_COMMAND_LENGTH) { "Command is too long" }

        when (val backend = selectBackend()) {
            CommandBackend.SHIZUKU -> runShizuku(command, backend)
            CommandBackend.ROOT -> runProcess(arrayOf("su", "-c", command), backend)
            CommandBackend.APP_SHELL -> runProcess(arrayOf("sh", "-c", command), backend)
            CommandBackend.UNAVAILABLE -> CommandExecutionResult(
                backend = backend,
                exitCode = -1,
                stdout = "",
                stderr = "No command execution backend is available"
            )
        }
    }

    fun status(): Map<String, String> = mapOf(
        "backend" to selectBackend().name,
        "shizuku" to shizukuStatus(),
        "root" to if (rootAvailable()) "available" else "unavailable"
    )

    fun startShell(): Pair<CommandBackend, Process> = when (val backend = selectBackend()) {
        CommandBackend.SHIZUKU -> backend to Shizuku.newProcess(arrayOf("sh"), null, null)
        CommandBackend.ROOT -> backend to ProcessBuilder("su").redirectErrorStream(true).start()
        CommandBackend.APP_SHELL -> backend to ProcessBuilder("sh").redirectErrorStream(true).start()
        CommandBackend.UNAVAILABLE -> error("No command execution backend is available")
    }

    private fun selectBackend(): CommandBackend = when {
        shizukuAvailable() -> CommandBackend.SHIZUKU
        rootAvailable() -> CommandBackend.ROOT
        appShellAvailable() -> CommandBackend.APP_SHELL
        else -> CommandBackend.UNAVAILABLE
    }

    private fun shizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (error: Throwable) {
        Log.d(TAG, "Shizuku unavailable", error)
        false
    }

    private fun shizukuStatus(): String = try {
        when {
            !Shizuku.pingBinder() -> "not_running"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "authorized"
            else -> "permission_required"
        }
    } catch (_: Throwable) {
        "unavailable"
    }

    private fun rootAvailable(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").start()
        process.inputStream.close()
        process.errorStream.close()
        process.waitFor() == 0
    } catch (_: Throwable) {
        false
    }

    private fun appShellAvailable(): Boolean = try {
        ProcessBuilder("sh", "-c", "true").start().waitFor() == 0
    } catch (_: Throwable) {
        false
    }

    private fun runShizuku(command: String, backend: CommandBackend): CommandExecutionResult {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        return readProcess(process, backend)
    }

    private fun runProcess(command: Array<String>, backend: CommandBackend): CommandExecutionResult =
        readProcess(ProcessBuilder(*command).start(), backend)

    private fun readProcess(process: Process, backend: CommandBackend): CommandExecutionResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread { readLimited(process.inputStream, stdout) }
        val stderrThread = Thread { readLimited(process.errorStream, stderr) }
        stdoutThread.start()
        stderrThread.start()
        val completed = process.waitFor(COMMAND_TIMEOUT_MS, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        stdoutThread.join(OUTPUT_JOIN_TIMEOUT_MS)
        stderrThread.join(OUTPUT_JOIN_TIMEOUT_MS)
        return CommandExecutionResult(
            backend,
            if (completed) process.exitValue() else TIMEOUT_EXIT_CODE,
            stdout.toString(),
            if (completed) stderr.toString() else "${stderr}Command timed out"
        )
    }

    private fun readLimited(stream: java.io.InputStream, output: StringBuilder) {
        val buffer = ByteArray(8192)
        var remaining = MAX_OUTPUT_BYTES
        stream.use {
            while (remaining > 0) {
                val count = it.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.append(String(buffer, 0, count, Charsets.UTF_8))
                remaining -= count
            }
        }
    }

    private companion object {
        const val TAG = "PrivilegedCommandExecutor"
        const val MAX_COMMAND_LENGTH = 4096
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val COMMAND_TIMEOUT_MS = 30L
        const val OUTPUT_JOIN_TIMEOUT_MS = 1000L
        const val TIMEOUT_EXIT_CODE = 124
    }
}
