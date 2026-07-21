package com.apk.claw.android.tentacle

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.apk.claw.android.utils.XLog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest

/**
 * 设备注册器 —— 管理 device_id 持久化 + 握手 + 心跳.
 *
 * ## 职责
 *
 * 1. **device_id 持久化**: 首次启动生成稳定 ID, 优先 `Settings.Secure.ANDROID_ID`,
 *    回退随机 UUID 落盘到 `filesDir/device_id.txt`(应用卸载才失效).
 * 2. **device/hello 帧**: 构造 [deviceHelloFrame] 由调用方(或 [sendHello])发送.
 * 3. **30s 心跳定时器**: 用 [Handler.postDelayed] 在主线程调度, 不阻塞 IO.
 *    5s 内未收到 `device/heartbeat_ack` 记一次失败, 3 次失败触发重连.
 * 4. **Capabilities 收集**: 运行时注入 —— 不直接 import ToolRegistry, 由调用方提供
 *    `() -> List<String>` lambda(集成阶段调用 `ToolRegistry.getInstance().listToolNames()`).
 *
 * ## 与 OctopusMobileClient 的协作
 *
 * ```
 * val client = OctopusMobileClient()
 * val reg = DeviceRegistration(context)
 *
 * client.onWelcome = { _, _ -> reg.startHeartbeat(client) }
 * client.onHeartbeatAck = { serverTs -> reg.onHeartbeatAck(serverTs) }
 *
 * client.connect(url, authToken)
 * // onOpen 后:
 * reg.sendHello(client, reg.deviceHelloFrame(capabilities, authToken))
 * ```
 */
