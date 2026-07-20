package com.apk.claw.android.tool.localmodel

import android.util.Log

/**
 * MNN JNI 声明 —— 对应 native/mnn-jni.cpp(待编译)中实现的 C++ 接口。
 *
 * 构建要求:
 *  1. 在 app/src/main/cpp/ 下放置 mnn-jni.cpp + CMakeLists.txt(扩展)
 *  2. 拉 MNN 源码 https://github.com/alibaba/MNN.git
 *  3. 用 NDK 编译 libMNN.so + libMNN_Express.so + 可选 libMNN_Vulkan.so / libMNN_CL.so
 *  4. 产物 libmnn-jni.so + libMNN*.so 放进 app/src/main/jniLibs/{arm64-v8a,x86_64}/
 *
 * 若 native 库未编译/未加载,所有方法返回错误而非崩溃(见 [isAvailable])。
 * 这让 Kotlin 层可以先落地,native 编译在开发者环境独立完成(见 build-mnn.sh)。
 *
 * MNN LLM 推理 API(参考 MNN/transformers/llm/engine):
 *  - loadModel(modelDir, config) → 句柄  // modelDir 含 .mnn 模型 + config.json + tokenizer
 *  - complete(ptr, prompt, maxTokens, temperature, topP, stopStr) → 文本
 *  - freeModel(ptr)
 *  - tokenCount(ptr, text) → Int
 *
 * ASR(Whisper)API(参考 MNN/modelc_zoo/whisper):
 *  - loadWhisper(modelDir) → 句柄
 *  - transcribe(ptr, pcm16ShortArray) → 文本  // 16k mono PCM16
 *  - freeWhisper(ptr)
 */
object MnnJni {

    private const val TAG = "MnnJni"

    @Volatile
    private var nativeLoaded = false

    /**
     * 加载 native 库。失败说明未编译 libmnn-jni.so(正常情况,需先跑 build-mnn.sh)。
     * 幂等:多次调用安全。
     */
    @Suppress("UnsafeCall")
    fun ensureLoaded(): Boolean {
        if (nativeLoaded) return true
        return try {
            // MNN 主库 + JNI 桥接库,顺序重要:MNN 先加载,JNI 依赖它
            System.loadLibrary("MNN")
            runCatching { System.loadLibrary("MNN_Express") }  // LLM engine 需要 Express
            runCatching { System.loadLibrary("MNN_Vulkan") }  // 可选 GPU 加速
            runCatching { System.loadLibrary("MNN_CL") }      // 可选 OpenCL
            System.loadLibrary("mnn-jni")
            nativeLoaded = true
            Log.i(TAG, "libmnn-jni.so + libMNN*.so loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libmnn-jni.so not available (native not compiled?): ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load libmnn-jni.so: ${e.message}")
            false
        }
    }

    /** native 库是否已加载就绪。 */
    fun isAvailable(): Boolean = nativeLoaded

    // ── LLM 接口 ──

    /**
     * 加载 MNN LLM 模型目录。
     * @param modelDir 模型目录(含 .mnn 权重 + config.json + tokenizer)
     * @param contextSize 上下文窗口(tokens)
     * @param gpuLayers GPU 层(MNN 中实际是 backend 类型选择,0=CPU)
     * @return 模型句柄(>0 成功,0 失败)
     */
    @JvmStatic
    external fun nativeLoadLlm(modelDir: String, contextSize: Int, gpuLayers: Int): Long

    /** 释放 LLM 模型。 */
    @JvmStatic
    external fun nativeFreeLlm(modelPtr: Long)

    /**
     * LLM 文本补全。
     * @param modelPtr 模型句柄
     * @param prompt 已套用 chat template 的 prompt
     * @param maxTokens 最大生成 tokens
     * @param temperature 采样温度
     * @param topP nucleus sampling
     * @param stopStr 停止字符串
     * @return 生成的文本
     */
    @JvmStatic
    external fun nativeCompleteLlm(
        modelPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopStr: String,
    ): String

    /** 获取模型上下文窗口大小。 */
    @JvmStatic
    external fun nativeGetLlmContextSize(modelPtr: Long): Int

    /** 估算文本 token 数。 */
    @JvmStatic
    external fun nativeLlmTokenCount(modelPtr: Long, text: String): Int

    // ── ASR (Whisper) 接口 ──

    /**
     * 加载 MNN Whisper 模型目录。
     * @param modelDir 含 whisper-tiny/tiny-base 等的 .mnn 权重 + config
     * @return 模型句柄(>0 成功,0 失败)
     */
    @JvmStatic
    external fun nativeLoadWhisper(modelDir: String): Long

    /** 释放 Whisper 模型。 */
    @JvmStatic
    external fun nativeFreeWhisper(modelPtr: Long)

    /**
     * 转写 PCM16 音频为文本。
     * @param modelPtr Whisper 模型句柄
     * @param pcm16 16k mono PCM16 short 数组
     * @return 转写文本(失败返回空字符串)
     */
    @JvmStatic
    external fun nativeTranscribe(modelPtr: Long, pcm16: ShortArray): String
}
