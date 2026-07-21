package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.tool.localmodel.MnnJni
import com.apk.claw.android.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

/**
 * MNN(Android)本地 LLM 后端客户端 —— 实现 [LlmClient],桥接到 [MnnJni] native 推理。
 *
 * 集成阶段需在 build.gradle.kts 添加依赖:
 * ```
 * implementation("com.alibaba.mnn:mnn-android:1.3.0")
 * ```
 * 并在 AndroidManifest.xml 声明独立进程(可选,隔离 native crash):
 * ```
 * <service android:name=".agent.llm.MnnInferenceService"
 *          android:process=":mnn" />
 * ```
 *
 * 设计要点:
 *  - 当前实现为同进程兜底模式:直接调用 [MnnJni] 的 native 方法。
 *    集成阶段可改造为独立进程 + AIDL/Messenger 通讯,本类仅保留契约。
 *  - 模型路径优先用构造参数,缺省时回退到 [KVUtils.getActiveLocalModel]。
 *  - chat template 优先用预置模型(由 name 匹配 [MnnModelPresets])的 template;
 *    找不到则降级为 ChatML 通用模板。
 *  - 不支持 function calling:toolSpecs 参数被忽略,返回空 toolExecutionRequests,
 *    与 `LocalLlmClient`(llama.cpp)行为一致,由上层自然降级为纯对话。
 *  - 不支持视觉:UserMessage 中的 ImageContent 由 [MnnChatTemplate] 丢弃。
 */
class MnnLlmClient(
    private val modelPath: String,         // filesDir/models/mnn/Qwen2-1.5B/
    private val configPath: String,        // filesDir/models/mnn/Qwen2-1.5B/config.json
    private val options: MnnOptions = MnnOptions(),
    private val chatTemplate: String = defaultChatTemplate(modelPath),
) : LlmClient {

    companion object {
        private const val TAG = "MnnLlmClient"
        private const val CONTEXT_SIZE = 4096
        private const val GPU_LAYERS_CPU = 0
        private const val STOP_QWEN = "<|im_end|>"
        private const val STOP_LLAMA = "<|eot_id|>"

        /** 模型句柄缓存(同进程兜底模式下复用,避免每次 chat 都 reload)。 */
        @Volatile
        private var cachedModelPtr: Long = 0L

        @Volatile
        private var cachedModelPath: String? = null

        /**
         * 根据 modelPath 推断默认 chat template。
         * 命名含 "Llama3" → 用 Llama3 template,否则默认 ChatML(Qwen2 系)。
         */
        private fun defaultChatTemplate(modelPath: String): String {
            val name = modelPath.substringAfterLast('/').lowercase()
            return MnnModelPresets.ALL.firstOrNull { name.contains(it.name.lowercase()) }
                ?.chatTemplate
                ?: "<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"
        }

        /** 根据 chat template 推断停止字符串。 */
        private fun inferStop(template: String): String {
            return when {
                template.contains("<|eot_id|>") -> STOP_LLAMA
                else -> STOP_QWEN
            }
        }
    }

    override val supportsVision: Boolean = false

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        if (!MnnJni.isAvailable() && !MnnJni.ensureLoaded()) {
            throw IllegalStateException("MNN 引擎未就绪(libmnn-jni.so 未加载)。请先编译 native 库。")
        }
        if (modelPath.isBlank()) {
            throw IllegalStateException("未设置 MNN 模型路径。请在设置→本地大模型 选择 MNN 模型目录。")
        }

        val ptr = ensureModelLoaded()
        if (ptr <= 0L) {
            throw IllegalStateException("MNN 模型加载失败: $modelPath")
        }

        val prompt = MnnChatTemplate.render(chatTemplate, messages)
        XLog.d(TAG, "MNN chat: ${prompt.length} chars prompt, model=${modelPath.takeLast(40)}")

        val stop = inferStop(chatTemplate)
        val result = try {
            MnnJni.nativeCompleteLlm(
                modelPtr = ptr,
                prompt = prompt,
                maxTokens = options.maxTokens,
                temperature = options.temperature,
                topP = options.topP,
                stopStr = stop,
            )
        } catch (e: Throwable) {
            XLog.e(TAG, "MNN inference failed", e)
            throw IllegalStateException("MNN 推理失败: ${e.message}", e)
        }

        XLog.d(TAG, "MNN response: ${result.length} chars")
        return LlmResponse(
            text = result,
            toolExecutionRequests = emptyList(),
            tokenUsage = null,
        )
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        cancelToken: CancellationToken?,
    ): LlmResponse {
        // MVP:与 LocalLlmClient 一致,本地推理不真流式(单次 complete 拿完整文本),
        //     一次性回调。真流式需扩展 MnnJni 增加逐 token 回调接口。
        return try {
            cancelToken?.checkCancelled()
            val response = chat(messages, toolSpecs)
            cancelToken?.checkCancelled()
            val text = response.text ?: ""
            if (text.isNotEmpty()) {
                listener.onPartialText(text)
            }
            listener.onComplete(response)
            response
        } catch (e: Throwable) {
            listener.onError(e)
            throw e
        }
    }

    // ── 内部 ──

    private fun ensureModelLoaded(): Long {
        val cached = cachedModelPtr
        if (cached > 0L && cachedModelPath == modelPath) return cached
        // 释放旧句柄(若 path 变了)
        if (cached > 0L) {
            runCatching { MnnJni.nativeFreeLlm(cached) }
            cachedModelPtr = 0L
            cachedModelPath = null
        }
        val ptr = MnnJni.nativeLoadLlm(
            modelDir = modelPath,
            contextSize = CONTEXT_SIZE,
            gpuLayers = options.backend.gpuLayersCode(),
        )
        if (ptr > 0L) {
            cachedModelPtr = ptr
            cachedModelPath = modelPath
        }
        return ptr
    }

    /** MnnBackend → native gpuLayers 整数编码(0=CPU,>0 表示 GPU 后端)。 */
    private fun MnnBackend.gpuLayersCode(): Int = when (this) {
        MnnBackend.CPU -> GPU_LAYERS_CPU
        MnnBackend.OPENCL -> 1
        MnnBackend.VULKAN -> 2
        MnnBackend.AUTO -> 1
    }
}
