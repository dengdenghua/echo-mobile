package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.tool.localmodel.LocalModelManager
import com.apk.claw.android.tool.localmodel.LlamaJni
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * 本地 LLM 客户端 —— 把 LlmClient 接口桥接到 LocalModelManager(llama.cpp)。
 *
 * 用途：离线模式下作为主对话 LLM,无需联网。
 *
 * 限制(已知,UI 应明确告知用户):
 *  - 不支持 function calling:toolSpecs 参数被忽略,返回空 toolExecutionRequests。
 *    DefaultAgentService 收到空 toolCalls 后会走 onComplete 路径,自然降级为纯对话(无设备控制)。
 *  - 不支持视觉:UserMessage 中的 ImageContent 被丢弃,仅保留 TextContent。
 *  - 需要前置条件:libllama-jni.so 已编译 + .gguf 模型已加载。
 *
 * 消息序列化采用 ChatML 格式(Qwen2.5 / Llama-3 / Phi-3.5 通用):
 *  <|im_start|>role\ncontent<|im_end|>
 * 末尾追加 <|im_start|>assistant\n 让模型续写。
 */
class LocalLlmClient(
    private val config: AgentConfig,
) : LlmClient {

    companion object {
        private const val TAG = "LocalLlmClient"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.7f
        private const val TOP_P = 0.9f
        private const val STOP = "<|im_end|>"
    }

    override val supportsVision: Boolean = false

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        if (!LlamaJni.isAvailable()) {
            throw IllegalStateException("本地模型引擎未就绪(libllama-jni.so 未加载)。请在设置→本地大模型检查。")
        }

        val modelPath = KVUtils.getActiveLocalModel().ifBlank {
            throw IllegalStateException("未设置活跃本地模型。请在设置→本地大模型 选择 .gguf 文件。")
        }

        if (!LocalModelManager.isModelLoaded(modelPath)) {
            val loadResult = LocalModelManager.loadModel(modelPath)
            if (loadResult.isFailure) {
                throw IllegalStateException("模型加载失败: ${loadResult.exceptionOrNull()?.message}")
            }
        }

        val prompt = toChatML(messages)
        XLog.d(TAG, "Local LLM call: ${prompt.length} chars prompt, model=${modelPath.takeLast(40)}")

        val result = LocalModelManager.complete(
            modelPath = modelPath,
            prompt = prompt,
            maxTokens = MAX_TOKENS,
            temperature = TEMPERATURE,
            topP = TOP_P,
            stopStr = STOP,
        )

        return result.fold(
            onSuccess = { text ->
                XLog.d(TAG, "Local LLM response: ${text.length} chars")
                LlmResponse(
                    text = text,
                    toolExecutionRequests = emptyList(),
                    tokenUsage = null,
                )
            },
            onFailure = { e ->
                XLog.e(TAG, "Local LLM inference failed", e)
                throw IllegalStateException("本地推理失败: ${e.message}", e)
            },
        )
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        cancelToken: CancellationToken?,
    ): LlmResponse {
        // 简化实现:本地推理不真流式(单次 complete 拿完整文本),一次性回调。
        // 真流式需要改 LlamaJni 增加逐 token 回调接口,工作量大,MVP 阶段先这样。
        return try {
            val response = chat(messages, toolSpecs)
            val text = response.text ?: ""
            if (text.isNotEmpty()) {
                listener.onPartialText(text)
            }
            listener.onComplete(response)
            response
        } catch (e: Exception) {
            listener.onError(e)
            throw e
        }
    }

    /** 把 LangChain4j ChatMessage 列表序列化为 ChatML 文本。 */
    private fun toChatML(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            val role = when (msg) {
                is SystemMessage -> "system"
                is UserMessage -> "user"
                is AiMessage -> "assistant"
                is ToolExecutionResultMessage -> "user"  // 工具结果作为 user 消息注入
                else -> "user"
            }
            val content = when (msg) {
                is SystemMessage -> msg.text()
                is UserMessage -> msgTextSafe(msg)
                is AiMessage -> msg.text() ?: ""
                is ToolExecutionResultMessage -> msg.text()
                else -> msg.toString()
            }
            if (content.isBlank()) continue
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** UserMessage 可能含 ImageContent,本地模型不支持视觉,只取文本部分。 */
    private fun msgTextSafe(msg: UserMessage): String {
        // 离线模式下 AppViewModel 已关闭 enableVision/enableAutoScreenshot,
        // UserMessage 通常只含 TextContent。多 content 时 filterIsInstance 兜底。
        return runCatching { msg.singleText() }.getOrNull()
            ?: msg.contents()
                .filterIsInstance<dev.langchain4j.data.message.TextContent>()
                .joinToString("\n") { it.text() }
    }
}
