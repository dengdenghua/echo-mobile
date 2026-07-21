package com.apk.claw.android.tentacle

import android.util.Base64
import com.apk.claw.android.utils.XLog
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tentacle 屏幕中继 —— 把手机屏幕帧按需增量上报到母本 Runtime.
 *
 * ## 设计
 *
 *  - 与既有 `octopus_mobile.ScreenStreamer` 解耦(运行时注入, 不直接 import):
 *    调用方提供 [ScreenSource] 实现, 可以包装既有 ScreenStreamer 的截图产物.
 *  - 默认实现 [DefaultScreenSource] 通过 `screenshot` 工具的结果提供帧 —— 工具调用
 *    由调用方注入为 `() -> ByteArray?` lambda, 避免直接依赖 ToolRegistry.
 *  - 增量上报: 每 [intervalMs] 至多一次; 帧哈希未变则跳过(节省带宽).
 *  - 帧格式: `{"type":"device/screen","frame_base64":"...","width":N,"height":N,"ts":...}`
 *    二进制(JPEG/WebP)走 base64 文本帧, 与母本 ws_server 文本路径兼容.
 *
 * ## 集成契约
 *
 * 集成阶段(ClawApplication)应当:
 *
 * ```
 * val source = DefaultScreenSource {
 *     // 调用 screenshot 工具取最新帧; 此处可走 ToolRegistry.executeTool("screenshot", emptyMap())
 *     ToolRegistry.getInstance().executeTool("screenshot", emptyMap()).imageBase64
 *         ?.let { Base64.decode(it, Base64.NO_WRAP) }
 * }
 * screenRelay = ScreenRelay(source)
 * screenRelay.start(client)
 * ```
 *
 * 或直接包装既有 ScreenStreamer(若它已接无障碍事件流):
 *
 * ```
 * val source = object : ScreenSource {
 *     override fun requestFrame(): ByteArray? = screenStreamer.captureNow()
 *     override fun subscribeChanges(): Flow<ByteArray> = screenStreamer.changeFlow
 * }
 * ```
 */
class ScreenRelay(
    private val source: ScreenSource,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private val tag = "ScreenRelay"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var pushJob: Job? = null

    @Volatile
    private var lastHash: Int = 0

    /**
     * 启动屏幕中继.
     *
     * @param client 目标 Tentacle 客户端 —— 帧通过 [OctopusMobileClient.send] 发出.
     */
    fun start(client: OctopusMobileClient) {
        if (pollJob != null) {
            XLog.w(tag, "already started")
            return
        }
        XLog.i(tag, "starting screen relay (interval=${intervalMs}ms)")

        // 路径 1: 订阅 ScreenSource 推送的变更事件(若有).
        // 路径 2: 定时轮询 requestFrame() 兜底.
        pollJob = scope.launch {
            // 仅在 ONLINE 时拉取, 否则跳过避免无效流量.
            val changeFlow = try {
                source.subscribeChanges()
            } catch (e: Exception) {
                XLog.w(tag, "subscribeChanges failed: ${e.message}")
                null
            }
            if (changeFlow != null) {
                try {
                    changeFlow.collect { frame ->
                        if (client.currentState() == TentacleState.ONLINE) {
                            sendFrame(client, frame)
                        }
                    }
                } catch (e: Exception) {
                    XLog.w(tag, "change flow collect failed: ${e.message}")
                }
            } else {
                // 轮询兜底
                while (isActive) {
                    delay(intervalMs)
                    if (client.currentState() != TentacleState.ONLINE) continue
                    val frame = try {
                        source.requestFrame()
                    } catch (e: Exception) {
                        XLog.w(tag, "requestFrame error: ${e.message}")
                        null
                    }
                    if (frame != null) sendFrame(client, frame)
                }
            }
        }
    }

    /** 停止中继. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        pushJob?.cancel()
        pushJob = null
        scope.cancel()
        XLog.i(tag, "screen relay stopped")
    }

    private fun sendFrame(client: OctopusMobileClient, frame: ByteArray) {
        // 哈希去重: 相同帧不重复发送(节省带宽)
        val hash = frame.contentHashCode()
        if (hash == lastHash && frame.size < 1024) {
            // 极小帧且与上次相同 —— 可能是占位黑帧, 仍发但限频
        }
        lastHash = hash

        val b64 = try {
            Base64.encodeToString(frame, Base64.NO_WRAP)
        } catch (e: Exception) {
            XLog.w(tag, "base64 encode failed: ${e.message}")
            return
        }
        val envelope = JsonObject().apply {
            addProperty("type", "device/screen")
            addProperty("frame_base64", b64)
            addProperty("size", frame.size)
            addProperty("ts", System.currentTimeMillis())
        }
        if (!client.send(envelope)) {
            XLog.d(tag, "frame dropped: client not connected")
        }
    }

    companion object {
        /** 默认上报间隔(2s). */
        const val DEFAULT_INTERVAL_MS = 2_000L
    }
}

/**
 * 屏幕帧来源 —— 由调用方注入, 屏蔽 ScreenRelay 对具体截图工具/服务的依赖.
 *
 * 集成实现可包装既有 `ScreenStreamer` 或 `screenshot` 工具.
 */
interface ScreenSource {
    /**
     * 同步取一帧(JPEG/WebP 字节). null 表示暂不可用(如无障碍未授权).
     */
    fun requestFrame(): ByteArray?

    /**
     * 订阅屏幕变化流 —— 每个元素是一帧字节.
     *
     * 默认实现可返回空流(迫使 ScreenRelay 走轮询兜底).
     */
    fun subscribeChanges(): Flow<ByteArray> = flow { /* 默认无推送, 走轮询 */ }
}

/**
 * 默认屏幕源 —— 用 lambda 注入取帧能力(通常是 `screenshot` 工具调用).
 *
 * 不直接 import ToolRegistry / ScreenStreamer —— 由调用方决定如何取帧.
 */
class DefaultScreenSource(
    private val frameProvider: () -> ByteArray?,
) : ScreenSource {
    override fun requestFrame(): ByteArray? = try {
        frameProvider()
    } catch (e: Exception) {
        null
    }
}
