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
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return CommandExecutionResult(backend, process.waitFor(), stdout, stderr)
    }

    private companion object {
        const val TAG = "PrivilegedCommandExecutor"
        const val MAX_COMMAND_LENGTH = 4096
    }
}
