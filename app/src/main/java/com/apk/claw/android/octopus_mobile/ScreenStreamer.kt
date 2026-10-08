package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.XLog
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.apk.claw.android.octopus_mobile.proactive.ProactiveRuleEngine
import com.apk.claw.android.service.ClawAccessibilityService
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 方案 F · 屏幕状态增量上报器.
 *
 * 监听无障碍事件，检测屏幕变化，把变化上报到 octopus-agent Runtime.
 *
 * 实现：
 *  - **节流**：throttleMs（默认 5s）内多次事件合并为一次上报
 *  - **哈希去重**：屏幕树未变则跳过
 *  - **节流回调**：通过静态监听器列表接收无障碍事件
 *  - **挂起执行**：在 IO 协程中拉取屏幕树，避阻塞无障碍主线程
 *
 * 接入方式：
 *  ```
 *  val streamer = ScreenStreamer(client, heartbeatReporter)
 *  ScreenStreamer.registerListener(streamer)  // 静态注册
 *  streamer.start()
 *  // ClawAccessibilityService.onAccessibilityEvent 触发时：
 *  ScreenStreamer.dispatchEvent(event)        // 静态分发
 *  ```
 */
class ScreenStreamer(
    private val client: OctopusMobileClient,
    private val heartbeatReporter: HeartbeatReporter? = null,
    private val throttleMs: Long = 5_000L,
    private val proactiveEngine: ProactiveRuleEngine? = null,
) {
    private val tag = "ScreenStreamer"

    /** 上次屏幕树哈希 */
    @Volatile
    private var lastTreeHash: String = ""

    /** 上次发送时间戳 */
    @Volatile
    private var lastEmitTs: Long = 0L

    /** 是否正在运行 */
    @Volatile
    private var running = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null

    /**
     * 启动屏幕流.
     */
    fun start() {
        if (running) return
        running = true
        XLog.i(tag, "ScreenStreamer started (throttle=${throttleMs}ms)")
    }

    /**
     * 停止屏幕流.
     */
    fun stop() {
        running = false
        sendJob?.cancel()
        sendJob = null
        unregisterListener(this)
        // 取消整个协程作用域,释放 SupervisorJob + IO 线程;否则 stop 后 scope 仍存活,
        // flushNow 等仍可调度新协程到已"停止"的 streamer 上。
        scope.cancel()
        XLog.i(tag, "ScreenStreamer stopped")
    }

    /**
     * 处理无障碍事件（由 ClawAccessibilityService.onAccessibilityEvent 转发）.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!running) return
        // 只关注窗口内容变化
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                scheduleSend()
            }
        }
    }

    /**
     * 调度一次发送（节流）.
     */
    private fun scheduleSend() {
        sendJob?.cancel()
        val now = System.currentTimeMillis()
        val delayMs = maxOf(0, throttleMs - (now - lastEmitTs))
        sendJob = scope.launch {
            delay(delayMs)
            sendScreenChanged()
        }
    }

    /**
     * 立即拉取并发送一次（外部触发，跳过节流）.
     */
    fun flushNow() {
        scope.launch { sendScreenChanged() }
    }

    /**
     * 发送一次屏幕变化消息.
     */
    private suspend fun sendScreenChanged() {
        val service = ClawAccessibilityService.getInstance() ?: return
        if (client.currentState() != ConnectionState.ONLINE) return

        val tree = try {
            service.screenTree
        } catch (e: Exception) {
            null
        } ?: return

        val currentHash = sha1Short(tree)
        if (currentHash == lastTreeHash) return
        lastTreeHash = currentHash
        lastEmitTs = System.currentTimeMillis()

        val root: AccessibilityNodeInfo? = try {
            service.getRootInActiveWindow()
        } catch (e: Exception) {
            null
        }
        val currentApp = try {
            root?.packageName?.toString() ?: ""
        } finally {
            root?.recycle()
        }
        val treeDelta = buildTreeDelta(tree)

        val envelope = EnvelopeFactory.screenChanged(
            tentacleId = "",
            currentApp = currentApp,
            screenHash = currentHash,
            treeDelta = treeDelta,
        )
        try {
            client.send(envelope)
            // 同步给 HeartbeatReporter，让心跳包也带上最新 hash
            heartbeatReporter?.lastScreenHash = currentHash
            heartbeatReporter?.currentApp = currentApp
            XLog.d(tag, "screen_changed sent: app=$currentApp hash=$currentHash")
        } catch (e: Exception) {
            XLog.w(tag, "screen_changed send failed: ${e.message}")
        }

        // 触发主动规则引擎检查
        proactiveEngine?.let { engine ->
            try {
                val results = engine.onScreenChanged(currentApp, tree)
                results.forEach { result ->
                    XLog.d(tag, "Proactive: ${result.message}")
                }
            } catch (e: Exception) {
                XLog.w(tag, "Proactive rule execution failed", e)
            }
        }
    }

    /**
     * 构造树增量（简化版：把全树作为 added 字段，removed/changed 留空）.
     */
    private fun buildTreeDelta(tree: String): Map<String, Any?> {
        // 安全(隐私):屏幕树文本会推送到远端 agent,可能含验证码/短信/手机号/邮箱/密钥。
        // 与审计/日志路径一致,先过 SecretRedactor 脱敏(OTP 上下文触发、手机号、邮箱、Bearer/api_key 等)。
        val safeTree = com.apk.claw.android.utils.SecretRedactor.redact(tree) ?: ""
        return mapOf(
            "added" to listOf(mapOf("tree" to safeTree)),
            "removed" to emptyList<Any>(),
            "changed" to emptyList<Any>(),
        )
    }

    /**
     * 短哈希（16 字符），减少 JSON 大小.
     */
    private fun sha1Short(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    companion object {
        /** 静态监听器列表 —— ClawAccessibilityService 通过 dispatchEvent 分发 */
        private val listeners = CopyOnWriteArrayList<ScreenStreamer>()

        /**
         * 注册监听器.
         */
        @JvmStatic
        fun registerListener(streamer: ScreenStreamer) {
            if (!listeners.contains(streamer)) listeners.add(streamer)
        }

        /**
         * 取消注册.
         */
        @JvmStatic
        fun unregisterListener(streamer: ScreenStreamer) {
            listeners.remove(streamer)
        }

        /**
         * 分发无障碍事件到所有监听器（供 ClawAccessibilityService.onAccessibilityEvent 调用）.
         */
        @JvmStatic
        fun dispatchEvent(event: AccessibilityEvent) {
            for (s in listeners) {
                try {
                    s.onAccessibilityEvent(event)
                } catch (e: Exception) {
                    // 单个监听器异常不影响其他
                }
            }
        }
    }
}
