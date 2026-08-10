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
        try {
            ServerSocket(PORT, BACKLOG, InetAddress.getByName(LOOPBACK)).use { socket ->
                serverSocket = socket
                while (running.get()) {
                    try {
                        socket.accept().use(::handle)
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
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = REQUEST_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readAsciiLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
        }

        if (!tokensMatch(headers["x-opendroid-token"].orEmpty(), configStore.accessToken())) {
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
            writeResponse(socket.getOutputStream(), 200, dispatch(request))
        } catch (error: Exception) {
            writeResponse(socket.getOutputStream(), 400, rpcError(null, -32600, error.message ?: "Invalid request"))
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ByteArray(MAX_HEADER_LINE_BYTES)
        var count = 0
        while (count < bytes.size) {
            val value = input.read()
            if (value < 0) return if (count == 0) null else String(bytes, 0, count, StandardCharsets.US_ASCII)
            if (value == '\n'.code) {
                val end = if (count > 0 && bytes[count - 1] == '\r'.code.toByte()) count - 1 else count
                return String(bytes, 0, end, StandardCharsets.US_ASCII)
            }
            bytes[count++] = value.toByte()
        }
        throw IllegalArgumentException("HTTP header line is too long")
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
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
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
        const val MAX_HEADER_LINE_BYTES = 8192
        const val REQUEST_TIMEOUT_MS = 15_000
        const val JOIN_TIMEOUT_MS = 2_000L
        const val THREAD_NAME = "OpenDroid-MCP"
    }
}
