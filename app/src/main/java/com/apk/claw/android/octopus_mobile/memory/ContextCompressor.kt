package com.apk.claw.android.octopus_mobile.memory

import android.util.Log

/**
 * 上下文压缩器 —— 从母体 runtime/memory/context_compressor.py 移植.
 *
 * 手机版策略（省 token）：
 *  1. system 消息 → 始终保留
 *  2. 最近 N 条消息 → 始终保留
 *  3. 更早的消息 → **优先用 LLM 真总结**([summarizer])；未注入/失败时退回硬截断
 *
 * [summarizer]:注入的"prompt → 摘要文本"函数(通常接一次小模型调用)。不注入时退回旧的
 * 500 字硬截断——**默认行为完全不变,向后兼容**。真总结只在编程等长任务里兑现价值:硬截断
 * 会丢掉"之前试过 X 因 Y 失败"这类关键历史,LLM 总结能保住。
 *
 * 用法：
 * ```kotlin
 * val compressor = ContextCompressor(summarizer = { prompt -> smallModel.complete(prompt) })
 * val (compressed, report) = compressor.compressWithReport(messages)
 * ```
 */
class ContextCompressor(
    val config: CompressorConfig = CompressorConfig(),
    private val summarizer: ((String) -> String?)? = null,
) {
    companion object {
        private const val TAG = "ContextCompressor"
        /** 喂给 LLM 总结的原文上限(超出取头尾各半,保住两端)。 */
        private const val SUMMARIZER_INPUT_CAP = 16000
    }

    @Volatile
    private var lastMethod: String = "truncate_older"

    data class CompressorConfig(
        val maxChars: Int = 80000,
        val preserveSystem: Boolean = true,
        val preserveRecentN: Int = 4,
        val summaryMaxChars: Int = 2000,
        val chunkTruncateChars: Int = 500,
    )

    data class CompressionResult(
        val originalChars: Int,
        val compressedChars: Int,
        val ratio: Float,
        val method: String,
        val sectionsPreserved: Int,
        val sectionsSummarized: Int,
    )

    data class ChatMessage(
        val role: String,   // "system" / "user" / "assistant" / "tool"
        val content: String,
    )

    /**
     * 压缩消息列表.
     */
    fun compress(messages: List<ChatMessage>): List<ChatMessage> {
        val totalChars = messages.sumOf { it.content.length }
        if (totalChars <= config.maxChars) {
            return messages
        }

        val systemMsgs = mutableListOf<ChatMessage>()
        val recentMsgs = mutableListOf<ChatMessage>()
        val olderMsgs = mutableListOf<ChatMessage>()

        for (m in messages) {
            if (m.role == "system" && config.preserveSystem && !m.content.startsWith("[Context Summary]")) {
                systemMsgs.add(m)
            } else {
                olderMsgs.add(m)
            }
        }

        if (olderMsgs.size > config.preserveRecentN) {
            recentMsgs.addAll(olderMsgs.takeLast(config.preserveRecentN))
            repeat(config.preserveRecentN) { olderMsgs.removeLastOrNull() }
        } else {
            recentMsgs.addAll(olderMsgs)
            olderMsgs.clear()
        }

        if (olderMsgs.isEmpty()) {
            return systemMsgs + recentMsgs
        }

        val summary = summarizeOlder(olderMsgs)
        val summaryMsg = ChatMessage(
            role = "system",
            content = "[Context Summary]\n$summary",
        )

        return systemMsgs + listOf(summaryMsg) + recentMsgs
    }

    /**
     * 压缩并返回报告.
     */
    fun compressWithReport(messages: List<ChatMessage>): Pair<List<ChatMessage>, CompressionResult> {
        val originalChars = messages.sumOf { it.content.length }
        val compressed = compress(messages)
        val compressedChars = compressed.sumOf { it.content.length }
        val ratio = if (originalChars > 0) compressedChars.toFloat() / originalChars else 1.0f

        val sectionsPreserved = compressed.count { !it.content.startsWith("[Context Summary]") }
        val sectionsSummarized = maxOf(0, messages.size - sectionsPreserved)

        val result = CompressionResult(
            originalChars = originalChars,
            compressedChars = compressedChars,
            ratio = (ratio * 1000).toInt() / 1000f,
            method = lastMethod,
            sectionsPreserved = sectionsPreserved,
            sectionsSummarized = sectionsSummarized,
        )

        Log.d(TAG, "Compressed: ${originalChars}→${compressedChars} chars (${(ratio * 100).toInt()}%)")

        return compressed to result
    }

    private fun summarizeOlder(messages: List<ChatMessage>): String {
        // 优先:LLM 真总结(注入了 summarizer 时)
        val llm = summarizer?.let { fn ->
            runCatching { fn(summaryPrompt(rawJoined(messages))) }
                .getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        if (llm != null) {
            lastMethod = "llm_summary"
            return if (llm.length > config.summaryMaxChars) llm.take(config.summaryMaxChars) + "\n...[truncated]" else llm
        }
        // 退回:硬截断(旧行为,向后兼容)
        lastMethod = "truncate_older"
        return truncateOlder(messages)
    }

    /** 旧的硬截断:每条截到 chunkTruncateChars,整体截到 summaryMaxChars。 */
    private fun truncateOlder(messages: List<ChatMessage>): String {
        val parts = messages.map { m ->
            val chunk = if (m.content.length > config.chunkTruncateChars) {
                m.content.take(config.chunkTruncateChars) + "..."
            } else m.content
            "[${m.role}] $chunk"
        }
        val full = parts.joinToString("\n")
        return if (full.length > config.summaryMaxChars) full.take(config.summaryMaxChars) + "\n...[truncated]" else full
    }

    /** 供 LLM 总结的原文:role 标注拼接;超 [SUMMARIZER_INPUT_CAP] 时取头尾各半,保住两端。 */
    private fun rawJoined(messages: List<ChatMessage>): String {
        val full = messages.joinToString("\n") { "[${it.role}] ${it.content}" }
        if (full.length <= SUMMARIZER_INPUT_CAP) return full
        val half = SUMMARIZER_INPUT_CAP / 2
        return full.take(half) + "\n...[省略中间]...\n" + full.takeLast(half)
    }

    /** 总结指令:强调保留"决定/失败原因/当前状态/关键数据",这些正是硬截断最容易丢的。 */
    private fun summaryPrompt(raw: String): String =
        "把下面这段 AI agent 的执行历史压缩成不超过 ${config.summaryMaxChars} 字的要点。" +
            "**重点保留**:已做的决定与完成的步骤;试过但失败的方案及失败原因(避免重复踩坑);" +
            "当前状态、待办、未解决的问题;关键数据/标识符(URL、id、坐标、错误信息)。" +
            "用简洁要点列表输出,不要寒暄、不要复述无关细节。\n\n历史:\n$raw"
}
