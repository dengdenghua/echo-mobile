package com.apk.claw.android.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Server —— 基于 NanoHTTPD 的 HTTP 传输层实现。
 *
 * 传输模式:Streamable HTTP(JSON-RPC 2.0 over HTTP POST)。
 * 这是 MCP 协议规范支持的官方传输方式之一,无需 WebSocket 即可工作。
 *
 * 端点:`POST /mcp`(默认端口 [DEFAULT_PORT]=9528)。
 *   - 每条 HTTP 请求携带一个 JSON-RPC 请求体,服务端处理后返回 JSON-RPC 响应体。
 *   - 通知(id=null):返回 HTTP 202(无 body),客户端不需等待响应。
 *   - 客户端 `Accept: application/json` → HTTP 200 + JSON body
 *   - 客户端 `Accept: text/event-stream` → HTTP 200 + 单个 SSE 事件包同一 JSON(兼容
 *     要求 SSE 的客户端)。本实现不主动推送 server→client 通知。
 *
 * 会话:每个客户端应使用独立的 sessionId,通过查询参数 `?sessionId=<uuid>` 标识。
 * 服务端用 [sessions] map 持有每个 session 的 [McpSession] 状态。
 *
 * 选型说明:为什么不使用 WebSocket?
 *  - 项目 build.gradle.kts 仅依赖 `org.nanohttpd:nanohttpd:2.3.1` core artifact,
 *    不含 `nanohttpd-websocket`(NanoWSD)。
 *  - 不能修改 build.gradle.kts 加新依赖,故选择 HTTP long-polling 模式。
 *  - HTTP 模式足以覆盖 Claude Desktop / Cursor / OpenClaw 等主流 MCP 客户端的远程连接场景。
 *
 * 安全:
 *  - MCP server 暂不强制 token 鉴权(由 [McpServerBootstrap] 在集成时按需追加,
 *    例如复用 ConfigServer 的 Bearer token 机制)。
 *  - 高危工具由 [JsonRpcDispatcher] 走 ApprovalGate 审批。
 */
class McpServer(
    private val serverName: String = "octopus-mobile-mcp",
    private val serverVersion: String = "0.1.0",
    port: Int = DEFAULT_PORT,
    hostname: String? = null,
) : NanoHTTPD(hostname, port) {

    companion object {
        private const val TAG = "McpServer"
        const val DEFAULT_PORT = 9528
        const val MCP_PATH = "/mcp"
        private const val MIME_JSON = "application/json"
        private const val MIME_SSE = "text/event-stream"
    }

    private val gson = Gson()

    @Volatile
    private var provider: McpToolRegistryProvider = NoopMcpToolRegistryProvider()

    @Volatile
    private var approvalGate: McpApprovalGate = AutoDenyApprovalGate()

    /** 会话表:sessionId → McpSession。session 在首次请求时惰性创建。 */
    private val sessions = ConcurrentHashMap<String, McpSession>()

    // ── 公开 API ────────────────────────────────────────────

    /** 注入工具后端。未注入前调用 tools/list 返回空,tools/call 返回 "no provider"。 */
    fun setProvider(provider: McpToolRegistryProvider) {
        this.provider = provider
    }

    /** 注入审批闸门。默认为 [AutoDenyApprovalGate](拒绝所有高危工具)。 */
    fun setApprovalGate(gate: McpApprovalGate) {
        this.approvalGate = gate
    }

    // ── NanoHTTPD 入口 ──────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检
        if (session.method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        if (uri != MCP_PATH) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_JSON,
                    """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Not found: $uri"}}""",
                ),
            )
        }

        // GET /mcp:服务端不主动推送通知,告知客户端走 POST
        if (session.method == Method.GET) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED, MIME_JSON,
                    """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Use POST for JSON-RPC requests"}}""",
                ),
            )
        }

        if (session.method != Method.POST) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED, MIME_JSON,
                    """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Method not allowed: ${session.method}"}}""",
                ),
            )
        }

        // 读取 body
        val body = try {
            readBody(session)
        } catch (e: Exception) {
            return jsonTextResponse(
                Response.Status.BAD_REQUEST,
                parseErrorJson("Parse error: ${e.message}"),
                session,
            )
        }

        if (body.isBlank()) {
            return jsonTextResponse(
                Response.Status.BAD_REQUEST,
                parseErrorJson("Parse error: empty body"),
                session,
            )
        }

        // 解析 sessionId(查询参数)
        val sessionId = session.parameters["sessionId"]?.firstOrNull()
            ?: session.parameters["session_id"]?.firstOrNull()
            ?: java.util.UUID.randomUUID().toString()

        val mcpSession = sessions.computeIfAbsent(sessionId) { McpSession() }

        // 构造 dispatcher(每次请求重新构造,因为 provider / gate 可能被外部更新)
        val dispatcher = JsonRpcDispatcher(
            provider = provider,
            approvalGate = approvalGate,
            serverName = serverName,
            serverVersion = serverVersion,
        )

        // 处理消息
        val responseText: String? = try {
            mcpSession.handleMessage(body, dispatcher)
        } catch (e: Exception) {
            return jsonTextResponse(
                Response.Status.INTERNAL_ERROR,
                gson.toJson(
                    JsonObject().apply {
                        addProperty("jsonrpc", "2.0")
                        add("id", com.google.gson.JsonNull.INSTANCE)
                        add("error", JsonObject().apply {
                            addProperty("code", -32603)
                            addProperty("message", "Internal error: ${e.message}")
                        })
                    },
                ),
                session,
            )
        }

        // 通知(id=null):无响应体,回 HTTP 202
        if (responseText == null) {
            return corsResponse(
                newFixedLengthResponse(Response.Status.ACCEPTED, MIME_JSON, ""),
            )
        }

        // 检查 Accept 头决定用 JSON 还是 SSE 包裹
        val accept = session.headers["accept"] ?: "application/json"
        val payload = if (accept.contains(MIME_SSE)) {
            // 单事件 SSE 流(立即关闭,不做长连接)
            "event: message\ndata: $responseText\n\n"
        } else {
            responseText
        }

        val mime = if (accept.contains(MIME_SSE)) MIME_SSE else MIME_JSON
        return jsonTextResponse(Response.Status.OK, payload, session, mime)
    }

    // ── 辅助 ────────────────────────────────────────────────

    /** 读取 POST 请求体。NanoHTTPD 要求先 parseBody,files 里的 postData 才是 JSON。 */
    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return ""
        // 必须 parseBody,否则 files 为空
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun jsonTextResponse(
        status: Response.Status,
        body: String,
        session: IHTTPSession,
        mime: String = MIME_JSON,
    ): Response {
        val r = newFixedLengthResponse(status, mime, body)
        // 标记 sessionId(便于客户端在首次请求时拿到服务端生成的 id)
        r.addHeader("Mcp-Session-Id", sessionIdOf(session))
        return corsResponse(r)
    }

    private fun sessionIdOf(session: IHTTPSession): String =
        session.parameters["sessionId"]?.firstOrNull()
            ?: session.parameters["session_id"]?.firstOrNull()
            ?: "auto"

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, Mcp-Session-Id")
        response.addHeader("Access-Control-Expose-Headers", "Mcp-Session-Id")
        // 隐藏 NanoHTTPD 默认 Server 头(含版本信息)
        response.addHeader("Server", "octopus-mcp")
        return response
    }

    private fun parseErrorJson(message: String): String {
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", com.google.gson.JsonNull.INSTANCE)
            add("error", JsonObject().apply {
                addProperty("code", -32700)
                addProperty("message", message)
            })
        }
        return gson.toJson(envelope)
    }
}
