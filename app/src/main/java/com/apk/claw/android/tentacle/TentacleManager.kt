package com.apk.claw.android.tentacle

import android.content.Context
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tentacle 单例管理器 —— 聚合 [OctopusMobileClient] + [DeviceRegistration] + [ScreenRelay].
 *
 * ## 集成入口(集成阶段在 ClawApplication.onCreate 调用)
 *
 * ```
 * class ClawApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         val config = TentacleConfig.load()
 *         if (config.isLocalOnly()) return  // LOCAL_ONLY 模式完全不影响现有功能(INV-T4)
 *
 *         TentacleManager.init(
 *             context = this,
 *             capabilitiesProvider = {
 *                 ToolRegistry.getInstance().listToolNames() // 集成阶段接入
 *             },
 *             screenSource = DefaultScreenSource {
 *                 ToolRegistry.getInstance().executeTool("screenshot", emptyMap())
 *                     .imageBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
 *             },
 *         )
 *         TentacleManager.setToolCallHandler { toolName, params ->
 *             // 远程母本下发的 tool/execute 必经来源闸门 R2/R3
 *             ToolCallDispatcher.withUntrustedSource {
 *                 ToolRegistry.getInstance().executeTool(toolName, params)
 *             }
 *         }
 *         if (config.enabled) {
 *             TentacleManager.start(config.runtimeUrl, config.authToken)
 *         }
 *     }
 * }
 * ```
 *
 * 注意: `ToolCallDispatcher.withUntrustedSource{}` 闸门 + `ToolRegistry.getInstance()`
 * 由集成阶段接入, 本单例不直接 import(契约见 [OctopusMobileClient] 文档).
 */
object TentacleManager {

    private val tag = "TentacleManager"

    private val _fallbackState = MutableStateFlow(TentacleState.DISCONNECTED)

    @Volatile
    private var client: OctopusMobileClient? = null

    @Volatile
    private var registration: DeviceRegistration? = null

    @Volatile
    private var screenRelay: ScreenRelay? = null

    /** 当前是否已 init. */
    @Volatile
    private var initialized: Boolean = false

    /** 当前是否已 start. */
    @Volatile
    private var started: Boolean = false

    /** 当前连接 URL/token(用于重连/重试). */
    @Volatile
    private var lastUrl: String = ""

    @Volatile
    private var lastToken: String = ""

    /**
     * 初始化 —— 注入 Context + 能力提供器 + 屏幕源.
     *
     * 必须在 [start] 之前调用. 重复 init 会先 [stop] 既有实例.
     *
     * @param context              任意 Context(用于 filesDir + ANDROID_ID)
     * @param capabilitiesProvider 工具能力清单(集成阶段从 ToolRegistry 列出)
     * @param screenSource         屏幕帧源(集成阶段从 screenshot 工具包装); null 表示不启用屏幕中继
     */
    fun init(
        context: Context,
        capabilitiesProvider: () -> List<String> = { emptyList() },
        screenSource: ScreenSource? = null,
    ) {
        if (initialized) {
            XLog.w(tag, "init: re-init, stopping existing first")
            stop()
        }
        val appContext = context.applicationContext
        val c = OctopusMobileClient()
        val r = DeviceRegistration(appContext, capabilitiesProvider)
        client = c
        registration = r
        if (screenSource != null) {
            screenRelay = ScreenRelay(screenSource)
        }
        wireCallbacks(c, r)
        initialized = true
        XLog.i(tag, "initialized (device=${r.deviceId.take(8)}…)")
    }

    /**
     * 把 DeviceRegistration 的回调接到 client.
     *
     * - `onWsOpen` → 立即发 `device/hello`(握手开始)
     * - `onWelcome` → 启动 30s 心跳 + 屏幕中继
     * - `onHeartbeatAck` → 重置未收 ack 计数
     * - 3 次未收 ack → 强制重连
     */
    private fun wireCallbacks(c: OctopusMobileClient, r: DeviceRegistration) {
        c.onWsOpen = {
            try {
                r.sendHello(c, r.capabilities(), lastToken)
            } catch (e: Exception) {
                XLog.w(tag, "sendHello on wsOpen failed: ${e.message}")
            }
        }
        c.onWelcome = { _, _ ->
            XLog.i(tag, "device/welcome received → starting heartbeat + screen relay")
            r.startHeartbeat(
                client = c,
                onMissedAcks = {
                    XLog.w(tag, "3 missed heartbeat acks → forcing reconnect")
                    // 断开当前连接 + 立即重连(由 client 内部退避处理后续重试)
                    c.disconnect()
                    if (lastUrl.isNotBlank()) {
                        // 这里走 connect(同步重置退避计数)而非等退避
                        c.connect(lastUrl, lastToken)
                    }
                },
            )
            screenRelay?.start(c)
        }
        c.onHeartbeatAck = { serverTs -> r.onHeartbeatAck(serverTs) }
    }

    /**
     * 启动 Tentacle 通路 —— 建立 WebSocket 连接并自动完成握手.
     *
     * 若 [init] 未调用, 报警并退出(必须先 init).
     */
    fun start(runtimeUrl: String, authToken: String) {
        if (!initialized) {
            XLog.w(tag, "start called before init — call TentacleManager.init(context) first")
            return
        }
        if (started) {
            XLog.w(tag, "already started, ignoring start()")
            return
        }
        if (runtimeUrl.isBlank()) {
            XLog.i(tag, "start: blank runtimeUrl → LOCAL_ONLY mode (no-op, INV-T4)")
            return
        }
        lastUrl = runtimeUrl
        lastToken = authToken
        started = true

        val c = client ?: return
        XLog.i(tag, "starting tentacle: url=$runtimeUrl")
        c.connect(runtimeUrl, authToken)
    }

    /** 停止 Tentacle 通路. */
    fun stop() {
        if (!initialized) return
        XLog.i(tag, "stopping tentacle")
        started = false
        screenRelay?.stop()
        registration?.stopHeartbeat()
        client?.disconnect()
        screenRelay = null
        registration = null
        client = null
        initialized = false
        _fallbackState.value = TentacleState.DISCONNECTED
    }

    /**
     * 注册工具调用处理器 —— 转发给 [OctopusMobileClient.setToolCallHandler].
     *
     * 集成阶段实现(经 ToolCallDispatcher 来源闸门):
     *
     * ```
     * TentacleManager.setToolCallHandler { toolName, params ->
     *     ToolCallDispatcher.withUntrustedSource {
     *         ToolRegistry.getInstance().executeTool(toolName, params)
     *     }
     * }
     * ```
     */
    fun setToolCallHandler(handler: (toolName: String, params: Map<String, Any>) -> ToolResult) {
        client?.setToolCallHandler(handler)
    }

    /**
     * 当前 Tentacle 状态流(供 UI / SettingsActivity 观察).
     *
     * 未 init 时返回常量 DISCONNECTED 流.
     */
    val state: StateFlow<TentacleState>
        get() = client?.stateFlow ?: _fallbackState.asStateFlow()

    /** 当前状态同步读. */
    fun currentState(): TentacleState = client?.currentState() ?: TentacleState.DISCONNECTED

    /** 当前 device_id(若已 init), 否则空串. */
    fun deviceId(): String = registration?.deviceId ?: ""
}
