package com.apk.claw.android.tool.localmodel

/**
 * 本地 LLM 推理引擎抽象 —— 让 llama.cpp / MNN / 未来其他引擎在同一接口下可替换。
 *
 * 实现方契约:
 *  - [isAvailable] 必须先于其他方法调用,返回 native 库是否就绪
 *  - [supportsFormat] 用于引擎选择:.gguf → LlamaEngine,.mnn → MnnLlmEngine
 *  - [loadModel] 阻塞调用,返回 >0 句柄成功,0 失败
 *  - [complete] 阻塞调用,由上层用协程调度
 *  - [freeModel] 必须幂等
 *  - 所有方法失败应返回错误而非抛异常(便于 Result 包装)
 */
interface LocalLlmEngine {

    /** 引擎 ID(用于持久化选择 / 日志)。 */
    val id: String

    /** 显示名(给 UI 用)。 */
    val displayName: String

    /** native 库是否已加载就绪(未编译时返回 false,优雅降级)。 */
    fun isAvailable(): Boolean

    /** 该引擎支持的模型文件后缀(小写,如 "gguf" / "mnn")。 */
    fun supportsFormat(): String

    /**
     * 加载模型文件。
     * @param modelPath 模型绝对路径
     * @param contextSize 上下文窗口 tokens
     * @param gpuLayers GPU 层数(0=纯 CPU,移动端通常 0)
     * @return 模型句柄(>0 成功,0 失败)
     */
    fun loadModel(modelPath: String, contextSize: Int, gpuLayers: Int): Long

    /**
     * 执行文本补全推理。
     * @param modelPtr 模型句柄
     * @param prompt 已套用 chat template 的 prompt
     * @param maxTokens 最大生成 tokens
     * @param temperature 采样温度
     * @param topP nucleus sampling
     * @param stopStr 停止字符串
     * @return 生成的文本
     */
    fun complete(
        modelPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopStr: String,
    ): String

    /** 释放模型(幂等)。 */
    fun freeModel(modelPtr: Long)

    /** 获取模型上下文窗口大小。 */
    fun getContextSize(modelPtr: Long): Int

    /** 估算文本 token 数(用于 prompt 长度校验)。 */
    fun tokenCount(modelPtr: Long, text: String): Int
}

/**
 * llama.cpp 引擎适配器 —— 包装 [LlamaJni],实现 [LocalLlmEngine]。
 *
 * 该类不持有状态,所有状态在 native 层 g_models 表中管理。
 */
object LlamaEngine : LocalLlmEngine {

    override val id: String = "llama_cpp"
    override val displayName: String = "llama.cpp(GGUF)"

    override fun isAvailable(): Boolean = LlamaJni.isAvailable()

    override fun supportsFormat(): String = "gguf"

    override fun loadModel(modelPath: String, contextSize: Int, gpuLayers: Int): Long {
        if (!LlamaJni.isAvailable()) return 0L
        return try {
            LlamaJni.nativeLoadModel(modelPath, contextSize, gpuLayers)
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
        return LlamaJni.nativeComplete(modelPtr, prompt, maxTokens, temperature, topP, stopStr)
    }

    override fun freeModel(modelPtr: Long) {
        try { LlamaJni.nativeFreeModel(modelPtr) } catch (_: Throwable) {}
    }

    override fun getContextSize(modelPtr: Long): Int {
        return try { LlamaJni.nativeGetContextSize(modelPtr) } catch (_: Throwable) { 0 }
    }

    override fun tokenCount(modelPtr: Long, text: String): Int {
        return try { LlamaJni.nativeTokenCount(modelPtr, text) } catch (_: Throwable) { 0 }
    }
}
