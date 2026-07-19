package com.apk.claw.android.tool.mcp

import android.util.Log
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.SecretRedactor
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP(Model Context Protocol)Client —— 连接外部 MCP server,动态发现并调用其工具。
 *
 * MCP 是 Anthropic 开放的 JSON-RPC 2.0 协议,让 AI Agent 连接任意识别 MCP 协议的工具服务器,
 * 复用整个 MCP 生态(文件系统/GitHub/Slack/数据库/... 数百个 server)。
 *
 * 支持两种传输:
 *  1. **stdio**:启动子进程(npx/uvx/node/python),通过 stdin/stdout 交换 JSON-RPC 消息
 *  2. **SSE**:连接 HTTP SSE 端点,通过 POST 发请求、SSE 流接收响应
 *
 * 生命周期:
 *  1. [connect] → 发 `initialize` 握手 → 发 `tools/list` 发现工具 → 注册到 ToolRegistry
 *  2. 工具被调用时 → [callTool] → 发 `tools/call` JSON-RPC → 返回结果
 *  3. [disconnect] → 关闭连接,注销工具
 *
 * 安全模型:
 *  - MCP server 是外部进程,其工具能力由 server 定义,Octopus 无法预审 → 登记为 HIGH 风险
 *  - 不可信来源走来源闸门弹审批 + 全程审计
 *  - MCP server 返回的结果过 SecretRedactor 脱敏
 *  - stdio 模式:子进程在 App UID 下运行,受 Android 沙箱约束
 *  - SSE 模式:URL 过 UrlGuard(由调用方 McpClientTool 完成)
 *  - 工具调用超时 60s,防止恶意 server 挂起
 *
 * 协议参考: https://spec.modelcontextprotocol.io/specification/
 */
