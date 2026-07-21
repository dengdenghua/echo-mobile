package com.apk.claw.android.tentacle

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tentacle 通路状态.
 *
 * 状态机:
 * ```
 * DISCONNECTED ──connect()──> CONNECTING ──device/welcome──> ONLINE
 *        ↑                          │                            │
 *        │                          │ (失败/关闭)                 │
 *        │                          ↓                            │
 *        └──────────────────── RECONNECTING <─── 3 失败/超时 ─────┘
 *                                      │
 *                                      └─ 3 次失败 ─> DISCONNECTED (终态)
 * ```
 */
enum class TentacleState {
    /** 未连接 / 已断开(终态或初始态). */
    DISCONNECTED,

    /** 正在建立 WebSocket 连接, 或已 open 但未收到 `device/welcome`. */
    CONNECTING,

    /** 已完成握手, 收到 `device/welcome`, 可收发业务帧. */
    ONLINE,

    /** 连接异常断开, 正在指数退避重连. */
    RECONNECTING,
}

/**
 * Tentacle WebSocket 客户端 —— 连接母本 Runtime 的 WS 通路.
 *
 * 与 `octopus_mobile.OctopusMobileClient` 的差异(刻意独立, 不互改):
 *  - 协议帧用 `type` 字段(`device/hello` / `device/welcome` / `tool/execute` / `tool/result`),
 *    而非既有客户端的 `method`+`jsonrpc` 风格 —— 对齐 task spec 中母本 ws_server 的 `type` 协议.
 *  - 状态机 4 态: [TentacleState.DISCONNECTED] / [TentacleState.CONNECTING] /
 *    [TentacleState.ONLINE] / [TentacleState.RECONNECTING].
 *  - 重连策略: 指数退避(1s 起步, 30s 封顶), **3 次失败后停止**(不再无限重试).
 *  - 工具调用: 通过 [setToolCallHandler] 注册同步回调, 收到 `tool/execute` 后异步执行并回 `tool/result`.
 *
 * ## 与 ToolCallDispatcher 的契约(集成阶段接线)
 *
 * 本类不直接 import `octopus_mobile.ToolCallDispatcher`. 集成阶段应当:
 *
 * ```
 * val client = OctopusMobileClient()
 * client.setToolCallHandler { toolName, params ->
 *     // 远程母本下发的 tool/execute 标记为"不可信来源", 必经来源闸门 R2/R3
 *     com.apk.claw.android.octopus_mobile.ToolCallDispatcher.withUntrustedSource {
 *         ToolRegistry.getInstance().executeTool(toolName, params)
 *     }
 * }
 * ```
 *
 * 即: 由调用方决定如何把 `(toolName, params) -> ToolResult` 路由到本地工具体系.
 * 本类只负责传输 + 协议帧封装.
 *
 * ## 帧格式(全部 JSON 文本帧, `type` 字段区分)
 *
 * | 方向 | type              | 字段                                              |
 * |------|-------------------|---------------------------------------------------|
 * | 出   | `device/hello`    | `device_id` `capabilities` `auth_token`           |
 * | 入   | `device/welcome`  | `session_id` `server_version`                     |
 * | 出   | `device/heartbeat`| `ts`                                              |
 * | 入   | `device/heartbeat_ack` | `server_ts`                                  |
 * | 入   | `tool/execute`     | `call_id` `tool_name` `params`                    |
 * | 出   | `tool/result`     | `call_id` `result` `error` `audit_chain_hash`     |
 */
class OctopusMobileClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val tag = "TentacleClient"

    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var state: TentacleState = TentacleState.DISCONNECTED

    private val _stateFlow = MutableStateFlow(state)
    /** 状态流, 供 UI / TentacleManager 观察. */
    val stateFlow: StateFlow<TentacleState> = _stateFlow.asStateFlow()

    /** 重连尝试次数(原子计数). */
    private val reconnectAttempts = AtomicInteger(0)

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var userInitiatedDisconnect: Boolean = false

    /** 当前连接的 URL + token, 重连时复用. */
    @Volatile
    private var connectUrl: String = ""

    @Volatile
    private var connectToken: String = ""

    /** 工具调用处理器 —— 收到母本 `tool/execute` 后调用, 同步返回 [ToolResult]. */
    @Volatile
    private var toolCallHandler: ((toolName: String, params: Map<String, Any>) -> ToolResult)? = null

    /** 收到 `device/welcome` 后回调(供 DeviceRegistration 注册 session_id 等). */
    @Volatile
    var onWelcome: ((sessionId: String, serverVersion: String) -> Unit)? = null

    /** WebSocket 打开后回调(供 DeviceRegistration 立即发送 device/hello). */
    @Volatile
    var onWsOpen: (() -> Unit)? = null

    /** 收到 `device/heartbeat_ack` 后回调(供 DeviceRegistration 重置未收 ack 计数). */
    @Volatile
    var onHeartbeatAck: ((serverTs: Long) -> Unit)? = null

    /** 状态变化回调(轻量通知; 重状态观察用 [stateFlow]). */
    @Volatile
    var onStateChanged: ((TentacleState) -> Unit)? = null

    // ── 公共 API ──────────────────────────────────────────────────────────

    /**
     * 启动客户端 —— 建立 WebSocket 连接.
     *
     * 状态流转: DISCONNECTED → CONNECTING → (收到 welcome) → ONLINE.
     *
     * @param url       母本 Runtime WS URL, 例 `wss://runtime.example.com/ws`.
     * @param authToken 母本认证 token(可空, 仅 loopback 开发时).
     */
    fun connect(url: String, authToken: String) {
        if (url.isBlank()) {
            XLog.w(tag, "connect rejected: blank url (LOCAL_ONLY mode)")
            return
        }
        connectUrl = url
        connectToken = authToken
        userInitiatedDisconnect = false
        reconnectAttempts.set(0)
        openWebSocket()
    }

    /**
     * 主动断开 —— 用户触发, 不重连.
     *
     * 状态 → DISCONNECTED.
     */
    fun disconnect() {
        XLog.i(tag, "disconnect: user initiated")
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts.set(0)
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        setState(TentacleState.DISCONNECTED)
    }

    /**
     * 发送一帧 JSON 消息.
     *
     * @return true 表示已写入 OS 缓冲(OkHttp 异步发送); false 表示未连接.
     */
    fun send(frame: JsonObject): Boolean {
        val ws = webSocket ?: run {
            XLog.w(tag, "send skipped: not connected (type=${frame.get("type")})")
            return false
        }
        return try {
            ws.send(frame.toString())
        } catch (e: Exception) {
            XLog.w(tag, "send failed: ${e.message}")
            false
        }
    }

    /**
     * 注册工具调用处理器.
     *
     * 收到母本 `tool/execute` 帧后, 在 IO 协程中同步调用 [handler], 等其返回 [ToolResult]
     * 后回 `tool/result` 帧. [handler] 应当是非阻塞的 —— 若需做长耗时操作(如截图/IO),
     * 内部自行切线程并阻塞等待结果.
     *
     * 集成阶段应当把 [handler] 实现为:
     *
     * ```
     * client.setToolCallHandler { toolName, params ->
     *     ToolCallDispatcher.withUntrustedSource {
     *         ToolRegistry.getInstance().executeTool(toolName, params)
     *     }
     * }
     * ```
     */
    fun setToolCallHandler(handler: (toolName: String, params: Map<String, Any>) -> ToolResult) {
        this.toolCallHandler = handler
    }

    /** 当前状态(同步读, 与 [stateFlow] 一致). */
    fun currentState(): TentacleState = state

    // ── 内部: 连接 + 消息分发 ─────────────────────────────────────────────

    private fun openWebSocket() {
        setState(TentacleState.CONNECTING)
        XLog.i(tag, "connecting to $connectUrl")

        val request = Request.Builder()
            .url(connectUrl)
            .apply {
                if (connectToken.isNotBlank()) {
                    header("Authorization", "Bearer $connectToken")
                }
            }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                XLog.i(tag, "websocket opened (waiting device/welcome)")
                this@OctopusMobileClient.webSocket = webSocket
                // 状态保持 CONNECTING, 等 device/welcome 才转 ONLINE
                try {
                    onWsOpen?.invoke()
                } catch (e: Exception) {
                    XLog.w(tag, "onWsOpen callback error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                XLog.i(tag, "websocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                XLog.i(tag, "websocket closed code=$code reason=$reason")
                this@OctopusMobileClient.webSocket = null
                handleDisconnect("closed code=$code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                XLog.w(tag, "websocket failure: ${t.message}")
                this@OctopusMobileClient.webSocket = null
                handleDisconnect("failure: ${t.message}")
            }
        }
        httpClient.newWebSocket(request, listener)
    }

    /**
     * 解析入站消息并分发.
     *
     * 仅处理 `type` 字段路由的协议帧. 未知类型忽略(不报错, 兼容母本未来扩展).
     */
    internal fun handleIncomingMessage(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject
            val type = root.get("type")?.asString ?: return
            when (type) {
                "device/welcome" -> handleWelcome(root)
                "device/heartbeat_ack" -> handleHeartbeatAck(root)
                "tool/execute" -> handleToolExecute(root)
                else -> XLog.d(tag, "unhandled frame type=$type")
            }
        } catch (e: Exception) {
            XLog.w(tag, "handleIncomingMessage error: ${e.message}")
        }
    }

    private fun handleWelcome(root: JsonObject) {
        val sessionId = root.get("session_id")?.asString ?: ""
        val serverVersion = root.get("server_version")?.asString ?: ""
        XLog.i(tag, "device/welcome received: session=$sessionId server=$serverVersion")
        reconnectAttempts.set(0)
        setState(TentacleState.ONLINE)
        onWelcome?.invoke(sessionId, serverVersion)
    }

    private fun handleHeartbeatAck(root: JsonObject) {
        val serverTs = root.get("server_ts")?.asLong ?: 0L
        onHeartbeatAck?.invoke(serverTs)
    }

    private fun handleToolExecute(root: JsonObject) {
        // 安全:握手确认(ONLINE)前不接受 tool/execute —— 与既有客户端一致(R3 闸门).
        if (state != TentacleState.ONLINE) {
            XLog.w(tag, "tool/execute rejected before ONLINE (state=$state)")
            return
        }
        val callId = root.get("call_id")?.asString ?: return
        val toolName = root.get("tool_name")?.asString ?: return
        val params = parseParams(root.get("params"))

        XLog.d(tag, "tool/execute: call=$callId tool=$toolName")
        scope.launch {
            val result = try {
                toolCallHandler?.invoke(toolName, params)
                    ?: ToolResult.error("No tool call handler registered")
            } catch (e: Exception) {
                XLog.w(tag, "tool handler exception: ${e.message}")
                ToolResult.error("Handler error: ${e.message}")
            }
            sendToolResult(callId, result)
        }
    }

    /**
     * 发送 `tool/result` 帧.
     *
     * 帧格式: `{type:"tool/result", call_id, result, error, audit_chain_hash}`.
     */
    private fun sendToolResult(callId: String, result: ToolResult) {
        val frame = JsonObject().apply {
            addProperty("type", "tool/result")
            addProperty("call_id", callId)
            if (result.isSuccess) {
                addProperty("result", result.data ?: "")
                addProperty("error", "")
            } else {
                addProperty("result", "")
                addProperty("error", result.error ?: "Unknown error")
            }
            // audit_chain_hash: 集成阶段由 ToolAuditLog 提供; 此处占位空串保持字段存在.
            addProperty("audit_chain_hash", "")
        }
        send(frame)
    }

    // ── 内部: 重连 ────────────────────────────────────────────────────────

    private fun handleDisconnect(reason: String) {
        if (userInitiatedDisconnect) {
            setState(TentacleState.DISCONNECTED)
            return
        }
        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            XLog.w(tag, "giving up after $attempts failed reconnects: $reason")
            setState(TentacleState.DISCONNECTED)
            return
        }
        setState(TentacleState.RECONNECTING)
        val backoffMs = computeBackoff(attempts)
        XLog.i(tag, "reconnect in ${backoffMs}ms (attempt $attempts/$MAX_RECONNECT_ATTEMPTS): $reason")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (isActive && !userInitiatedDisconnect) {
                openWebSocket()
            }
        }
    }

    /**
     * 指数退避: 1s, 2s, 4s, ... 封顶 30s. Full Jitter +20% 随机扰动避免惊群.
     */
    internal fun computeBackoff(attempt: Int): Long {
        val exp = INITIAL_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 30))
        val capped = minOf(exp, MAX_BACKOFF_MS)
        val jitter = (Math.random() * (INITIAL_BACKOFF_MS / 2)).toLong()
        return (capped + jitter).coerceAtMost(MAX_BACKOFF_MS + INITIAL_BACKOFF_MS)
    }

    private fun setState(newState: TentacleState) {
        if (state == newState) return
        XLog.i(tag, "state: $state → $newState")
        state = newState
        _stateFlow.value = newState
        onStateChanged?.invoke(newState)
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private fun parseParams(element: com.google.gson.JsonElement?): Map<String, Any> {
        if (element == null || !element.isJsonObject) return emptyMap()
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        return try {
            gson.fromJson(element, mapType) ?: emptyMap()
        } catch (e: Exception) {
            XLog.w(tag, "parseParams failed: ${e.message}")
            emptyMap()
        }
    }

    companion object {
        /** 重连最大尝试次数. 超过后进入 DISCONNECTED 终态. */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** 初始退避(1s). */
        const val INITIAL_BACKOFF_MS = 1_000L

        /** 最大退避(30s). */
        const val MAX_BACKOFF_MS = 30_000L

        private fun defaultHttpClient(): OkHttpClient = OctoHttp.shared.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接, 无读超时
            .build()
    }
}
