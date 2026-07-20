package com.apk.claw.android.wakeword

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.apk.claw.android.utils.XLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 唤醒词引擎接口 —— 可插拔设计。
 *
 * 实现:
 *  - [EnergyVadEngine] —— demo 引擎(零依赖,基于 RMS 能量阈值,任何响声触发,用于打通基础设施)
 *  - (预留) OpenWakeWordEngine / PorcupineEngine —— 接入 onnxruntime / Picovoice SDK 后启用真正关键词识别
 *
 * 引擎契约:
 *  - [start] 阻塞调用方必须已确保 RECORD_AUDIO 已授权
 *  - 引擎自行管理 AudioRecord 生命周期与采集线程
 *  - 命中后通过 [onDetected] 回调(引擎应自带防抖,不要在 1 秒内重复回调)
 *  - [stop] 必须非阻塞且幂等,释放 AudioRecord
 */
interface WakeWordEngine {

    /** 引擎 ID(用于持久化选择)。 */
    val id: String

    /** 显示名。 */
    val displayName: String

    /**
     * 开始监听。
     *
     * @param keyword 唤醒词文本(demo 引擎忽略,真正 KWS 引擎据此选模型)
     * @param sensitivity 灵敏度档位
     * @param onDetected 命中回调(引擎保证防抖)
     */
    fun start(keyword: String, sensitivity: WakeWordSettings.Sensitivity, onDetected: () -> Unit)

    /** 非阻塞停止;幂等。 */
    fun stop()

    /** 是否正在运行。 */
    fun isRunning(): Boolean
}

/**
 * Demo 引擎 —— 基于 RMS 能量阈值的「响声触发」唤醒。
 *
 * 限制(明确告知用户):
 *  - 不真正识别唤醒词,任何超过阈值的响声(拍手 / 大声说话 / 撞击)都会触发
 *  - 适合"演示唤醒词基础设施是否通"和"轻量省电场景"
 *  - 真正关键词识别需切换到 OpenWakeWord / Porcupine 引擎
 *
 * 实现:
 *  - 16k / mono / PCM16 采集(参考 [com.apk.claw.android.voice.realtime.AudioIn] 骨架)
 *  - 每 100ms 帧计算 RMS,连续 N 帧超阈值 = 命中
 *  - 内置 [cooldownMs] 防抖:命中后该时长内不再触发
 *  - 灵敏度档位对应不同阈值(LOW/MEDIUM/HIGH 反向映射到阈值 HIGH/MEDIUM/LOW dB)
 */
class EnergyVadEngine : WakeWordEngine {

    override val id: String = WakeWordSettings.ENGINE_ENERGY_VAD
    override val displayName: String = "响声触发(演示引擎)"

    private companion object {
        const val TAG = "EnergyVadEngine"
        const val SAMPLE_RATE = 16000
        const val BUFFER_MS = 100
        const val BYTES_PER_FRAME = 2
        const val FRAME_BYTES = BYTES_PER_FRAME * SAMPLE_RATE * BUFFER_MS / 1000 // 3200
        /** 连续多少帧超阈值才认为是人声(而非瞬时噪声)。 */
        const val CONSECUTIVE_FRAMES = 3
        /** 命中后冷却时长(防抖,避免连续触发)。 */
        const val COOLDOWN_MS = 5000L
    }

    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    /** 灵敏度 → RMS 阈值(dB 满量程 0 ~ -90,常见说话 -30 ~ -20)。 */
    private fun thresholdDb(sensitivity: WakeWordSettings.Sensitivity): Double = when (sensitivity) {
        WakeWordSettings.Sensitivity.HIGH -> -38.0   // 高灵敏:小声也能触发(易误触)
        WakeWordSettings.Sensitivity.MEDIUM -> -30.0 // 中等:正常说话音量触发
        WakeWordSettings.Sensitivity.LOW -> -22.0    // 低灵敏:需要较大声响(防误触)
    }

    /** RMS(dB 满量程)从 PCM16 byte 缓冲计算。 */
    internal fun computeRmsDb(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return -100.0
        var sum = 0.0
        for (s in pcm) {
            val v = s.toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / pcm.size)
        // 转 dB 满量程(0dB = 满幅 32767,-90dB = 极静)
        if (rms <= 0.0) return -100.0
        return 20.0 * kotlin.math.log10(rms / 32767.0)
    }

    /** 把 16-bit little-endian byte[] 转 short[]。供测试调用。 */
    internal fun bytesToShorts(bytes: ByteArray, length: Int): ShortArray {
        val n = length / 2
        val out = ShortArray(n)
        var i = 0
        var j = 0
        while (i + 1 < length) {
            // little-endian
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            out[j] = ((hi shl 8) or lo).toShort()
            i += 2
            j++
        }
        return out
    }

    @Suppress("MissingPermission") // 调用方(Service)负责确认权限
    override fun start(
        keyword: String,
        sensitivity: WakeWordSettings.Sensitivity,
        onDetected: () -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        val threshold = thresholdDb(sensitivity)
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, FRAME_BYTES)
        val rec = try {
            AudioRecord(
                // MIC 而非 VOICE_COMMUNICATION:wake word 不需要 AEC(没有扬声器回采要消)
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (e: IllegalArgumentException) {
            XLog.e(TAG, "AudioRecord 构造失败: ${e.message}")
            running.set(false)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            XLog.e(TAG, "AudioRecord 初始化失败(权限或被占用)")
            rec.release()
            running.set(false)
            return
        }
        rec.startRecording()
        worker = thread(name = "WakeWordEnergyVad") {
            val buf = ByteArray(FRAME_BYTES)
            var overThreshold = 0
            var lastHitAt = 0L
            try {
                while (running.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> {
                            val shorts = bytesToShorts(buf, n)
                            val rms = computeRmsDb(shorts)
                            if (rms >= threshold) {
                                overThreshold++
                                // 连续 N 帧超阈值 + 冷却已过 → 触发
                                if (overThreshold >= CONSECUTIVE_FRAMES &&
                                    System.currentTimeMillis() - lastHitAt > COOLDOWN_MS
                                ) {
                                    lastHitAt = System.currentTimeMillis()
                                    overThreshold = 0
                                    XLog.i(TAG, "Wake word detected (RMS=${"%.1f".format(rms)}dB ≥ ${"%.1f".format(threshold)}dB)")
                                    onDetected()
                                }
                            } else {
                                overThreshold = 0
                            }
                        }
                        n < 0 -> { XLog.e(TAG, "AudioRecord.read 错误码 $n"); break }
                        else -> { /* n==0:无数据,继续 */ }
                    }
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
    }

    override fun stop() {
        running.set(false)
        worker = null
    }

    override fun isRunning(): Boolean = running.get()
}
