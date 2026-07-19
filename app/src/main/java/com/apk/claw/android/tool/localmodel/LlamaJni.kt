package com.apk.claw.android.tool.localmodel

import android.util.Log

/**
 * llama.cpp JNI 声明 —— 对应 native/llama-jni.cpp 中实现的 C++ 接口。
 *
 * 构建要求:
 *  1. 在 app/src/main/cpp/ 下放置 llama-jni.cpp + CMakeLists.txt
 *  2. CMakeLists 拉取 llama.cpp 上游源码(git submodule 或 fetchContent)
 *  3. 配置 NDK ABI:arm64-v8a(主)、x86_64(模拟器)
 *  4. 首次构建需 cmake/ndk,产物是 libllama-jni.so 打进 APK
 *
 * 若 native 库未编译/未加载,所有方法返回错误而非崩溃(见 [isAvailable])。
 * 这让 Kotlin 层可以先落地,native 编译在开发者环境独立完成。
 */
object LlamaJni {

    private const val TAG = "LlamaJni"

    @Volatile
    private var nativeLoaded = false

    /**
     * 加载 native 库。失败说明未编译 libllama-jni.so(正常情况,开发者需先跑 build-native.sh)。
     * 幂等:多次调用安全。
     * @return true 若 native 库已就绪
     */
    @Suppress("UnsafeCall")
    fun ensureLoaded(): Boolean {
        if (nativeLoaded) return true
        return try {
            System.loadLibrary("llama-jni")
            nativeLoaded = true
            Log.i(TAG, "libllama-jni.so loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libllama-jni.so not available (native not compiled?): ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load libllama-jni.so: ${e.message}")
            false
        }
    }

    /** native 库是否已加载就绪。 */
    fun isAvailable(): Boolean = nativeLoaded

    // ── JNI 方法声明(对应 llama-jni.cpp) ──────────────────────────────

    /**
     * 加载 GGUF 模型文件。
     * @param modelPath 模型文件绝对路径(.gguf)
     * @param contextSize 上下文窗口大小(tokens),建议 2048-8192
     * @param gpuLayers GPU 层数(0=纯 CPU,-1=全部 GPU)
     * @return 模型句柄(>0 成功,0 失败)
     */
    @JvmStatic
    external fun nativeLoadModel(modelPath: String, contextSize: Int, gpuLayers: Int): Long

    /**
     * 释放模型。
     * @param modelPtr 模型句柄
     */
    @JvmStatic
    external fun nativeFreeModel(modelPtr: Long)

    /**
     * 执行推理(补全)。
     * @param modelPtr 模型句柄
     * @param prompt 输入 prompt(已含 chat template)
     * @param maxTokens 最大生成 token 数
     * @param temperature 采样温度(0.0-2.0)
     * @param topP nucleus sampling top_p(0.0-1.0)
     * @param stopStr 停止字符串(如 "</s>"、"<|im_end|>"),命中则停止
     * @return 生成的文本
     */
    @JvmStatic
    external fun nativeComplete(
        modelPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopStr: String,
    ): String

    /**
     * 获取模型上下文窗口大小。
     */
    @JvmStatic
    external fun nativeGetContextSize(modelPtr: Long): Int

    /**
     * 对文本进行 tokenization(用于计算 prompt token 数)。
     */
    @JvmStatic
    external fun nativeTokenCount(modelPtr: Long, text: String): Int
}
