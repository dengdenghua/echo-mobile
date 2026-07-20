package com.apk.claw.android.tool.localmodel

/**
 * MNN LLM 引擎 —— 实现 [LocalLlmEngine],包装 [MnnJni]。
 *
 * 模型格式:MNN 转换后的 .mnn 模型目录(含 .mnn 权重 + config.json + tokenizer)。
 * 与 [LlamaEngine] 互为替代,由 [LocalModelManager] 根据文件后缀或用户配置选择。
 *
 * 性能优势(对比 llama.cpp):
 *  - 支持 OpenCL/Vulkan GPU 加速(Snapdragon 8 Gen 2 上 Qwen-1.8B 可达 30+ tok/s)
 *  - MNN 算子针对移动端优化(INT8/FP16 混合精度)
 *  - 内存占用更小(MNN 的内存池更紧凑)
 *
 * 局限:
 *  - 模型需先用 MNN Convert 工具从 HuggingFace 转换
 *  - 当前 native 库未编译时,本引擎不可用(返回 false)
 *  - 不支持 function calling(纯文本补全,与 LlamaEngine 一致)
 */
object MnnLlmEngine : LocalLlmEngine {

    override val id: String = "mnn"
    override val displayName: String = "MNN(移动端优化)"

    override fun isAvailable(): Boolean = MnnJni.isAvailable()

    override fun supportsFormat(): String = "mnn"

    override fun loadModel(modelPath: String, contextSize: Int, gpuLayers: Int): Long {
        if (!MnnJni.isAvailable()) return 0L
        // MNN LLM 接受的是模型目录,而非单个文件
        // 调用方传 path 可能是文件或目录,native 层统一处理
        return try {
            MnnJni.nativeLoadLlm(modelPath, contextSize, gpuLayers)
        } catch (_: Throwable) { 0L }
    }

    override fun complete(
        modelPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopStr: String,
    ): String {
        return try {
            MnnJni.nativeCompleteLlm(modelPtr, prompt, maxTokens, temperature, topP, stopStr)
        } catch (_: Throwable) { "" }
    }

    override fun freeModel(modelPtr: Long) {
        try { MnnJni.nativeFreeLlm(modelPtr) } catch (_: Throwable) {}
    }

    override fun getContextSize(modelPtr: Long): Int {
        return try { MnnJni.nativeGetLlmContextSize(modelPtr) } catch (_: Throwable) { 0 }
    }

    override fun tokenCount(modelPtr: Long, text: String): Int {
        return try { MnnJni.nativeLlmTokenCount(modelPtr, text) } catch (_: Throwable) { 0 }
    }
}
