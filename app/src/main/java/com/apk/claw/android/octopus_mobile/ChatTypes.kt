package com.apk.claw.android.octopus_mobile

/**
 * 方案 F · LLM 调用与 ReAct 循环的"消息 / 工具 / 响应"类型定义.
 *
 * 这是 LightweightLlmClient 与 LightweightReAct 之间共享的数据契约.
 * 不依赖任何 LangChain 框架，~100 行.
 */

/** 单条对话消息（与 OpenAI messages 格式对齐）*/
sealed class ChatMessage {
    abstract val role: String

    data class System(override val role: String = "system", val content: String) : ChatMessage()

    data class User(
        override val role: String = "user",
        val content: String,
        /** 单张图片的 base64(JPEG)。向后兼容字段,等价于 [images] = listOf(imageBase64)。
         *  新代码应优先用 [images] 传多图(视频帧序列)。 */
        val imageBase64: String? = null,
        /** 多张图片的 base64 列表(JPEG)。用于视频理解:把多个关键帧一次喂给 VLM。
         *  留空 + [imageBase64] 非空时,自动回退到单图模式。 */
        val images: List<String>? = null,
    ) : ChatMessage()

    data class Assistant(
        override val role: String = "assistant",
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList()
    ) : ChatMessage()

    data class Tool(
        override val role: String = "tool",
        val toolCallId: String,
        val content: String
    ) : ChatMessage()
}

/** LLM 决定调用的工具. */
data class ToolCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>
)

/** LLM 响应. */
data class LlmResponse(
    val content: String? = null,           // 自然语言回复
    val toolCalls: List<ToolCall> = emptyList(),  // 工具调用列表
    val usage: TokenUsage? = null,         // token 用量统计
    val finishReason: String = "stop"      // stop / tool_calls / length / content_filter
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/** Token 用量. */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
) {
    /** 单价（元/千 token，参考 DeepSeek 价格）*/
    fun estimatedCost(cnyPerKToken: Double = 0.001): Double {
        return (promptTokens + completionTokens) / 1000.0 * cnyPerKToken
    }
}

/** SKILL.md 解析后的工具描述（喂给 LLM）*/
data class SkillSpec(
    val id: String,                        // e.g. "android.tap"
    val description: String,               // LLM 看到的功能描述
    val parametersSchema: org.json.JSONObject  // JSON Schema（OpenAI tools 格式）
)

/** 工具执行结果（被 ReAct 循环消费）*/
sealed class ToolExecutionResult {
    abstract val toolCallId: String
    abstract val display: String           // 给人看的简述

    data class Success(
        override val toolCallId: String,
        override val display: String,
        val data: Any? = null
    ) : ToolExecutionResult()

    data class Failure(
        override val toolCallId: String,
        override val display: String,
        val errorCode: Int,
        val errorMessage: String
    ) : ToolExecutionResult()
}

/** 任务执行结果（ReAct 循环结束）*/
sealed class TaskResult {
    /** 任务完成 */
    data class Done(
        val summary: String,
        val totalSteps: Int,
        val totalUsage: TokenUsage
    ) : TaskResult()

    /** 达到最大步数未完成 */
    data class MaxStepsReached(
        val totalSteps: Int,
        val lastResponse: String?,
        val totalUsage: TokenUsage
    ) : TaskResult()

    /** 被取消 */
    data class Cancelled(
        val totalSteps: Int,
        val totalUsage: TokenUsage
    ) : TaskResult()

    /** 死循环检测触发 */
    data class Stuck(
        val totalSteps: Int,
        val totalUsage: TokenUsage,
        val detectedAction: String
    ) : TaskResult()
}
