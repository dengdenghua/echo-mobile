package com.apk.claw.android.server
import com.apk.claw.android.utils.OctoHttp

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.utils.runCatchingOrDefault
import com.apk.claw.android.utils.runCatchingOrNull
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 官网远程控制台网关。
 *
 * 手机端主动连接官网 WebSocket,避免 NAT/局域网/IP 变动问题。官网控制台只和服务端通信,
 * 服务端再把 JSON 指令转发到这里执行。
 */
object RemoteConsoleGateway {
    private const val TAG = "RemoteConsoleGateway"
    private const val KEY_DEVICE_ID = "REMOTE_CONSOLE_DEVICE_ID"
    private const val KEY_DEVICE_TOKEN = "REMOTE_CONSOLE_DEVICE_TOKEN"
    private const val KEY_DEVICE_NAME = "REMOTE_CONSOLE_DEVICE_NAME"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var manualStop = false
    @Volatile private var retryMs = 2_000L

    val isPaired: Boolean get() = deviceId.isNotEmpty() && deviceToken.isNotEmpty()
    val isConnected: Boolean get() = socket != null

    var deviceId: String
        get() = KVUtils.getString(KEY_DEVICE_ID, "")
        private set(v) {
            KVUtils.putString(KEY_DEVICE_ID, v)
        }

    private var deviceToken: String
        get() = KVUtils.getString(KEY_DEVICE_TOKEN, "")
        set(v) {
            KVUtils.putString(KEY_DEVICE_TOKEN, v)
        }

    var deviceName: String
        get() = KVUtils.getString(KEY_DEVICE_NAME, defaultDeviceName())
        private set(v) {
            KVUtils.putString(KEY_DEVICE_NAME, v)
        }