class McpClient(
    private val serverId: String,
    private val transport: Transport,
    private val clientName: String = "octopus-mobile",
    private val clientVersion: String = "1.0",
) {

    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val CALL_TIMEOUT_MS = 60_000L
        private const val INIT_TIMEOUT_MS = 15_000L
        private val idCounter = AtomicLong(1)
    }

    /** MCP 传输层抽象。 */
    sealed interface Transport {
        data class Stdio(val command: List<String>, val env: Map<String, String> = emptyMap()) : Transport
        data class Sse(val url: String, val headers: Map<String, String> = emptyMap()) : Transport
    }

    /** MCP 工具定义(server 返回的 tools/list 结果)。 */
    data class McpToolInfo(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,  // JSON Schema
    )

    @Volatile
    private var connected = false

    /** 已发现的工具(name → info)。 */
    private val tools = ConcurrentHashMap<String, McpToolInfo>()

    /** stdio 模式的子进程。 */
    private var process: Process? = null

    /** stdio 模式的 stdin writer。 */
    private var processWriter: OutputStreamWriter? = null

    /** SSE 模式的 EventSource。 */
    private var eventSource: EventSource? = null

    /** SSE 模式的 OkHttp client。 */
    private var httpClient: OkHttpClient? = null

    /** 响应队列:JSON-RPC id → 等待响应的 queue。 */
    private val pendingResponses = ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>()

    /** SSE 模式的 POST 端点(从 SSE endpoint 事件获取)。 */
    @Volatile
    private var ssePostEndpoint: String? = null

    /** 连接状态回调。 */
    var onStatusChange: ((Boolean) -> Unit)? = null

    val isConnected: Boolean get() = connected
    val serverName: String get() = serverId
    val discoveredTools: Map<String, McpToolInfo> get() = tools.toMap()

    /**
     * 连接 MCP server:握手 + 发现工具。
     * @return 成功返回发现的工具列表,失败返回错误
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun connect(): Result<List<McpToolInfo>> {
        if (connected) return Result.success(tools.values.toList())

        return try {
            when (transport) {
                is Transport.Stdio -> connectStdio(transport)
                is Transport.Sse -> connectSse(transport)
            }

            // 握手:initialize
            val initResult = sendRequest("initialize", mapOf(
                "protocolVersion" to PROTOCOL_VERSION,
                "capabilities" to JsonObject(),
                "clientInfo" to mapOf("name" to clientName, "version" to clientVersion),
            ), INIT_TIMEOUT_MS)

            if (initResult.isFailure) {
                disconnect()
                return Result.failure(initResult.exceptionOrNull()!!)
            }
            val initResp = initResult.getOrThrow()
            // 发 initialized 通知
            sendNotification("notifications/initialized", JsonObject())
            Log.i(TAG, "[$serverId] MCP initialized: ${initResp.get("serverInfo")}")

            // 发现工具:tools/list
            val toolsResult = sendRequest("tools/list", JsonObject(), INIT_TIMEOUT_MS)
            if (toolsResult.isFailure) {
                disconnect()
                return Result.failure(toolsResult.exceptionOrNull()!!)
            }
            val toolsResp = toolsResult.getOrThrow()
            val toolsArray = toolsResp.getAsJsonArray("tools")
            if (toolsArray != null) {
                toolsArray.forEach { elem ->
                    val obj = elem.asJsonObject
                    val name = obj.get("name")?.asString ?: return@forEach
                    val desc = obj.get("description")?.asString ?: ""
                    val schema = obj.getAsJsonObject("inputSchema") ?: JsonObject()
                    tools[name] = McpToolInfo(name, desc, schema)
                }
            }
            connected = true
            onStatusChange?.invoke(true)
            Log.i(TAG, "[$serverId] Discovered ${tools.size} tools: ${tools.keys}")
            Result.success(tools.values.toList())
        } catch (e: Exception) {
            Log.e(TAG, "[$serverId] connect failed", e)
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * 调用 MCP server 上的工具。
     * @param toolName 工具名(server 定义的)
     * @param arguments 工具参数(key-value)
     * @return 工具执行结果
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun callTool(toolName: String, arguments: Map<String, Any>): ToolResult {
        if (!connected) {
            return ToolResult.error("MCP server [$serverId] 未连接", ToolErr.INTERNAL)
        }

        val args = JsonObject()
        for ((k, v) in arguments) {
            args.add(k, Gson().toJsonTree(v))
        }

        val params = JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", args)
        }

        val result = sendRequest("tools/call", params, CALL_TIMEOUT_MS)
        return if (result.isSuccess) {
            val resp = result.getOrThrow()
            val content = resp.getAsJsonArray("content")
            if (content == null) {
                ToolResult.success(resp.toString())
            } else {
                val sb = StringBuilder()
                content.forEach { item ->
                    val itemObj = item.asJsonObject
                    val type = itemObj.get("type")?.asString
                    if (type == "text") {
                        val text = itemObj.get("text")?.asString ?: ""
                        // 脱敏:防止 MCP server 返回的文本含密钥/token
                        sb.append(SecretRedactor.redact(text) ?: text)
                    }
                }
                val isError = resp.get("isError")?.asBoolean == true
                if (isError) {
                    ToolResult.error(sb.toString(), ToolErr.UPSTREAM)
                } else {
                    ToolResult.success(sb.toString())
                }
            }
        } else {
            ToolResult.error(
                "MCP 工具调用失败 [${serverId}/$toolName]: ${result.exceptionOrNull()?.message}",
                ToolErr.UPSTREAM,
            )
        }
    }

    /** 断开连接,清理资源。 */
    fun disconnect() {
        connected = false
        tools.clear()
        pendingResponses.clear()
        onStatusChange?.invoke(false)

        processWriter?.runCatching { close() }
        process?.runCatching { destroyForcibly() }
        processWriter = null
        process = null

        eventSource?.runCatching { cancel() }
        eventSource = null
        httpClient?.runCatching { dispatcher.executorService.shutdown() }
        httpClient = null
        ssePostEndpoint = null

        Log.i(TAG, "[$serverId] disconnected")
    }

    // ── stdio 传输 ──────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun connectStdio(transport: Transport.Stdio) {
        val pb = ProcessBuilder(transport.command)
        transport.env.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(false)
        val proc = pb.start()
        process = proc
        processWriter = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)

        // 读 stdout 行,每行是一个 JSON-RPC 响应/通知
        Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        parseAndDispatch(line!!)
                    }
                }
            } catch (_: Exception) {
                // 进程结束或读异常
            }
            connected = false
            onStatusChange?.invoke(false)
        }.apply { isDaemon = true; name = "mcp-stdio-$serverId" }.start()
    }

    // ── SSE 传输 ────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun connectSse(transport: Transport.Sse) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // SSE 长连接
            .build()
        httpClient = client

        val reqBuilder = Request.Builder().url(transport.url).get()
        transport.headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(reqBuilder.build(), object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // SSE endpoint 事件:拿到 POST 端点
                if (type == "endpoint") {
                    ssePostEndpoint = resolveUrl(transport.url, data)
                    return
                }
                parseAndDispatch(data)
            }

            override fun onClosed(eventSource: EventSource) {
                connected = false
                onStatusChange?.invoke(false)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e(TAG, "[$serverId] SSE failure: ${t?.message}")
                connected = false
                onStatusChange?.invoke(false)
            }
        })
        // 等待 endpoint 事件
        Thread.sleep(500)  // 给 SSE 一点时间建立连接
    }

    /** 解析 SSE 端点 URL(可能是相对路径)。 */
    private fun resolveUrl(baseUrl: String, endpoint: String): String {
        return try {
            if (endpoint.startsWith("http")) endpoint
            else {
                val base = java.net.URI(baseUrl)
                base.resolve(endpoint).toString()
            }
        } catch (_: Exception) {
            endpoint
        }
    }

    // ── JSON-RPC ────────────────────────────────────────────────────────

    /**
     * 发送 JSON-RPC 请求,等待对应 id 的响应。
     * @param method JSON-RPC method
     * @param params 请求参数(JsonObject)
     * @param timeoutMs 超时
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun sendRequest(
        method: String,
        params: Any,
        timeoutMs: Long,
    ): Result<JsonObject> {
        val id = idCounter.getAndIncrement()
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            when (params) {
                is JsonObject -> add("params", params)
                is Map<*, *> -> add("params", Gson().toJsonTree(params))
                else -> add("params", Gson().toJsonTree(params))
            }
        }

        val queue = LinkedBlockingQueue<JsonObject>(1)
        pendingResponses[id] = queue

        return try {
            sendJson(request)
            val resp = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: return Result.failure(IOException("MCP 请求超时(>${timeoutMs}ms): $method"))

            // 检查错误
            val error = resp.getAsJsonObject("error")
            if (error != null) {
                val msg = error.get("message")?.asString ?: "未知错误"
                val code = error.get("code")?.asInt ?: -1
                return Result.failure(IOException("MCP 错误[$code]: $msg (method=$method)"))
            }
            Result.success(resp.getAsJsonObject("result") ?: JsonObject())
        } catch (e: InterruptedException) {
            Result.failure(IOException("MCP 请求被中断: $method"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingResponses.remove(id)
        }
    }

    /** 发送 JSON-RPC 通知(无 id,无响应)。 */
    private fun sendNotification(method: String, params: Any) {
        val notification = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            when (params) {
                is JsonObject -> add("params", params)
                else -> add("params", Gson().toJsonTree(params))
            }
        }
        sendJson(notification)
    }

    /** 发送 JSON 消息到传输层。 */
    @Suppress("TooGenericExceptionCaught")
    private fun sendJson(json: JsonObject) {
        val str = json.toString()
        try {
            when {
                processWriter != null -> {
                    // stdio: 写一行 JSON + 换行
                    synchronized(processWriter!!) {
                        processWriter!!.write(str)
                        processWriter!!.write("\n")
                        processWriter!!.flush()
                    }
                }
                ssePostEndpoint != null && httpClient != null -> {
                    // SSE: POST 到 endpoint
                    val mediaType = "application/json".toMediaTypeOrNull()
                    val body = str.toRequestBody(mediaType)
                    val req = Request.Builder().url(ssePostEndpoint!!).post(body).build()
                    httpClient!!.newCall(req).execute().use { /* 忽略响应体,SSE 流会带回 */ }
                }
                else -> Log.w(TAG, "[$serverId] No transport available to send: $str")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$serverId] sendJson failed", e)
        }
    }

    /** 解析收到的 JSON-RPC 消息,分发到响应 queue 或忽略通知。 */
    @Suppress("TooGenericExceptionCaught")
    private fun parseAndDispatch(data: String) {
        try {
            val json = JsonParser.parseString(data).asJsonObject
            val id = json.get("id")?.asLong ?: return  // 通知无 id,忽略
            val queue = pendingResponses[id] ?: return
            queue.offer(json)
        } catch (e: Exception) {
            Log.w(TAG, "[$serverId] Failed to parse: $data", e)
        }
    }
}
