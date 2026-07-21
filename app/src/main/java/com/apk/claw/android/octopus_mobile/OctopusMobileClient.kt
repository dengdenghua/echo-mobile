package com.apk.claw.android.octopus_mobile
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.apk.claw.android.utils.KVUtils

/**
 * Octopus Mobile 客户端 —— Octopus Mobile 与 octopus-agent Runtime 之间的 WebSocket 通道.
 *
 * Phase 0 骨架实现（不依赖任何 Octopus Mobile 现有代码，纯 add-only 新增）：
 *  - 构造时接受 runtimeUrl，connect() 后启动 OkHttp WebSocket
 *  - send(envelope) 发 JSON 消息
 *  - 接收消息分发到 onMessage 回调
 *  - 30s 心跳由 [HeartbeatReporter] 负责
 *
 * Phase 1 实装：
 *  - 接入 ToolCallDispatcher 把 tool/execute 路由到 BaseTool
 *  - 接入 ScreenStreamer 推送屏幕变化
 *  - 接入 DualConfigWriter 双写 MMKV
 *  - executeRemoteTask: 向母体发送任务请求并等待结果
 *  - onToolExecute: 接收母体下发的 tool/execute 并执行
 *
 * 完整设计：
 *  - octopus-agent 仓库：docs/mobile/architecture.md 第 3 节
 *  - 协议：docs/mobile/protocol.md
 *  - 集成：docs/adr/008-octopus-mobile.md
 */
