package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.*

/**
 * 方案 F · 心跳上报器.
 *
 * 周期性（默认 30s）向 octopus-agent Runtime 推送设备状态：
 *  - 电量 + 是否充电
 *  - 当前前台应用（从无障碍服务取）
 *  - 最近一次屏幕树哈希（去重 + 心跳包轻量化）
 *  - 在线时间戳
 *
 * 设计要点：
 *  - 与 ConnectionState 联动：仅在 ONLINE 时发送
 *  - 断线时不发浪费（ConnectionStateMachine 文档残留已废弃,实际退避由 OctopusMobileClient 内置全抖动算法承担）
 *  - 后台协程：SupervisorJob 隔离异常，避免影响主流程
 *  - 极简 payload：< 1KB
 */
class HeartbeatReporter(
    private val context: Context,
    private val client: OctopusMobileClient,
    private val intervalMs: Long = 30_000L,
) {
    private val tag = "HeartbeatReporter"

    /** 上次屏幕树哈希（由 ScreenStreamer 写入） */
    @Volatile
    var lastScreenHash: String? = null

    /** 当前前台应用（由 ScreenStreamer / Agent 写入） */
    @Volatile
    var currentApp: String? = null

    private var heartbeatJob: Job? = null

    /** start() 传入的 scope,stop() 时取消以避免协程泄漏 */
    private var scope: CoroutineScope? = null

    /** 上次收到母体 ACK 的时间戳（用于检测母体僵死） */
    @Volatile
    private var lastAckReceivedAt: Long = 0L

    /** 连续未收到 ACK 的次数 */
    @Volatile
    private var missedAcks: Int = 0

    /** 连续 N 次未收到 ACK 则判定母体僵死，触发重连 */
    private val maxMissedAcks = 3

    /** ACK 超时窗口：超过此时间未收到 ACK 判定为丢失 */
    private val ackTimeoutMs = intervalMs * 2

    /**
     * 启动心跳循环.
     */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        stop()
        this.scope = scope
        // 注册 ACK 回调
        client.onHeartbeatAck = {
            lastAckReceivedAt = System.currentTimeMillis()
            missedAcks = 0
            XLog.d(tag, "heartbeat ACK received")
        }
        heartbeatJob = scope.launch {
            lastAckReceivedAt = System.currentTimeMillis()
            // 立即发一次，缩短首次感知延迟
            sendOnce()
            while (isActive) {
                delay(intervalMs)
                // 检查 ACK 超时 → 强制重连。
                // 跨仓协议依赖:仅当母体 octopus-agent runtime 确实回 {"method":"heartbeat/ack"} 时才有意义。
                // 本仓只消费 ack(OctopusMobileClient.onHeartbeatAck),不产生 ack;若母体不回 ack 而开启此逻辑,
                // missedAcks 会每窗口累计到上限并 forceReconnect,导致每台设备每 ~90s 强制重连一次的自我风暴。
                // 故默认关闭(KVUtils.isHeartbeatAckReconnectEnabled),确认母体支持后再开启。关闭时仍正常发心跳。
                if (KVUtils.isHeartbeatAckReconnectEnabled()) {
                    val timeSinceAck = System.currentTimeMillis() - lastAckReceivedAt
                    if (timeSinceAck > ackTimeoutMs) {
                        missedAcks++
                        XLog.w(tag, "heartbeat ACK timeout ($missedAcks/$maxMissedAcks), ${timeSinceAck}ms since last ACK")
                        if (missedAcks >= maxMissedAcks) {
                            XLog.e(tag, "Parent runtime appears unresponsive ($maxMissedAcks missed ACKs), forcing reconnect")
                            missedAcks = 0
                            // 主动断开触发重连（OctopusMobileClient 的 onFailure/onClosed 会处理重连）
                            client.forceReconnect()
                            lastAckReceivedAt = System.currentTimeMillis()
                            continue
                        }
                    }
                }
                sendOnce()
            }
        }
        XLog.i(tag, "HeartbeatReporter started (interval=${intervalMs}ms, ackTimeout=${ackTimeoutMs}ms)")
    }

    /**
     * 停止心跳.
     */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        scope?.cancel()
        scope = null
        client.onHeartbeatAck = null
    }

    /**
     * 主动发一次心跳（外部可触发，如状态切换后立即上报）.
     */
    suspend fun sendOnce() {
        if (client.currentState() != ConnectionState.ONLINE) return

        val battery = readBatteryLevel()
        val isCharging = readIsCharging()
        val envelope = EnvelopeFactory.heartbeat(
            tentacleId = "",  // 在 OctopusMobileClient.send 时会自动填入
            currentApp = currentApp ?: detectCurrentApp(),
            battery = battery,
            isCharging = isCharging,
            screenTreeHash = lastScreenHash,
        )
        try {
            client.send(envelope)
            XLog.d(tag, "heartbeat sent: battery=$battery% charging=$isCharging app=$currentApp")
        } catch (e: Exception) {
            XLog.w(tag, "heartbeat send failed: ${e.message}")
        }
    }

    /**
     * 读取电池电量（0-100）.
     */
    private fun readBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 读取是否在充电.
     */
    private fun readIsCharging(): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter) ?: return false
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测当前前台应用.
     *
     * 优先从无障碍服务取（更准确），
     * 失败时回退到 UsageStatsManager（需权限）或返回 null.
     */
    private fun detectCurrentApp(): String? {
        return try {
            val root = ClawAccessibilityService.getInstance()?.rootInActiveWindow
                ?: return null
            try {
                root.packageName?.toString()
            } finally {
                root.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }
}
