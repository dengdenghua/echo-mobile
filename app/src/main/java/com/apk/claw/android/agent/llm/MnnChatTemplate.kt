package com.apk.claw.android.agent.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * MNN 模型 chat template 渲染器。
 *
 * 不同模型有不同的 chat template,例如:
 *  - Qwen2:<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n
 *  - Llama3:<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n{user}<|eot_id|>...
 *
 * 本对象负责把 [ChatMessage] 列表拼成单一 prompt 字符串,供 MNN native 推理。
 *
 * ## 设计
 *
 * 模板代表"一轮 user 消息 + 紧随其后的 assistant 段开头"。即:
 *  - {user} 占位符所在位置 → 当前 user 消息内容
 *  - 占位符之后的部分 → assistant 段开头(让模型续写)
 *
 * 渲染规则:
 * 1. SystemMessage:
 *    - 模板含 {system}:从模板中提取 system 段(从开头到 {system} 后第一个 turn-close),
 *      把 system 文本填入,渲染一次到开头。后续 user 渲染用去除 system 段的精简模板。
 *    - 模板不含 {system}:用通用 `<|im_start|>system\n...<|im_end|>\n` 包裹注入到开头。
 * 2. UserMessage:用(可能精简后的)模板渲染({user}=content,其他占位符清空)。模板尾部的
 *    assistant 段开头自动形成"待续写"的提示。
 * 3. AiMessage:把助手响应内容追加到上一轮 UserMessage 渲染产生的 assistant 段开头
 *    之后,并补一个 turn-close(如 `<|im_end|>\n`)关闭该 assistant 段。
 * 4. ToolExecutionResultMessage:按 user 角色处理(同 UserMessage)。
 * 5. 末尾:若最后一条是 UserMessage/ToolResult,模板已提供 assistant 段开头,无需追加。
 *    若最后一条是 AiMessage,对话已完整,也不追加。
 *
 * 占位符:{user} / {assistant} / {system}。{assistant} 主要用于未来扩展
 * (当前预置模板均不含 {assistant},由步骤 3 隐式处理 assistant 内容)。
 */
object MnnChatTemplate {

    private const val PLACEHOLDER_USER = "{user}"
    private const val PLACEHOLDER_ASSISTANT = "{assistant}"
    private const val PLACEHOLDER_SYSTEM = "{system}"

    /**
     * 把对话消息渲染为最终 prompt。
     *
     * @param template 模型 chat template,来自 [MnnModelPreset.chatTemplate]
     * @param messages LangChain4j 消息列表(可能含 system/user/assistant/tool result)
     * @return 已套用 template 的 prompt 字符串
     */
    fun render(template: String, messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return template

        val turnClose = inferTurnClose(template)
        val hasSystemPlaceholder = template.contains(PLACEHOLDER_SYSTEM)

        // 用于 user 消息渲染的模板:若模板含 {system},剥离 system 段避免重复
        val userTemplate = if (hasSystemPlaceholder) {
            stripSystemSegment(template, turnClose)
        } else {
            template
        }

        val sb = StringBuilder()

        // 1. 提取 system 消息(合并多条),渲染到开头
        val systemText = messages.filterIsInstance<SystemMessage>()
            .joinToString("\n\n") { it.text() }
            .ifBlank { null }

        if (systemText != null) {
            if (hasSystemPlaceholder) {
                // 从模板中提取 system 段(从开头到 {system} 后第一个 turn-close)
                val sysSegment = extractSystemSegment(template, turnClose)
                sb.append(sysSegment.replace(PLACEHOLDER_SYSTEM, systemText))
            } else {
                // 模板不含 {system}:用通用 ChatML-style system 段注入
                sb.append("<|im_start|>system\n").append(systemText).append(turnClose)
            }
        }

        // 2. 渲染非 system 消息
        val turns = messages.filter { it !is SystemMessage }
        for (msg in turns) {
            when (msg) {
                is UserMessage -> {
                    val content = msgTextSafe(msg)
                    // 渲染 user 段 + assistant 段开头(模板尾部自带)
                    sb.append(userTemplate
                        .replace(PLACEHOLDER_SYSTEM, "")
                        .replace(PLACEHOLDER_USER, content)
                        .replace(PLACEHOLDER_ASSISTANT, ""))
                }
                is AiMessage -> {
                    val content = msg.text() ?: ""
                    // 上一轮 UserMessage 已开启 assistant 段,这里填充内容并关闭
                    sb.append(content).append(turnClose)
                }
                is ToolExecutionResultMessage -> {
                    // 工具结果按 user 角色渲染
                    sb.append(userTemplate
                        .replace(PLACEHOLDER_SYSTEM, "")
                        .replace(PLACEHOLDER_USER, msg.text())
                        .replace(PLACEHOLDER_ASSISTANT, ""))
                }
                else -> {
                    // 未知类型跳过
                }
            }
        }

        // 3. 末尾处理:
        //    - 最后一条是 UserMessage/ToolResult → 模板已提供 assistant 段开头,无需追加
        //    - 最后一条是 AiMessage → 对话已完整,无需追加
        //    (其他情况如空 turns 也无需追加)
        return sb.toString()
    }

    /**
     * 根据模板推断 turn 结束符。
     *  - 含 `<|eot_id|>`(Llama3 系) → `<|eot_id|>`
     *  - 含 `<|im_end|>`(Qwen/ChatML 系) → `<|im_end|>\n`
     *  - 兜底 → `<|im_end|>\n`
     */
    private fun inferTurnClose(template: String): String {
        return when {
            template.contains("<|eot_id|>") -> "<|eot_id|>"
            template.contains("<|im_end|>") -> "<|im_end|>\n"
            else -> "<|im_end|>\n"
        }
    }

    /**
     * 从模板中提取 system 段:从开头到 {system} 后第一个 turn-close(含)。
     * 若 {system} 不存在或找不到 turn-close,返回空字符串。
     */
    private fun extractSystemSegment(template: String, turnClose: String): String {
        val sysIdx = template.indexOf(PLACEHOLDER_SYSTEM)
        if (sysIdx < 0) return ""
        val closeIdx = template.indexOf(turnClose, sysIdx)
        if (closeIdx < 0) return ""
        return template.substring(0, closeIdx + turnClose.length)
    }

    /**
     * 从模板中剥离 system 段,返回剩余部分(用于 user 消息渲染)。
     * 若 {system} 不存在或找不到 turn-close,返回原模板。
     */
    private fun stripSystemSegment(template: String, turnClose: String): String {
        val sysIdx = template.indexOf(PLACEHOLDER_SYSTEM)
        if (sysIdx < 0) return template
        val closeIdx = template.indexOf(turnClose, sysIdx)
        if (closeIdx < 0) return template
        return template.substring(closeIdx + turnClose.length)
    }

    /** UserMessage 可能含多 content,只取 TextContent 拼接(本地模型不支持视觉)。 */
    private fun msgTextSafe(msg: UserMessage): String {
        return runCatching { msg.singleText() }.getOrNull()
            ?: msg.contents()
                .filterIsInstance<dev.langchain4j.data.message.TextContent>()
                .joinToString("\n") { it.text() }
    }
}
