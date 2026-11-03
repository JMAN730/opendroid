package com.opendroid.ai.core.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionDispatcher: ActionDispatcher,
    private val commandExecutor: PrivilegedCommandExecutor,
    private val configStore: McpConfigStore,
    private val terminalManager: PersistentTerminalManager
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    // Shared by every pool, so a pool from a previous run cannot dispatch alongside the current one.
    private val dispatchLock = ReentrantLock()

    @Synchronized
    fun start() {
        if (running.get()) return
        val previous = serverThread
        if (previous != null && previous.isAlive) {
            previous.join(JOIN_TIMEOUT_MS)
        }
        running.set(true)
        serverThread = Thread(::serve, THREAD_NAME).also { it.start() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        val thread = serverThread
        serverThread = null
        thread?.join(JOIN_TIMEOUT_MS)
        terminalManager.closeAll()
    }

    private fun serve() {
        val connections = McpConnectionPool(WORKERS, QUEUED_CONNECTIONS, THREAD_NAME, dispatchLock) { socket -> handle(socket) }
        try {
            ServerSocket(PORT, BACKLOG, InetAddress.getByName(LOOPBACK)).use { socket ->
                serverSocket = socket
                while (running.get()) {
                    try {
                        connections.submit(socket.accept())
                    } catch (error: Exception) {
                        if (running.get()) Log.e(TAG, "MCP request failed", error)
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "MCP server could not bind to port $PORT", error)
        } finally {
            running.set(false)
            serverSocket = null
            connections.shutdown()
        }
    }

    private fun McpConnectionPool.handle(socket: Socket) {
        // The whole request must arrive within one deadline, so a client trickling bytes
        // cannot keep a worker past it; each read still times out after REQUEST_TIMEOUT_MS.
        val deadlineAtMs = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        val input = BufferedInputStream(DeadlineInputStream(socket.getInputStream(), deadlineAtMs,
            System::currentTimeMillis) { remaining -> socket.soTimeout = minOf(remaining, REQUEST_TIMEOUT_MS) })
        val head = try {
            McpHttpRequestReader.readHead(input) ?: return
        } catch (rejected: McpRequestRejectedException) {
            writeResponse(socket.getOutputStream(), rejected.status, JSONObject().put("error", rejected.message))
            return
        }
        val requestLine = head.requestLine
        val headers = head.headers

        // A token that no longer decrypts is never replaced behind the user's back; say why
        // requests fail instead of answering 401 to a client that holds the right token.
        val accessToken = try {
            configStore.accessToken()
        } catch (error: McpSecretsMustBeReenteredException) {
            writeResponse(socket.getOutputStream(), 503, JSONObject().put("error", error.message))
            return
        }
        if (!tokensMatch(headers["x-opendroid-token"].orEmpty(), accessToken)) {
            writeResponse(socket.getOutputStream(), 401, JSONObject().put("error", "Unauthorized"))
            return
        }
        if (!requestLine.startsWith("POST /mcp ")) {
            writeResponse(socket.getOutputStream(), 404, JSONObject().put("error", "Not found"))
            return
        }

        try {
            val length = headers["content-length"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Content-Length is required")
            require(length in 1..MAX_REQUEST_BYTES) { "Request body is too large" }
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                val read = input.read(body, offset, body.size - offset)
                if (read < 0) throw IllegalArgumentException("Incomplete request body")
                offset += read
            }
            val request = JSONObject(String(body, StandardCharsets.UTF_8).trim())
            if (!request.has("id")) {
                writeEmptyResponse(socket.getOutputStream())
                return
            }
            // Requests are read in parallel, but tools still run one at a time as before, and
            // none starts once this connection's pool has been shut down.
            val response = runExclusive { dispatch(request) }
            writeResponse(socket.getOutputStream(), 200, response)
        } catch (rejected: McpRequestRejectedException) {
            writeResponse(socket.getOutputStream(), rejected.status, JSONObject().put("error", rejected.message))
        } catch (error: Exception) {
            writeResponse(socket.getOutputStream(), 400, rpcError(null, -32600, error.message ?: "Invalid request"))
        }
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val response = JSONObject().put("jsonrpc", "2.0").put("id", id)
        return try {
            when (request.optString("method")) {
                "initialize" -> response.put("result", initializeResult())
                "ping" -> response.put("result", JSONObject())
                "tools/list" -> response.put("result", JSONObject().put("tools", tools()))
                "tools/call" -> response.put("result", callTool(request.optJSONObject("params") ?: JSONObject()))
                else -> response.put("error", error(-32601, "Method not found"))
            }
        } catch (failure: Exception) {
            Log.e(TAG, "MCP dispatch failed", failure)
            response.put("error", error(-32603, failure.message ?: "Internal error"))
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", "2024-11-05")
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "opendroid").put("version", BuildConfig.VERSION_NAME))

    private fun tools(): JSONArray = JSONArray()
        .put(tool("device_info", "Return OpenDroid and privileged-backend status", JSONObject()))
        .put(tool("list_actions", "List actions available to OpenDroid", JSONObject()))
        .put(tool("execute_action", "Execute an existing OpenDroid action", objectSchema(
            JSONObject().put("action", stringSchema()).put("params", JSONObject().put("type", "object")),
            "action")))
        .put(tool("run_privileged_command", "Run a command through Shizuku, root, or app shell", objectSchema(
            JSONObject().put("command", stringSchema()), "command")))
        .put(tool("mcp_list_configs", "List persisted MCP endpoint configurations", JSONObject()))
        .put(tool("mcp_configure", "Create or update a persisted MCP endpoint configuration", objectSchema(
            JSONObject().put("name", stringSchema())
                .put("url", stringSchema())
                .put("enabled", JSONObject().put("type", "boolean"))
                .put("headers", JSONObject().put("type", "object")), "name", "url")))
        .put(tool("mcp_remove_config", "Remove a persisted MCP endpoint configuration", objectSchema(
            JSONObject().put("name", stringSchema()), "name")))
        .put(tool("terminal_create", "Create a persistent shell session", JSONObject()))
        .put(tool("terminal_write", "Write a command to a persistent shell session", objectSchema(
            JSONObject().put("sessionId", stringSchema()).put("command", stringSchema()), "sessionId", "command")))
        .put(tool("terminal_read", "Read currently available output from a terminal session", objectSchema(
            JSONObject().put("sessionId", stringSchema()), "sessionId")))
        .put(tool("terminal_list", "List persistent terminal sessions", JSONObject()))
        .put(tool("terminal_close", "Close a persistent terminal session", objectSchema(
            JSONObject().put("sessionId", stringSchema()), "sessionId")))

    private fun objectSchema(properties: JSONObject, vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required.toList()))

    private fun stringSchema(): JSONObject = JSONObject().put("type", "string")

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", schema)

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val text = when (name) {
            "device_info" -> JSONObject()
                .put("package", context.packageName)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("command", JSONObject(commandExecutor.status()))
                .put("mcpPort", PORT)
                .toString()
            "list_actions" -> JSONObject().put("actions", JSONArray(actionDispatcher.getAllRegisteredActions())).toString()
            "execute_action" -> executeAction(arguments)
            "run_privileged_command" -> executeCommand(arguments)
            "mcp_list_configs" -> JSONObject().put("configs", configsJson()).toString()
            "mcp_configure" -> configureMcp(arguments)
            "mcp_remove_config" -> removeMcp(arguments)
            "terminal_create" -> terminalCreate()
            "terminal_write" -> terminalWrite(arguments)
            "terminal_read" -> terminalRead(arguments)
            "terminal_list" -> terminalList()
            "terminal_close" -> terminalClose(arguments)
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
        return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    }

    private fun executeAction(arguments: JSONObject): String {
        val action = arguments.optString("action")
        require(action.isNotBlank()) { "action is required" }
        val rawParams = arguments.optJSONObject("params") ?: JSONObject()
        val params = rawParams.keys().asSequence().associateWith { key -> rawParams.optString(key) }
        return resultToJson(runBlocking { actionDispatcher.execute(action, params, context) })
    }

    private fun executeCommand(arguments: JSONObject): String {
        val command = arguments.optString("command")
        require(command.isNotBlank()) { "command is required" }
        val result = runBlocking { commandExecutor.execute(command) }
        return JSONObject().put("backend", result.backend.name).put("exitCode", result.exitCode)
            .put("stdout", result.stdout).put("stderr", result.stderr).toString()
    }

    private fun configsJson(): JSONArray = JSONArray().apply {
        configStore.list().forEach { config ->
            put(JSONObject().put("name", config.name).put("url", config.url)
                .put("enabled", config.enabled).put("headers", JSONObject(config.headers)))
        }
    }

    private fun configureMcp(arguments: JSONObject): String {
        val headers = arguments.optJSONObject("headers") ?: JSONObject()
        configStore.upsert(McpEndpointConfig(
            name = arguments.optString("name"),
            url = arguments.optString("url"),
            enabled = arguments.optBoolean("enabled", true),
            headers = headers.keys().asSequence().associateWith { key -> headers.optString(key) }
        ))
        return JSONObject().put("saved", true).put("name", arguments.optString("name")).toString()
    }

    private fun removeMcp(arguments: JSONObject): String {
        val name = arguments.optString("name")
        require(name.isNotBlank()) { "name is required" }
        configStore.remove(name)
        return JSONObject().put("removed", true).put("name", name).toString()
    }

    private fun terminalCreate(): String = JSONObject(runBlocking { terminalManager.create().toMap() }).toString()

    private fun terminalWrite(arguments: JSONObject): String {
        runBlocking { terminalManager.write(arguments.optString("sessionId"), arguments.optString("command")) }
        return JSONObject().put("written", true).toString()
    }

    private fun terminalRead(arguments: JSONObject): String =
        JSONObject().put("output", runBlocking { terminalManager.read(arguments.optString("sessionId")) }).toString()

    private fun terminalList(): String = JSONObject().put("sessions", JSONArray(terminalManager.list().map { it.toMap() })).toString()

    private fun terminalClose(arguments: JSONObject): String {
        terminalManager.close(arguments.optString("sessionId"))
        return JSONObject().put("closed", true).toString()
    }

    private fun TerminalSessionInfo.toMap(): Map<String, String> = mapOf("id" to id, "backend" to backend.name)

    private fun resultToJson(result: ActionResult): String = JSONObject()
        .put("success", result.success).put("data", result.data).put("error", result.error).toString()

    private fun error(code: Int, message: String): JSONObject = JSONObject().put("code", code).put("message", message)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", error(code, message))

    private fun writeResponse(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 408 -> "Request Timeout"
            431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeEmptyResponse(output: OutputStream) {
        output.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    /**
     * Compare digests so unequal lengths cannot short-circuit before a constant-time compare.
     * Token values are never logged.
     */
    private fun tokensMatch(provided: String, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val providedHash = digest.digest(provided.toByteArray(StandardCharsets.UTF_8))
        digest.reset()
        val expectedHash = digest.digest(expected.toByteArray(StandardCharsets.UTF_8))
        return MessageDigest.isEqual(providedHash, expectedHash)
    }

    private companion object {
        const val TAG = "McpServer"
        const val LOOPBACK = "127.0.0.1"
        const val PORT = 8765
        const val BACKLOG = 8
        const val MAX_REQUEST_BYTES = 1_048_576
        const val REQUEST_TIMEOUT_MS = 15_000
        const val REQUEST_DEADLINE_MS = 30_000L
        const val WORKERS = 4
        const val QUEUED_CONNECTIONS = 8
        const val JOIN_TIMEOUT_MS = 2_000L
        const val THREAD_NAME = "OpenDroid-MCP"
    }
}