open class OctopusMobileClient(
    private val runtimeUrl: String,
    private val tentacleId: String,
    private val authToken: String? = null
) {
    private val tag = "OctopusMobile"

    private val gson = Gson()

    private val httpClient: OkHttpClient = run {
        val builder = OctoHttp.shared.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 长连接，无读超时
        // 证书固定：用户在设置中配置 runtime 服务器的 SPKI pin 后，
        // 即使攻击者持有受信任 CA 也无法 MITM wss:// 连接。
        val certPin = KVUtils.getRuntimeCertPin()
        if (certPin.isNotBlank()) {
            val host = try {
                java.net.URL(runtimeUrl).host
            } catch (e: Exception) { null }
            if (host != null) {
                builder.certificatePinner(
                    CertificatePinner.Builder()
                        .add(host, certPin)
                        .build()
                )
                XLog.i(tag, "certificate pinning enabled for $host")
            }
        }
        builder.build()
    }

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 通用消息监听器列表（多订阅者） */
    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** 接收消息回调（兼容旧 API，调用 addMessageListener / removeMessageListener）。
     *  无 backing field，实际由线程安全的 [messageListeners] 支持。 */
    var onMessage: ((String) -> Unit)?
        get() = messageListeners.firstOrNull()
        set(value) {
            messageListeners.clear()
            if (value != null) messageListeners.add(value)
        }

    /** 连接状态变化回调 */
    @Volatile
    var onStateChanged: ((ConnectionState) -> Unit)? = null

    /** 工具执行回调：收到母体 tool/execute 后调用 */
    @Volatile
    var onToolExecute: ((ToolCall) -> Unit)? = null

    /** PC 屏幕帧回调：收到母体 push_pc_frame 推来的二进制帧后调用（远程桌面用） */
    @Volatile
    var onPcFrame: ((ByteArray) -> Unit)? = null

    /** 配置变更回调：收到母体 config/sync_pull_response 后调用 */
    @Volatile
    var onConfigChange: ((String) -> Unit)? = null

    /** 心跳 ACK 回调：母体确认收到心跳，表明母体存活 */
    @Volatile
    var onHeartbeatAck: (() -> Unit)? = null

    /** 等待远程任务结果的 future：task_id → CompletableDeferred */
    private val pendingTasks = ConcurrentHashMap<String, CompletableDeferred<RemoteTaskResult>>()
    /** lease_id → 等待母体响应的租约申请(workspace/lease_acquire_result) */
    private val pendingLeases = ConcurrentHashMap<String, CompletableDeferred<LeaseResult>>()

    @Volatile
    private var state: ConnectionState = ConnectionState.OFFLINE

    @Volatile
    private var pendingHelloId: String? = null

    /** 握手 nonce：防止捕获的 hello_ack 被重放（服务端回传则校验，旧服务端不回传则跳过） */
    @Volatile
    private var pendingHelloNonce: String? = null

    /** 启动客户端 —— 建立 WebSocket 连接 + 发送 hello + 状态流转. */
    open fun connect() {
        val transport = MobileRuntimeSecurity.assess(
            runtimeUrl,
            allowInsecureRuntime = KVUtils.isInsecureOctopusRuntimeAllowed(),
        )
        if (!transport.allowed) {
            XLog.w(tag, "blocked runtime connection to $runtimeUrl: ${transport.reason}")
            diagnostics.onDisconnected("blocked: ${transport.reason}", System.currentTimeMillis())
            setState(ConnectionState.DISCONNECTED)
            return
        }
        if (runtimeUrl.startsWith("ws://") && !transport.localDevelopment) {
            XLog.w(tag, "⚠️ connecting to runtime over plaintext ws:// — auth token is exposed to network MITM. Use wss:// in production.")
        }
        XLog.i(tag, "connecting to $runtimeUrl as $tentacleId")

        val request = Request.Builder()
            .url(runtimeUrl)
            .also { builder ->
                val token = authToken?.takeIf { it.isNotBlank() }
                if (token != null) builder.header("Authorization", "Bearer $token")
            }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                XLog.i(tag, "websocket opened")
                this@OctopusMobileClient.webSocket = webSocket
                setState(ConnectionState.CONNECTED)

                // 发送 hello 握手 —— 附带随机 nonce 防重放
                val nonce = generateNonce()
                val hello = EnvelopeFactory.hello(
                    tentacleId = tentacleId,
                    deviceMeta = mapOf(
                        "brand" to android.os.Build.BRAND,
                        "model" to android.os.Build.MODEL,
                        "android_version" to android.os.Build.VERSION.RELEASE,
                        "sdk" to android.os.Build.VERSION.SDK_INT
                    ),
                    capabilities = emptyList(),
                    authToken = authToken,
                    nonce = nonce
                )
                pendingHelloId = hello.id
                pendingHelloNonce = nonce
                webSocket.send(hello.toJson())
                setState(ConnectionState.HELLO_SENT)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                XLog.d(tag, "received: $text")
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制帧：母体 push_pc_frame 推来的 PC 屏幕帧（远程桌面）
                if (state == ConnectionState.HELLO_SENT) {
                    XLog.w(tag, "binary frame ignored before hello acknowledgement")
                    return
                }
                onPcFrame?.invoke(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                XLog.i(tag, "websocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                XLog.i(tag, "websocket closed code=$code reason=$reason")
                this@OctopusMobileClient.webSocket = null
                // code 1000 = 正常关闭（用户主动 disconnect），不重连
                if (code == 1000) {
                    setState(ConnectionState.OFFLINE)
                } else {
                    setState(ConnectionState.DISCONNECTED)
                }
                diagnostics.onDisconnected(if (code == 1000) null else "closed code=$code", System.currentTimeMillis())
                failPendingTasks("Connection closed (code=$code)")
                if (code != 1000) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                XLog.w(tag, "websocket failure: ${t.message}")
                this@OctopusMobileClient.webSocket = null
                setState(ConnectionState.DISCONNECTED)
                diagnostics.onDisconnected("failed: ${t.message}", System.currentTimeMillis())
                failPendingTasks("Connection failed: ${t.message}")
                scheduleReconnect()
            }
        }

        httpClient.newWebSocket(request, listener)
    }

    /** 连接诊断（重连历史/失败原因/离线时长），供 UI/控制台观测断线状态。 */
    val diagnostics = ConnectionDiagnostics()

    /** 指数退避重连（基础 2s，最大 30s） */
    private val reconnectAttempts = AtomicInteger(0)
    @Volatile
    private var reconnectJob: kotlinx.coroutines.Job? = null

    private fun scheduleReconnect() {
        val attempts = reconnectAttempts.incrementAndGet()
        // 无限重试：不限制最大次数，仅限制单次退避上限。
        // 重连条件：非用户主动离线（OFFLINE）时均触发重连。
        if (state != ConnectionState.OFFLINE) {
            val baseDelay = 2000L
            val maxDelay = 30_000L
            // Full Jitter: delay = min(base * 2^(n-1), max) + random(0, base)
            // 指数封顶 30:无界重连时 attempts 可能很大,(1L shl 63+) 会移位溢出成负/乱值;
            // 30 已远超 maxDelay 所需(2^30 * 2s 远大于 30s),minOf 再 clamp,故安全。
            val expDelay = minOf(baseDelay * (1L shl (attempts - 1).coerceIn(0, 30)), maxDelay)
            val jitter = (Math.random() * baseDelay).toLong()
            val totalDelay = (expDelay + jitter).coerceAtMost(maxDelay + baseDelay)
            XLog.i(tag, "Reconnecting in ${totalDelay}ms (attempt $attempts, state=$state)")
            diagnostics.onReconnectAttempt()
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                kotlinx.coroutines.delay(totalDelay)
                if (state != ConnectionState.OFFLINE) {
                    setState(ConnectionState.CONNECTING)
                    connect()
                }
            }
        }
    }

    /**
     * 断连时把所有在途任务标记失败并清空。
     *
     * 否则旧连接的 deferred 会挂起到 60s 超时；且重连后旧连接迟到的 task/result
     * 可能错误完成新连接的任务(跨连接泄漏)。在 setState(OFFLINE) 之后调用——
     * 此时 executeRemoteTask 因状态非 ONLINE 不会再注册新任务,无竞态。
     */
    private fun failPendingTasks(reason: String) {
        if (pendingTasks.isEmpty()) return
        val deferreds = pendingTasks.values.toList()
        pendingTasks.clear()
        deferreds.forEach { it.complete(RemoteTaskResult.Failure(reason)) }
    }

    /**
     * 处理收到的消息 —— 解析 task/result / task/error / tool/execute / config/sync_pull_response.
     *
     * 使用 Gson 解析 JSON，替代之前的正则提取（正则无法正确处理嵌套 JSON、转义字符、Unicode）。
     */
    internal fun handleIncomingMessage(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject
            if (handleHelloAck(root)) {
                // Continue to listener fan-out below, but no further protocol dispatch needed.
            } else if (handleHelloError(root)) {
                return
            }
            // 母体（Python json.dumps）发的是 "method": "..."，Gson 自动处理空白
            val method = root.get("method")?.asString

            when (method) {
                "device/hello_ack", "device/registered" -> {
                    markHelloAcknowledged(method)
                }
                // 任务结果（母体返回的任务执行结果）
                "task/result" -> {
                    val taskId = root.get("task_id")?.asString ?: return
                    val response = root.get("response")?.asString ?: ""
                    val steps = root.get("steps")?.asInt ?: 0
                    val deferred = pendingTasks.remove(taskId)
                    deferred?.complete(RemoteTaskResult.Success(steps, response, TokenUsage(0, 0, 0)))
                }
                // 任务错误
                "task/error" -> {
                    val taskId = root.get("task_id")?.asString ?: return
                    val error = root.get("error")?.asString ?: "Unknown error"
                    val deferred = pendingTasks.remove(taskId)
                    deferred?.complete(RemoteTaskResult.Failure(error))
                }
                // 工具执行（母体下发的 tool/execute）
                "tool/execute" -> {
                    // 安全(R3):握手确认(ONLINE)前不接受 tool/execute —— 防止未完成 hello 鉴权的
                    // 连接、或握手前抢注的 MITM 直接驱动工具(高危工具虽仍过来源闸门,但握手前
                    // 就不该接受任何指令)。
                    if (state != ConnectionState.ONLINE) {
                        XLog.w(tag, "tool/execute rejected before handshake ack (state=$state)")
                        return
                    }
                    val callId = root.get("id")?.asString ?: return
                    val tool = root.get("tool")?.asString ?: return
                    val argsElement = root.get("args")
                    val args = parseArgs(argsElement)
                    val call = ToolCall(id = callId, name = tool, args = args)
                    onToolExecute?.invoke(call)
                }
                // 配置同步响应（母体推来的配置变更）
                "config/sync_pull_response" -> {
                    // 同上:握手确认前不应用任何远程配置(敏感键另有 SYNC_BLOCKED 黑名单兜底)。
                    if (state != ConnectionState.ONLINE) {
                        XLog.w(tag, "config/sync_pull_response rejected before handshake ack (state=$state)")
                        return
                    }
                    onConfigChange?.invoke(text)
                }
                // 母体下发的 workspace/sync —— 自动创建本地挂载点,实现远程协作项目同步
                "workspace/sync" -> {
                    if (state != ConnectionState.ONLINE) {
                        XLog.w(tag, "workspace/sync rejected before handshake ack (state=$state)")
                        return
                    }
                    handleWorkspaceSync(root)
                }
                // 母体下发的 workspace/lease_acquire_result —— 推送前申请租约的结果
                "workspace/lease_acquire_result" -> {
                    if (state != ConnectionState.ONLINE) return
                    handleLeaseAcquireResult(root)
                }
                // 心跳 ACK（母体确认收到心跳，表明母体存活）
                "heartbeat/ack" -> {
                    if (state == ConnectionState.HELLO_SENT) {
                        markHelloAcknowledged("heartbeat/ack")
                    }
                    onHeartbeatAck?.invoke()
                }
            }
        } catch (e: Exception) {
            XLog.w(tag, "handleIncomingMessage error: ${e.message}")
        }
        // 通用消息分发（所有监听器）
        for (listener in messageListeners) {
            try {
                listener.invoke(text)
            } catch (e: Exception) {
                XLog.w(tag, "messageListener failed: ${e.message}")
            }
        }
    }

    private fun handleHelloAck(root: JsonObject): Boolean {
        val helloId = pendingHelloId ?: return false
        val id = root.get("id")?.asString ?: return false
        if (id != helloId || !root.has("result")) return false
        val result = root.get("result")
        if (result.isJsonObject) {
            val obj = result.asJsonObject
            // nonce 校验：若服务端回传了 nonce，必须与我们发送的一致（防重放）。
            // 旧服务端不回传 nonce 则跳过此项（向后兼容）。
            val serverNonce = obj.get("nonce")?.asString
            if (serverNonce != null) {
                val expected = pendingHelloNonce
                if (expected == null || serverNonce != expected) {
                    XLog.w(tag, "hello_ack nonce mismatch — possible replay attack, disconnecting")
                    pendingHelloId = null
                    pendingHelloNonce = null
                    webSocket?.close(1008, "nonce mismatch")
                    setState(ConnectionState.DISCONNECTED)
                    return true
                }
            }
            val registered = obj.get("registered")?.asBoolean == true ||
                obj.get("ok")?.asBoolean == true ||
                obj.get("accepted")?.asBoolean == true
            if (registered) {
                markHelloAcknowledged("hello result")
                return true
            }
        } else if (result.isJsonPrimitive && result.asJsonPrimitive.isBoolean && result.asBoolean) {
            markHelloAcknowledged("hello boolean result")
            return true
        }
        return false
    }

    private fun handleHelloError(root: JsonObject): Boolean {
        val helloId = pendingHelloId ?: return false
        val id = root.get("id")?.asString ?: return false
        if (id != helloId || !root.has("error")) return false
        val message = root.getAsJsonObject("error")?.get("message")?.asString ?: "hello rejected"
        XLog.w(tag, "runtime hello rejected: $message")
        pendingHelloId = null
        pendingHelloNonce = null
        diagnostics.onDisconnected("hello rejected: $message", System.currentTimeMillis())
        setState(ConnectionState.DISCONNECTED)
        webSocket?.close(1008, "hello rejected")
        return true
    }

    private fun markHelloAcknowledged(reason: String) {
        if (state == ConnectionState.HELLO_SENT) {
            XLog.i(tag, "runtime hello acknowledged: $reason")
            pendingHelloId = null
            pendingHelloNonce = null
            setState(ConnectionState.ONLINE)
            reconnectAttempts.set(0)
        }
    }

    /**
     * 添加一个通用消息监听器.
     */
    fun addMessageListener(listener: (String) -> Unit) {
        messageListeners.add(listener)
    }

    /**
     * 移除一个通用消息监听器.
     */
    fun removeMessageListener(listener: (String) -> Unit) {
        messageListeners.remove(listener)
    }

    /**
     * 向母体发送远程任务请求，等待结果.
     *
     * ⚠️ 注意：原本设计为 BrainModeSelector.decideRemotely() 的核心调用,
     * 但 BrainModeSelector 的路由裁决从未真正接线（已加 @Deprecated）,
     * 本方法目前无生产调用方,保留以备未来接线。
     */
    suspend fun executeRemoteTask(task: String, intent: IntentClassifier.ClassificationResult): RemoteTaskResult {
        val ws = webSocket
        if (ws == null || state != ConnectionState.ONLINE) {
            return RemoteTaskResult.Failure("Not connected to runtime")
        }

        val taskId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<RemoteTaskResult>()
        pendingTasks[taskId] = deferred

        val request = Envelope.Request(
            method = "task/execute",
            params = mapOf(
                "task_id" to taskId,
                "task" to task,
                "intent" to intent.primary.name,
                "confidence" to intent.confidence,
                "tentacle_id" to tentacleId
            ),
            id = taskId
        )

        return try {
            ws.send(request.toJson())
            // 等待结果（60 秒超时）
            withTimeout(60_000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingTasks.remove(taskId)
            RemoteTaskResult.Failure("Task timeout after 60s")
        } catch (e: Exception) {
            pendingTasks.remove(taskId)
            RemoteTaskResult.Failure("Task failed: ${e.message}")
        }
    }

    /**
     * 发送工具执行结果给母体.
     *
     * 手机端执行完 tool/execute 后调用.
     */
    open fun sendToolResult(
        callId: String,
        success: Boolean,
        data: String? = null,
        error: String? = null,
        errorCode: Int? = null,
        durationMs: Int = 0,
        screenHashAfter: String? = null,
    ) {
        val ws = webSocket ?: return
        // 母体 ws_server._handle_tool_result 按 method="tool/result" 路由，并从
        // params.{call_id,success,data,error{code,message},duration_ms} 读取 ——
        // 必须发 method 风格消息，而非 JSON-RPC reply（否则母体当作未知方法、调用方超时）。
        val params = mutableMapOf<String, Any?>(
            "call_id" to callId,
            "success" to success,
            "duration_ms" to durationMs,
        )
        if (success) {
            params["data"] = data ?: ""
            if (screenHashAfter != null) params["screen_hash_after"] = screenHashAfter
        } else {
            params["error"] = mapOf(
                "code" to (errorCode ?: -32603),
                "message" to (error ?: "Unknown error"),
            )
        }
        ws.send(Envelope.Request(method = "tool/result", params = params).toJson())
    }

    /**
     * 是否处于"已订阅母体 PC 屏幕流"状态。母体侧订阅是有状态的，断连即失效——
     * 记录意图，以便重连到达 ONLINE 时自动重订阅（见 [setState]）。
     */
    @Volatile
    private var pcScreenSubscribed = false

    /** 订阅母体 PC 屏幕流（远程桌面：母体随后通过 push_pc_frame 推 JPEG 帧）。 */
    fun subscribePcScreen() {
        pcScreenSubscribed = true
        send(Envelope.Request(method = "pc_screen/subscribe", params = mapOf("tentacle_id" to tentacleId)))
    }

    /** 取消订阅母体 PC 屏幕流。 */
    fun unsubscribePcScreen() {
        pcScreenSubscribed = false
        send(Envelope.Request(method = "pc_screen/unsubscribe", params = mapOf("tentacle_id" to tentacleId)))
    }

    /**
     * 远程控制母体（remote/input）。坐标为归一化 [0,1]，母体按其屏幕尺寸还原。
     * @param action tap / move / down / up / type / key 等
     */
    fun sendRemoteInput(action: String, x: Float = 0f, y: Float = 0f, text: String? = null) {
        val params = mutableMapOf<String, Any?>(
            "action" to action,
            "x" to x.toDouble(),
            "y" to y.toDouble(),
            "tentacle_id" to tentacleId,
        )
        if (text != null) params["text"] = text
        send(Envelope.Request(method = "remote/input", params = params))
    }

    /**
     * 发送 envelope 消息.
     */
    fun send(envelope: Envelope) {
        val ws = webSocket ?: run {
            XLog.w(tag, "send skipped: not connected")
            return
        }
        ws.send(envelope.toJson())
    }

    /**
     * 主动断开连接.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectAttempts.set(0)
        scope.cancel()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        setState(ConnectionState.OFFLINE)
        diagnostics.onDisconnected(null, System.currentTimeMillis())
    }

    /**
     * 强制重连：主动断开当前连接并立即重连（用于心跳检测到母体僵死时）。
     * 与 [disconnect] 不同，不设为 OFFLINE，而是走 DISCONNECTED → 重连流程。
     */
    fun forceReconnect() {
        XLog.i(tag, "forceReconnect: closing current connection")
        reconnectAttempts.set(0)
        webSocket?.close(1001, "force reconnect")
        webSocket = null
        setState(ConnectionState.DISCONNECTED)
        diagnostics.onDisconnected("force reconnect", System.currentTimeMillis())
        scheduleReconnect()
    }

    private fun setState(newState: ConnectionState) {
        if (state != newState) {
            state = newState
            onStateChanged?.invoke(newState)
            if (newState == ConnectionState.ONLINE) {
                diagnostics.onConnected(System.currentTimeMillis())
                // 重连恢复：到达 ONLINE 时，若此前订阅过母体 PC 屏幕流则自动重订阅。
                // 否则远程桌面在一次网络抖动后掉线，便永远等不到新帧（母体侧订阅已随旧连接失效）。
                if (pcScreenSubscribed) {
                    XLog.i(tag, "reconnected ONLINE, restoring pc_screen subscription")
                    send(Envelope.Request(method = "pc_screen/subscribe", params = mapOf("tentacle_id" to tentacleId)))
                }
            }
        }
    }

    open fun currentState(): ConnectionState = state

    // ── 辅助 ────────────────────────────────────────────────

    /** 生成 16 字节随机 nonce（十六进制编码），用于握手防重放. */
    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解析 args 元素为 Map<String, Any>.
     * 支持 string/int/long/double/boolean 及嵌套对象/数组（Gson 自动处理类型）。
     */
    private fun parseArgs(element: com.google.gson.JsonElement?): Map<String, Any> {
        if (element == null || !element.isJsonObject) return emptyMap()
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        return try {
            gson.fromJson(element, mapType) ?: emptyMap()
        } catch (e: Exception) {
            XLog.w(tag, "parseArgs failed: ${e.message}")
            emptyMap()
        }
    }
    // ── workspace/sync 处理 ──────────────────────────────

    /**
     * 处理母体下发的 workspace/sync —— 解析 workspace 配置,自动创建本地挂载点.
     *
     * 协议格式:
     * ```
     * {
     *   "method": "workspace/sync",
     *   "workspace_id": "ws-xxx",
     *   "mount_config": {
     *     "name": "Team Project X",
     *     "type": "sftp" | "webdav" | "local",
     *     "host": "nas.example.com",
     *     "port": 22,
     *     "user": "dev",
     *     "root_path": "/home/dev/project-x"
     *   },
     *   "credentials": {  // 可选;母体可只下发配置,凭据由用户在移动端输入
     *     "password": "...",
     *     "private_key": "..."
     *   },
     *   "lease_token": "optional-lease-token"
     * }
     * ```
     *
     * 行为:
     *  1. 按 motherWorkspaceId 去重:已存在则只更新 lease_token,不重复 mount
     *  2. 不存在则创建 Mount 并调用 RemoteWorkspaceManager.mount()
     *  3. 回复 workspace/sync_ack(含 mountId + 状态)
     */
    private fun handleWorkspaceSync(root: JsonObject) {
        try {
            val workspaceId = root.get("workspace_id")?.asString ?: ""
            if (workspaceId.isEmpty()) {
                XLog.w(tag, "workspace/sync missing workspace_id")
                return
            }
            val mountConfig = root.getAsJsonObject("mount_config") ?: run {
                XLog.w(tag, "workspace/sync missing mount_config")
                return
            }
            val credObj = root.getAsJsonObject("credentials")
            val password = credObj?.get("password")?.asString ?: ""
            val privateKey = credObj?.get("private_key")?.asString ?: ""
            val leaseToken = root.get("lease_token")?.asString ?: ""

            val name = mountConfig.get("name")?.asString ?: "Mother-$workspaceId"
            val typeStr = mountConfig.get("type")?.asString ?: "sftp"
            val host = mountConfig.get("host")?.asString ?: ""
            val port = mountConfig.get("port")?.asInt ?: 22
            val user = mountConfig.get("user")?.asString ?: ""
            val rootPath = mountConfig.get("root_path")?.asString ?: "/"

            if (host.isEmpty()) {
                XLog.w(tag, "workspace/sync missing host")
                sendWorkspaceSyncAck(workspaceId, "", "missing_host", "Mount config missing host")
                return
            }

            // 去重:按 motherWorkspaceId 查找已有挂载
            val existing = com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts
                .findByMotherWorkspaceId(workspaceId)
            if (existing != null) {
                XLog.i(tag, "workspace/sync: mount ${existing.id} already exists for $workspaceId")
                sendWorkspaceSyncAck(workspaceId, existing.id, "ok", "already_mounted")
                return
            }

            // 创建新挂载点
            val type = try {
                com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts.Type
                    .valueOf(typeStr.uppercase())
            } catch (e: Exception) {
                sendWorkspaceSyncAck(workspaceId, "", "invalid_type", "Unknown type: $typeStr")
                return
            }
            val mount = com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts.Mount(
                id = "rws-" + java.util.UUID.randomUUID().toString().take(8),
                name = name,
                type = type,
                host = host,
                port = port,
                user = user,
                rootPath = rootPath,
                motherWorkspaceId = workspaceId,
            )

            val result = com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
                .mount(mount, password, privateKey)

            val ackStatus = if (result.success) "ok" else "mount_failed"
            val ackMsg = if (result.success) "mounted" else result.message
            sendWorkspaceSyncAck(workspaceId, mount.id, ackStatus, ackMsg)
        } catch (e: Exception) {
            XLog.w(tag, "handleWorkspaceSync error: ${e.message}")
        }
    }

    /** 处理母体对 lease_acquire 的响应 —— 通知等待中的 push 操作. */
    private fun handleLeaseAcquireResult(root: JsonObject) {
        try {
            val leaseId = root.get("lease_id")?.asString ?: return
            val granted = root.get("granted")?.asBoolean ?: false
            val holder = root.get("holder_info")?.asString ?: ""
            val deferred = pendingLeases.remove(leaseId)
            if (deferred != null) {
                deferred.complete(LeaseResult(granted, holder))
            }
        } catch (e: Exception) {
            XLog.w(tag, "handleLeaseAcquireResult error: ${e.message}")
        }
    }

    /** 发送 workspace/sync_ack 给母体. */
    private fun sendWorkspaceSyncAck(
        workspaceId: String,
        mountId: String,
        status: String,
        message: String,
    ) {
        val params = mapOf(
            "workspace_id" to workspaceId,
            "mount_id" to mountId,
            "status" to status,
            "message" to message,
            "ts" to System.currentTimeMillis(),
        )
        send(Envelope.Request(method = "workspace/sync_ack", params = params))
    }

    /**
     * 向母体申请推送租约(防多端同时推送覆盖).
     *
     * @param workspaceId 母体 workspace id
     * @param path 要推送的文件路径
     * @return [LeaseResult] —— granted=true 可推送;granted=false 他人持有租约,需等待
     */
    suspend fun acquirePushLease(workspaceId: String, path: String): LeaseResult {
        val leaseId = "lease-" + java.util.UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<LeaseResult>()
        pendingLeases[leaseId] = deferred
        val params = mapOf(
            "lease_id" to leaseId,
            "workspace_id" to workspaceId,
            "path" to path,
            "tentacle_id" to tentacleId,
            "ts" to System.currentTimeMillis(),
        )
        send(Envelope.Request(method = "workspace/lease_acquire", params = params))
        // 10s 超时
        return try {
            withTimeoutOrNull(10_000) { deferred.await() } ?: LeaseResult(false, "timeout")
        } catch (e: Exception) {
            pendingLeases.remove(leaseId)
            LeaseResult(false, "error: ${e.message}")
        } finally {
            pendingLeases.remove(leaseId)
        }
    }

    /** 释放推送租约(推送完成后调用). */
    fun releasePushLease(leaseId: String, workspaceId: String, path: String) {
        val params = mapOf(
            "lease_id" to leaseId,
            "workspace_id" to workspaceId,
            "path" to path,
            "tentacle_id" to tentacleId,
        )
        send(Envelope.Request(method = "workspace/lease_release", params = params))
    }

    /** 通知母体文件已推送(用于协作通知). */
    fun notifyPushComplete(workspaceId: String, path: String, mountId: String) {
        val params = mapOf(
            "workspace_id" to workspaceId,
            "path" to path,
            "mount_id" to mountId,
            "tentacle_id" to tentacleId,
            "ts" to System.currentTimeMillis(),
        )
        send(Envelope.Request(method = "workspace/push_notify", params = params))
    }

    /** 租约申请结果. */
    data class LeaseResult(val granted: Boolean, val holderInfo: String)


}
