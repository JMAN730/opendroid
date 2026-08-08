package com.opendroid.ai.core.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.base.ActionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionDispatcher: ActionDispatcher,
    private val commandExecutor: PrivilegedCommandExecutor
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        serverThread = Thread(::serve, THREAD_NAME).also { it.start() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        serverThread = null
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
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
        }

        if (!requestLine.startsWith("POST /mcp ")) {
            writeResponse(socket.getOutputStream(), 404, JSONObject().put("error", "Not found"))
            return
        }

        val length = headers["content-length"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Content-Length is required")
        require(length in 1..MAX_REQUEST_BYTES) { "Request body is too large" }
        val body = CharArray(length)
        var offset = 0
        while (offset < length) {
            val read = reader.read(body, offset, length - offset)
            if (read < 0) throw IllegalArgumentException("Incomplete request body")
            offset += read
        }

        val request = JSONObject(String(body).trim())
        if (!request.has("id")) {
            writeEmptyResponse(socket.getOutputStream())
            return
        }
        writeResponse(socket.getOutputStream(), 200, dispatch(request))
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
        } catch (error: Exception) {
            Log.e(TAG, "MCP dispatch failed", error)
            response.put("error", error(-32603, error.message ?: "Internal error"))
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", "2024-11-05")
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "opendroid").put("version", "1.0.3"))

    private fun tools(): JSONArray = JSONArray()
        .put(tool("device_info", "Return OpenDroid and privileged-backend status", JSONObject()))
        .put(tool("list_actions", "List actions available to OpenDroid", JSONObject()))
        .put(tool("execute_action", "Execute an existing OpenDroid action", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string"))
                .put("params", JSONObject().put("type", "object")))
            .put("required", JSONArray().put("action"))))
        .put(tool("run_privileged_command", "Run a command through Shizuku, root, or app shell", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("command", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("command"))))

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
                .toString()
            "list_actions" -> JSONObject()
                .put("actions", JSONArray(actionDispatcher.getAllRegisteredActions()))
                .toString()
            "execute_action" -> executeAction(arguments)
            "run_privileged_command" -> executeCommand(arguments)
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
        return JSONObject()
            .put("backend", result.backend.name)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .toString()
    }

    private fun resultToJson(result: ActionResult): String = JSONObject()
        .put("success", result.success)
        .put("data", result.data)
        .put("error", result.error)
        .toString()

    private fun error(code: Int, message: String): JSONObject = JSONObject()
        .put("code", code)
        .put("message", message)

    private fun writeResponse(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeEmptyResponse(output: OutputStream) {
        output.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    private companion object {
        const val TAG = "McpServer"
        const val LOOPBACK = "127.0.0.1"
        const val PORT = 8765
        const val BACKLOG = 8
        const val MAX_REQUEST_BYTES = 1_048_576
        const val REQUEST_TIMEOUT_MS = 15_000
        const val THREAD_NAME = "OpenDroid-MCP"
    }
}