    suspend fun claimPairCode(code: String, name: String = defaultDeviceName()): String = withContext(Dispatchers.IO) {
        val auth = AccountStore.token.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先登录账号")
        val normalized = code.trim()
        require(normalized.matches(Regex("\\d{6,9}"))) { "配对码格式不正确" }
        val stableDeviceId = deviceId.ifBlank {
            "d_" + UUID.randomUUID().toString().replace("-", "").take(16)
        }
        val bodyJson = gson.toJson(mapOf(
            "code" to normalized,
            "deviceName" to name.ifBlank { defaultDeviceName() },
            "deviceId" to stableDeviceId,
        ))
        val req = Request.Builder()
            .url(AccountConfig.baseUrl.trimEnd('/') + "/remote/pair/claim")
            .header("Authorization", "Bearer $auth")
            .post(bodyJson.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "配对失败: HTTP ${resp.code}")
            val json = gson.fromJson(text, JsonObject::class.java)
            deviceId = json.get("deviceId")?.asString.orEmpty()
            deviceToken = json.get("deviceToken")?.asString.orEmpty()
            deviceName = json.get("deviceName")?.asString?.ifBlank { name } ?: name
            if (!isPaired) throw RuntimeException("服务端返回的设备凭证不完整")
        }
        connect()
        "已配对 ${deviceName}"
    }

    /**
     * 一键绑定:已登录账号即可,设备自行 /remote/pair/start 生成本账号配对码,
     * 再自行 claim 完成绑定 —— 免去从网页 / PC 手输配对码(纯两台手机也能用)。
     */
    suspend fun autoPairWithAccount(name: String = defaultDeviceName()): String = withContext(Dispatchers.IO) {
        val auth = AccountStore.token.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先登录账号")
        val startReq = Request.Builder()
            .url(AccountConfig.baseUrl.trimEnd('/') + "/remote/pair/start")
            .header("Authorization", "Bearer $auth")
            .post(gson.toJson(mapOf("deviceName" to name.ifBlank { defaultDeviceName() })).toRequestBody(JSON))
            .build()
        val code = http.newCall(startReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error(serverDetail(text) ?: "绑定失败: HTTP ${resp.code}")
            gson.fromJson(text, JsonObject::class.java)?.get("code")?.asString.orEmpty()
        }
        if (code.isBlank()) error("服务端未返回配对码")
        claimPairCode(code, name)
    }

    /**
     * 拉取当前账号下、在线且已上报 LAN 凭证的设备。
     * 用于把对端 ConfigServer 的真实 token 下发到本机 —— beacon 出于明文安全不携带 token,
     * 真 token 通过账号 /remote/devices 通道分发给同账号设备。未登录 / 失败时返回空列表。
     */
    suspend fun fetchAccountDevices(): List<AccountLanDevice> = withContext(Dispatchers.IO) {
        val auth = AccountStore.token.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val req = Request.Builder()
            .url(AccountConfig.baseUrl.trimEnd('/') + "/remote/devices")
            .header("Authorization", "Bearer $auth")
            .get()
            .build()
        runCatchingOrDefault(TAG, emptyList<AccountLanDevice>()) {
            OctoHttp.shared.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<AccountLanDevice>()
                val root = gson.fromJson(resp.body?.string().orEmpty(), JsonObject::class.java)
                val items = root?.getAsJsonArray("items") ?: return@use emptyList<AccountLanDevice>()
                items.mapNotNull { el ->
                    val o = el.asJsonObject
                    val base = o.get("lanBaseUrl")?.asString.orEmpty()
                    val token = o.get("lanAuthToken")?.asString.orEmpty()
                    if (base.isBlank() || token.isBlank()) null
                    else AccountLanDevice(o.get("deviceId")?.asString.orEmpty(), base, token)
                }
            }
        }
    }

    fun connect() {
        if (!isPaired || socket != null) return
        manualStop = false
        val req = Request.Builder().url(wsUrl()).build()
        http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                retryMs = 2_000L
                sendDeviceInfo(webSocket)
                XLog.i(TAG, "官网远程控制台已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                XLog.e(TAG, "官网远程控制台连接失败: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        manualStop = true
        socket?.close(1000, "user disconnect")
        socket = null
    }

    fun clearPairing() {
        disconnect()
        deviceId = ""
        deviceToken = ""
        deviceName = defaultDeviceName()
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        val msg = runCatchingOrNull(TAG) { gson.fromJson(text, JsonObject::class.java) }
        if (msg == null) {
            sendResult(ws, "", false, "消息格式错误")
            return
        }
        if (msg.get("type")?.asString == "revoked") {
            clearPairing()
            return
        }
        val id = msg.get("id")?.asString.orEmpty()
        when (msg.get("type")?.asString) {
            "ping" -> ws.send(gson.toJson(mapOf("type" to "pong", "id" to id)))
            "control" -> {
                val action = msg.get("action")?.asString.orEmpty()
                val ok = runCatching { performControl(action, msg) }.getOrElse { e ->
                    XLog.e(TAG, "远程控制执行失败: ${e.message}", e)
                    false
                }
                sendResult(ws, id, ok, if (ok) "操作已执行" else "操作失败: $action")
            }
            // 个人网页公网隧道:访客请求经服务端进来,只交给 PersonalSiteServer 读 filesDir/site 里的
            // 静态字节回去 —— 刻意不走 performControl/ToolRegistry,匿名访客永远碰不到任何带权能力。
            "http" -> {
                val r = PersonalSiteServer.serve(ClawApplication.instance, msg.get("path")?.asString.orEmpty())
                r.body.use { stream ->
                    ws.send(gson.toJson(mapOf(
                        "type" to "http_response",
                        "id" to id,
                        "status" to r.status,
                        "contentType" to r.contentType,
                        "headers" to r.headers,
                        "bodyB64" to Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP),
                    )))
                }
            }
            else -> sendResult(ws, id, false, "暂不支持的指令类型")
        }
    }

    private fun performControl(action: String, msg: JsonObject): Boolean {
        // 远程控制台=不可信来源:每条指令都向用户浮标/审计上报"正被远程控制",
        // 并统一走 ToolRegistry.withUntrustedSource + executeTool,与 DeviceRouteHandler
        // 的 LAN 控制口径一致(避免静默操控 + 补齐 7 门管线的审计/安全门/来源闸门)。
        RemoteControlIndicator.onControlInput("remote_console")
        // 与 DeviceRouteHandler 完全对称的 action → tool 映射。
        val (toolName, params) = when (action) {
            "tap" -> "tap" to mapOf(
                "x" to msg.int("x"),
                "y" to msg.int("y"),
            )
            "swipe" -> "swipe" to mapOf(
                "start_x" to msg.int("x1"),
                "start_y" to msg.int("y1"),
                "end_x" to msg.int("x2"),
                "end_y" to msg.int("y2"),
                "duration_ms" to msg.long("duration", 300L),
            )
            "long_press" -> "long_press" to mapOf(
                "x" to msg.int("x"),
                "y" to msg.int("y"),
                "duration_ms" to msg.long("duration", 600L),
            )
            "key" -> "system_key" to mapOf("key_code" to msg.int("keyCode"))
            "text" -> "input_text" to mapOf("text" to (msg.get("text")?.asString ?: ""))
            "open_app" -> "open_app" to mapOf("package_name" to (msg.get("package")?.asString ?: ""))
            "back" -> "system_key" to mapOf("key" to "back")
            "home" -> "system_key" to mapOf("key" to "home")
            "recent" -> "system_key" to mapOf("key" to "recent_apps")
            "notifications" -> "system_key" to mapOf("key" to "notifications")
            else -> return false
        }
        return ToolRegistry.withUntrustedSource {
            ToolRegistry.executeTool(toolName, params).isSuccess
        }
    }

    private fun sendResult(ws: WebSocket, id: String, success: Boolean, message: String) {
        ws.send(gson.toJson(mapOf(
            "type" to "result",
            "id" to id,
            "success" to success,
            "message" to message,
        )))
    }

    private fun sendDeviceInfo(ws: WebSocket) {
        val lan = runCatchingOrNull(TAG) { ensureLanConsole() }
        ws.send(gson.toJson(mapOf(
            "type" to "device_info",
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "lanBaseUrl" to (lan?.first ?: ""),
            "lanAuthToken" to (lan?.second ?: ""),
            "lanConsoleUrl" to (lan?.third ?: ""),
        )))
    }

    private fun ensureLanConsole(): Triple<String, String, String>? {
        KVUtils.setConfigServerEnabled(true)
        com.apk.claw.android.mcp.McpServerBootstrap.onNetworkSettingChanged()
        if (!ConfigServerManager.start(ClawApplication.instance)) return null
        val address = ConfigServerManager.getAddress() ?: return null
        val token = ConfigServerManager.getAuthToken() ?: return null
        val baseUrl = "http://$address"
        return Triple(baseUrl, token, "$baseUrl/console?token=${enc(token)}")
    }

    private fun scheduleReconnect() {
        if (manualStop || !isPaired) return
        val base = retryMs.coerceAtMost(60_000L)
        // 0-30% 的 jitter:避免大量设备同时断线后集中重连(thundering herd)
        val jitter = (Math.random() * 0.3 * base).toLong()
        val delay = base + jitter
        retryMs = (retryMs * 2).coerceAtMost(60_000L)
        mainHandler.postDelayed({ connect() }, delay)
    }

    private fun wsUrl(): String {
        val base = AccountConfig.baseUrl.trimEnd('/')
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return wsBase + "/remote/device/ws?device_id=" + enc(deviceId) + "&device_token=" + enc(deviceToken)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun defaultDeviceName(): String = "Octopus ${Build.MODEL ?: "Android"}"

    private fun serverDetail(body: String): String? = runCatchingOrNull(TAG) {
        gson.fromJson(body, JsonObject::class.java)
            ?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        runCatchingOrDefault(TAG, default) { get(key)?.asInt ?: default }

    private fun JsonObject.long(key: String, default: Long = 0L): Long =
        runCatchingOrDefault(TAG, default) { get(key)?.asLong ?: default }
}

/** 账号 /remote/devices 下发的一台在线设备的 LAN 直连凭证。 */
data class AccountLanDevice(
    val deviceId: String,
    val lanBaseUrl: String,
    val lanAuthToken: String,
)
