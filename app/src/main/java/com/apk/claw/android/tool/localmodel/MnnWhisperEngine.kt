package com.apk.claw.android.tool.localmodel

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.apk.claw.android.utils.KVUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * MNN Whisper 端侧 ASR 引擎 —— 用 MNN 转换后的 Whisper 模型做离线语音识别。
 *
 * 替代 [android.speech.SpeechRecognizer]:
 *  - 优势:完全离线,无网可用;隐私音频不出设备;无 Google 服务依赖(国产 ROM 友好)
 *  - 劣势:模型质量略低(Whisper-tiny 150MB,中文识别率约 80%);无实时流式(需缓冲 1-3s)
 *
 * 工作流程:
 *  1. [loadModel] 加载 MNN Whisper 模型目录
 *  2. [startListening] 启动 AudioRecord 16k/mono/PCM16 采集(与 [com.apk.claw.android.voice.realtime.AudioIn] 同规格)
 *  3. 缓冲 [CHUNK_MS] 毫秒音频 → 调 [MnnJni.nativeTranscribe] 转写
 *  4. 通过 [onPartial] 回调实时推送识别结果(拼接多 chunk)
 *  5. [stopListening] 触发最后一次转写,通过 [onResult] 回调最终文本
 *
 * 模型准备:
 *  - 下载 whisper-tiny-base 原 ONNX/PyTorch 模型
 *  - 用 MNN Convert 工具转换为 .mnn 格式
 *  - 打包成目录:embedding.mnn + encoder.mnn + decoder.mnn + tokenizer.json + config.json
 *  - 放到 /sdcard/OctopusMobile/models/whisper-tiny/ 或通过 SAF 选择
 *
 * 持久化:
 *  - 模型路径存 KVUtils KEY_ACTIVE_WHISPER_MODEL
 *  - 是否启用端侧 ASR 存 KEY_USE_LOCAL_ASR
 */
object MnnWhisperEngine {

    private const val TAG = "MnnWhisperEngine"
    const val KEY_ACTIVE_WHISPER_MODEL = "KEY_ACTIVE_WHISPER_MODEL"
    const val KEY_USE_LOCAL_ASR = "KEY_USE_LOCAL_ASR"

    /** 16k mono PCM16,与 AudioIn 一致(Whisper 输入要求)。 */
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    /** 单次转写 chunk 长度(毫秒)。Whisper 模型一次最多 30s,这里取 2s 平衡延迟与准确率。 */
    const val CHUNK_MS = 2000
    private const val CHUNK_SHORTS = SAMPLE_RATE * CHUNK_MS / 1000 // 32000 shorts = 2s

    @Volatile private var modelPtr: Long = 0L
    @Volatile private var modelPath: String = ""
    private val recording = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    /** Whisper 模型是否已加载就绪。 */
    fun isModelLoaded(): Boolean = modelPtr != 0L

    /** native 库是否可用(未编译返回 false)。 */
    fun isAvailable(): Boolean = MnnJni.isAvailable()

    /** 获取已配置的 Whisper 模型路径(可能未加载)。 */
    fun getConfiguredModelPath(): String = KVUtils.getString(KEY_ACTIVE_WHISPER_MODEL, "")

    /** 用户是否启用了端侧 ASR(替代系统 SpeechRecognizer)。 */
    fun isLocalAsrEnabled(): Boolean = KVUtils.getBoolean(KEY_USE_LOCAL_ASR, false)

    fun setLocalAsrEnabled(enabled: Boolean) {
        KVUtils.putBoolean(KEY_USE_LOCAL_ASR, enabled)
    }

    /**
     * 加载 Whisper 模型。
     * @param modelDir 模型目录(含 .mnn + tokenizer + config)
     * @return true 成功
     */
    fun loadModel(modelDir: String): Boolean {
        if (!MnnJni.isAvailable()) {
            Log.w(TAG, "MNN native lib not available")
            return false
        }
        val dir = File(modelDir)
        if (!dir.isDirectory) {
            Log.w(TAG, "Not a directory: $modelDir")
            return false
        }
        unloadModel()
        return try {
            val ptr = MnnJni.nativeLoadWhisper(modelDir)
            if (ptr == 0L) {
                Log.w(TAG, "nativeLoadWhisper returned 0")
                return false
            }
            modelPtr = ptr
            modelPath = modelDir
            KVUtils.putString(KEY_ACTIVE_WHISPER_MODEL, modelDir)
            Log.i(TAG, "Whisper model loaded: ${dir.name} (ptr=$ptr)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Whisper model", e)
            false
        }
    }

    /** 卸载 Whisper 模型。 */
    fun unloadModel() {
        if (modelPtr != 0L) {
            try { MnnJni.nativeFreeWhisper(modelPtr) } catch (_: Throwable) {}
            modelPtr = 0L
            modelPath = ""
        }
    }

    /**
     * 开始监听麦克风并实时转写。
     *
     * @param onPartial 部分(累计)文本回调,每次 chunk 转写后调用
     * @param onResult 最终文本(stopListening 后调用)
     * @param onError 错误回调
     * @param onRms 音量电平(dB),供动效用
     */
    @Suppress("MissingPermission") // 调用方负责确保 RECORD_AUDIO 已授权
    fun startListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRms: (Float) -> Unit = {},
    ) {
        if (!isModelLoaded()) {
            onError("Whisper 模型未加载")
            return
        }
        if (!recording.compareAndSet(false, true)) {
            onError("已在监听中")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, CHUNK_SHORTS * 2)
        val rec = try {
            AudioRecord(
                // MIC 而非 VOICE_COMMUNICATION:ASR 不需 AEC(没有扬声器回采要消)
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING, bufSize,
            )
        } catch (e: IllegalArgumentException) {
            recording.set(false)
            onError("AudioRecord 构造失败: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            recording.set(false)
            rec.release()
            onError("AudioRecord 初始化失败")
            return
        }

        val accumulated = StringBuilder()
        rec.startRecording()
        worker = thread(name = "MnnWhisperAsr") {
            val buf = ShortArray(CHUNK_SHORTS)
            try {
                while (recording.get()) {
                    // 一次读 2s 音频
                    var read = 0
                    while (read < CHUNK_SHORTS && recording.get()) {
                        val n = rec.read(buf, read, CHUNK_SHORTS - read)
                        if (n <= 0) break
                        read += n
                        // RMS 计算(取最近 100ms 估音量,粗略)
                        val sampleEnd = minOf(read, 1600)
                        if (sampleEnd > 0) {
                            var sum = 0.0
                            for (i in 0 until sampleEnd) {
                                val v = buf[i].toDouble()
                                sum += v * v
                            }
                            val rms = Math.sqrt(sum / sampleEnd)
                            val db = if (rms > 0) 20 * Math.log10(rms / 32767) else -100.0
                            onRms(db.toFloat())
                        }
                    }
                    if (read == 0) continue

                    // 调 native 转写
                    val chunk = if (read == CHUNK_SHORTS) buf else buf.copyOf(read)
                    val text = try {
                        MnnJni.nativeTranscribe(modelPtr, chunk)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Transcribe failed: ${e.message}")
                        ""
                    }
                    if (text.isNotBlank()) {
                        accumulated.append(text)
                        onPartial(accumulated.toString())
                    }
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                onResult(accumulated.toString())
            }
        }
    }

    /** 用户松手:停止采集,触发最终 onResult 回调。 */
    fun stopListening() {
        recording.set(false)
        // worker 退出循环后会在 finally 中调 onResult
    }

    /** 取消本次识别(不产出结果)。 */
    fun cancel() {
        recording.set(false)
        worker = null
    }
}
