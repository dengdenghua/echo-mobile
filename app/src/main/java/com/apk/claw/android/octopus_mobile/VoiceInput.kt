package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.apk.claw.android.tool.localmodel.MnnWhisperEngine

/**
 * 语音优先输入 —— 包住 Android [SpeechRecognizer] 或 MNN 端侧 ASR,做中文连续识别。
 *
 * 设计为「按住说话」:
 *   - [start] 开始聆听,实时通过 onPartial 回吐部分识别结果
 *   - 用户松手 → 调 [stop] 让识别器收尾 → 触发 onResult(最终文本)
 *   - 出错 / 没听清 → onError
 *
 * 双引擎支持(用户可在设置切换):
 *  - 系统 [SpeechRecognizer]:依赖 Google 服务,实时部分结果,云端识别质量高
 *  - [MnnWhisperEngine]:完全离线,无网可用,隐私音频不出设备(Whisper-tiny 中文 80% 准确率)
 *
 * 引擎选择策略:
 *  - 用户开启"端侧 ASR"且 MNN Whisper 模型已加载 → 走 MNN
 *  - 否则 → 走系统 SpeechRecognizer(默认,向后兼容)
 *
 * 注意:[SpeechRecognizer] 必须在主线程创建与调用;Compose 手势回调本就在主线程。
 */
class VoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var usingMnn = false

    /** 设备是否具备语音识别能力(系统引擎或 MNN 引擎任一可用即返回 true)。 */
    fun isAvailable(): Boolean {
        if (MnnWhisperEngine.isLocalAsrEnabled() && MnnWhisperEngine.isModelLoaded()) {
            return true
        }
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * 开始聆听。
     * @param onPartial 实时部分结果(可能多次回调)
     * @param onResult  最终结果(onResults 或松手收尾后)
     * @param onError   失败原因(中文)
     * @param onRms     音量电平 dB(用于动效,可选)
     */
    fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRms: (Float) -> Unit = {},
    ) {
        // 引擎选择:用户启用端侧 ASR 且模型已加载 → 走 MNN;否则走系统
        if (MnnWhisperEngine.isLocalAsrEnabled() && MnnWhisperEngine.isModelLoaded()) {
            startMnn(onPartial, onResult, onError, onRms)
            return
        }
        startSystem(onPartial, onResult, onError, onRms)
    }

    private fun startMnn(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRms: (Float) -> Unit,
    ) {
        if (!MnnWhisperEngine.isAvailable()) {
            onError("MNN native 库未加载,无法使用端侧 ASR")
            return
        }
        destroy()
        usingMnn = true
        MnnWhisperEngine.startListening(
            onPartial = onPartial,
            onResult = onResult,
            onError = onError,
            onRms = onRms,
        )
    }

    private fun startSystem(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRms: (Float) -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("此设备不支持语音识别(无 Google 服务,可在设置启用端侧 ASR)")
            return
        }
        // 复用前先彻底释放,避免 ERROR_RECOGNIZER_BUSY
        destroy()
        usingMnn = false

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.w(TAG, "speech error: $error")
                onError(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { r.startListening(intent) }
            .onFailure { onError(it.message ?: "语音启动失败") }
    }

    /** 用户松手:让识别器停止采音并收尾,最终结果走 onResults。 */
    fun stop() {
        if (usingMnn) {
            MnnWhisperEngine.stopListening()
        } else {
            runCatching { recognizer?.stopListening() }
        }
    }

    /** 取消本次识别(不产出结果)。 */
    fun cancel() {
        if (usingMnn) {
            MnnWhisperEngine.cancel()
        } else {
            runCatching { recognizer?.cancel() }
        }
    }

    /** 释放识别器资源。 */
    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        // MNN 引擎不需要每次释放,模型保留以便下次复用
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "" // 通常是 cancel/快速松手,静默
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清,再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙,稍等"
        SpeechRecognizer.ERROR_SERVER -> "语音服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
        else -> "识别失败($code)"
    }

    companion object {
        private const val TAG = "VoiceInput"
    }
}
