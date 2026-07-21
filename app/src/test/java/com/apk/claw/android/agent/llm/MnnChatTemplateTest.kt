package com.apk.claw.android.agent.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MnnChatTemplate 单测 —— 纯 JVM,无 Android 依赖。
 *
 * 覆盖:
 *  - 单轮 user 渲染
 *  - 多轮对话渲染(含 assistant 历史正确插入)
 *  - Llama3 占位符替换正确
 *  - system 消息处理(模板含/不含 {system} 占位符)
 *  - 末尾 assistant 段开头自动追加
 */
class MnnChatTemplateTest {

    private val qwenTemplate: String =
        "<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"

    private val llamaTemplate: String =
        "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n{user}<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n"

    @Test
    fun `single user message renders correctly`() {
        val messages = listOf<UserMessage>(
            UserMessage.from("你好")
        )
        val out = MnnChatTemplate.render(qwenTemplate, messages)
        // 模板尾部的 assistant 段开头保留,让模型续写
        assertEquals(
            "<|im_start|>user\n你好<|im_end|>\n<|im_start|>assistant\n",
            out,
        )
    }

    @Test
    fun `multi-turn dialogue renders assistant content in place`() {
        val messages = listOf(
            UserMessage.from("你是谁"),
            AiMessage.from("我是助手"),
            UserMessage.from("今天天气如何"),
        )
        val out = MnnChatTemplate.render(qwenTemplate, messages)
        // 期望:Q1 + assistant-prefix + A1 + turn-close + Q2 + assistant-prefix
        val expected = buildString {
            append("<|im_start|>user\n你是谁<|im_end|>\n<|im_start|>assistant\n")
            append("我是助手<|im_end|>\n")
            append("<|im_start|>user\n今天天气如何<|im_end|>\n<|im_start|>assistant\n")
        }
        assertEquals(expected, out)
    }

    @Test
    fun `placeholder substitution for llama3 template`() {
        val messages = listOf<UserMessage>(UserMessage.from("hello"))
        val out = MnnChatTemplate.render(llamaTemplate, messages)
        assertEquals(
            "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nhello<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n",
            out,
        )
    }

    @Test
    fun `llama3 multi-turn uses eot_id as turn close`() {
        val messages = listOf(
            UserMessage.from("Q1"),
            AiMessage.from("A1"),
            UserMessage.from("Q2"),
        )
        val out = MnnChatTemplate.render(llamaTemplate, messages)
        // assistant 内容后应跟 <|eot_id|>(而非 <|im_end|>\n)
        assertTrue("应包含 A1<|eot_id|>", out.contains("A1<|eot_id|>"))
    }

    @Test
    fun `system message without placeholder falls back to chatml system segment`() {
        val messages = listOf(
            SystemMessage.from("你是助手"),
            UserMessage.from("hi"),
        )
        val out = MnnChatTemplate.render(qwenTemplate, messages)
        // system 段(Qwen 模板无 {system} 占位符 → 通用 ChatML-style 注入)
        assertTrue(out.contains("<|im_start|>system\n你是助手<|im_end|>\n"))
        // 紧接着是 user 段
        assertTrue(out.contains("<|im_start|>user\nhi<|im_end|>\n"))
        // 末尾是 assistant 段开头
        assertTrue(out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `system message with placeholder is substituted into template`() {
        val templateWithSystem =
            "<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"
        val messages = listOf(
            SystemMessage.from("SYS"),
            UserMessage.from("U"),
        )
        val out = MnnChatTemplate.render(templateWithSystem, messages)
        // system 段从模板中提取并渲染一次,然后 user 段用剥离 system 后的精简模板渲染
        val expected = buildString {
            append("<|im_start|>system\nSYS<|im_end|>\n")
            append("<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n")
        }
        assertEquals(expected, out)
    }

    @Test
    fun `empty messages returns template unchanged`() {
        val out = MnnChatTemplate.render(qwenTemplate, emptyList())
        assertEquals(qwenTemplate, out)
    }

    @Test
    fun `multiple system messages are joined`() {
        val messages = listOf(
            SystemMessage.from("rule1"),
            SystemMessage.from("rule2"),
            UserMessage.from("go"),
        )
        val out = MnnChatTemplate.render(qwenTemplate, messages)
        // 两条 system 消息应被 "\n\n" 连接
        assertTrue(out.contains("rule1\n\nrule2"))
    }

    @Test
    fun `trailing assistant prefix present when last is user`() {
        val messages = listOf<UserMessage>(UserMessage.from("Q"))
        val out = MnnChatTemplate.render(qwenTemplate, messages)
        assertTrue("末尾应是 assistant 段开头", out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `no trailing assistant prefix when last is ai`() {
        val messages = listOf(
            UserMessage.from("Q"),
            AiMessage.from("A"),
        )
        val out = MnnChatTemplate.render(qwenTemplate, messages)
        // 最后一条是 AiMessage,对话已完整,末尾应是 A + turn-close
        assertTrue(out.endsWith("A<|im_end|>\n"))
    }
}