class DeviceRegistration(
    private val context: Context,
    /** 能力提供器 —— 集成阶段注入 ToolRegistry 的工具名列表. 默认返回空(占位). */
    private val capabilitiesProvider: () -> List<String> = { emptyList() },
) {
    private val tag = "DeviceRegistration"

    /** 主线程 Handler, 调度心跳. */
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var heartbeatRunnable: Runnable? = null

    @Volatile
    private var ackTimeoutRunnable: Runnable? = null

    /** 连续未收到 ack 的次数. */
    @Volatile
    private var missedAcks: Int = 0

    /** 当前是否在心跳循环中. */
    @Volatile
    private var heartbeatActive: Boolean = false

    /** 持久化的 device_id. */
    val deviceId: String by lazy { loadOrCreateDeviceId() }

    // ── device_id 持久化 ──────────────────────────────────────────────────

    /**
     * 加载或生成 device_id.
     *
     * 优先用 `Settings.Secure.ANDROID_ID`(应用签名 + 用户 + 设备 三元组稳定);
     * 取不到时回退随机 UUID, 落盘到 `filesDir/device_id.txt` 保证跨重启稳定.
     */
    private fun loadOrCreateDeviceId(): String {
        // 1. 先看 filesDir/device_id.txt(已落盘的回退 ID)
        val idFile = File(context.filesDir, DEVICE_ID_FILE)
        if (idFile.exists()) {
            try {
                val persisted = idFile.readText().trim()
                if (persisted.isNotEmpty()) {
                    return persisted
                }
            } catch (e: Exception) {
                XLog.w(tag, "read device_id file failed: ${e.message}")
            }
        }

        // 2. 优先 ANDROID_ID
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }.orEmpty()

        // 3. ANDROID_ID 不稳定/取不到时, 回退随机 UUID + 落盘
        val resolved = if (androidId.isNotBlank()) {
            // hash 一下避免直接暴露 ANDROID_ID(顺带抹除前导零差异)
            sha1Hex(androidId + Build.MODEL).take(32)
        } else {
            java.util.UUID.randomUUID().toString().replace("-", "")
        }

        // 4. 持久化
        try {
            idFile.parentFile?.mkdirs()
            idFile.writeText(resolved)
        } catch (e: Exception) {
            XLog.w(tag, "persist device_id failed: ${e.message}")
        }
        return resolved
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── device/hello 帧 ───────────────────────────────────────────────────

    /**
     * 构造 `device/hello` 帧.
     *
     * 帧格式: `{type:"device/hello", device_id, capabilities, auth_token}`.
     *
     * @param capabilities 设备能力清单, 例 `["tap","swipe","screenshot",...]`.
     *                     默认取构造时注入的 [capabilitiesProvider].
     * @param authToken    母本认证 token(若已通过 HTTP header 鉴权可留空).
     */
    fun deviceHelloFrame(
        capabilities: List<String> = capabilitiesProvider(),
        authToken: String = "",
    ): JsonObject {
        val arr = JsonArray()
        capabilities.forEach { arr.add(it) }
        return JsonObject().apply {
            addProperty("type", "device/hello")
            addProperty("device_id", deviceId)
            add("capabilities", arr)
            if (authToken.isNotBlank()) {
                addProperty("auth_token", authToken)
            }
            // 元数据(非协议要求, 便于母本侧设备画像)
            addProperty("platform", "android")
            addProperty("brand", Build.BRAND)
            addProperty("model", Build.MODEL)
            addProperty("android_version", Build.VERSION.RELEASE)
            addProperty("sdk", Build.VERSION.SDK_INT)
        }
    }

    /**
     * 发送 `device/hello` 给母本.
     *
     * 在 [OctopusMobileClient] 的 WS `onOpen` 后调用.
     */
    fun sendHello(client: OctopusMobileClient, capabilities: List<String>, authToken: String) {
        val frame = deviceHelloFrame(capabilities, authToken)
        XLog.i(tag, "sending device/hello: device=${deviceId.take(8)}… caps=${capabilities.size}")
        client.send(frame)
    }

    // ── 心跳定时器 ─────────────────────────────────────────────────────────

    /**
     * 启动 30s 心跳循环.
     *
     * 用 [Handler.postDelayed] 在主线程调度, 不阻塞 IO 协程.
     * 每次 send 后 5s 内未收到 ack → 记一次失败; 3 次失败 → 触发重连.
     *
     * @param client 用于发送心跳帧 + 触发重连(通过返回 Boolean 让调用方决定).
     * @param onMissedAcks 达到阈值时回调(由 TentacleManager 调用 client 重连).
     */
    fun startHeartbeat(
        client: OctopusMobileClient,
        onMissedAcks: () -> Unit = {},
    ) {
        stopHeartbeat()
        heartbeatActive = true
        missedAcks = 0

        val heartbeat = object : Runnable {
            override fun run() {
                if (!heartbeatActive) return
                sendHeartbeat(client)
                // 5s 内未收到 ack → 记一次失败
                ackTimeoutRunnable?.let { handler.removeCallbacks(it) }
                val timeout = Runnable {
                    if (!heartbeatActive) return@Runnable
                    missedAcks++
                    XLog.w(tag, "heartbeat ack timeout, missed=$missedAcks")
                    if (missedAcks >= MAX_MISSED_ACKS) {
                        XLog.w(tag, "max missed acks reached, triggering reconnect")
                        missedAcks = 0
                        onMissedAcks()
                    }
                }
                ackTimeoutRunnable = timeout
                handler.postDelayed(timeout, ACK_TIMEOUT_MS)
                // 调度下一次心跳
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = heartbeat
        // 立即发一次, 缩短首次感知延迟
        handler.post(heartbeat)
        XLog.i(tag, "heartbeat started (interval=${HEARTBEAT_INTERVAL_MS}ms, ack_timeout=${ACK_TIMEOUT_MS}ms)")
    }

    /** 停止心跳循环. */
    fun stopHeartbeat() {
        heartbeatActive = false
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        ackTimeoutRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
        ackTimeoutRunnable = null
        missedAcks = 0
    }

    /**
     * 收到 `device/heartbeat_ack` —— 重置未收 ack 计数.
     *
     * 由 [OctopusMobileClient.onHeartbeatAck] 调用.
     */
    fun onHeartbeatAck(serverTs: Long) {
        missedAcks = 0
        ackTimeoutRunnable?.let { handler.removeCallbacks(it) }
        ackTimeoutRunnable = null
        XLog.d(tag, "heartbeat ack received: server_ts=$serverTs")
    }

    private fun sendHeartbeat(client: OctopusMobileClient) {
        val frame = JsonObject().apply {
            addProperty("type", "device/heartbeat")
            addProperty("ts", System.currentTimeMillis())
            addProperty("device_id", deviceId)
        }
        client.send(frame)
    }

    // ── Capabilities 工具函数 ─────────────────────────────────────────────

    /**
     * 收集设备能力清单 —— 运行时注入, 不直接 import ToolRegistry.
     *
     * 集成阶段应:
     * ```
     * val reg = DeviceRegistration(context) {
     *     ToolRegistry.getInstance().listToolNames() // 或类似 API
     * }
     * ```
     */
    fun capabilities(): List<String> = capabilitiesProvider()

    companion object {
        /** device_id 持久化文件名(相对 filesDir). */
        const val DEVICE_ID_FILE = "device_id.txt"

        /** 心跳间隔(30s). */
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** 单次心跳 ack 等待超时(5s). */
        const val ACK_TIMEOUT_MS = 5_000L

        /** 连续未收到 ack 的最大次数, 达到后触发重连. */
        const val MAX_MISSED_ACKS = 3
    }
}
