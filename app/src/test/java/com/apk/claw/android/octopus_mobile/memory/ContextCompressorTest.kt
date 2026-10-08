package com.apk.claw.android.octopus_mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContextCompressor 单元测试 —— 验证移动端上下文压缩契约：
 *  - 字符上限保护与按需压缩
 *  - System 提示与最近 N 轮强保留
 *  - 历史总结防堆叠（多次压缩不雪崩累积 [Context Summary]）
 *  - LLM 摘要器与硬截断回退机制
 */
class ContextCompressorTest {

    @Test
    fun `messages below maxChars are returned unchanged`() {
        val compressor = ContextCompressor(
            config = ContextCompressor.CompressorConfig(maxChars = 1000, preserveRecentN = 2)
        )
        val msgs = listOf(
            ContextCompressor.ChatMessage("system", "You are an assistant"),
            ContextCompressor.ChatMessage("user", "Hello"),
            ContextCompressor.ChatMessage("assistant", "Hi there"),
        )
        val result = compressor.compress(msgs)
        assertEquals(msgs, result)
    }

    @Test
    fun `messages exceeding maxChars are compressed with system and recent preserved`() {
        val compressor = ContextCompressor(
            config = ContextCompressor.CompressorConfig(
                maxChars = 100,
                preserveSystem = true,
                preserveRecentN = 2,
                summaryMaxChars = 50,
                chunkTruncateChars = 30
            )
        )
        val msgs = listOf(
            ContextCompressor.ChatMessage("system", "System prompt"),
            ContextCompressor.ChatMessage("user", "Step 1: Do something very long and detailed"),
            ContextCompressor.ChatMessage("assistant", "Step 1 result: Processed successfully with details"),
            ContextCompressor.ChatMessage("user", "Step 2: Continue to next phase"),
            ContextCompressor.ChatMessage("assistant", "Step 2 result: Almost done"),
        )
        val result = compressor.compress(msgs)

        // System prompt preserved at start
        assertEquals("system", result[0].role)
        assertEquals("System prompt", result[0].content)

        // Middle has context summary
        assertEquals("system", result[1].role)
        assertTrue(result[1].content.startsWith("[Context Summary]"))

        // Recent 2 messages preserved at end
        assertEquals("user", result[2].role)
        assertEquals("Step 2: Continue to next phase", result[2].content)
        assertEquals("assistant", result[3].role)
        assertEquals("Step 2 result: Almost done", result[3].content)
        assertEquals(4, result.size)
    }

    @Test
    fun `repeated compression does not accumulate duplicate context summary in system messages`() {
        val compressor = ContextCompressor(
            config = ContextCompressor.CompressorConfig(
                maxChars = 80,
                preserveSystem = true,
                preserveRecentN = 1,
                summaryMaxChars = 40,
                chunkTruncateChars = 20
            )
        )
        val initialMsgs = listOf(
            ContextCompressor.ChatMessage("system", "Base prompt"),
            ContextCompressor.ChatMessage("user", "Very long query that exceeds threshold 1"),
            ContextCompressor.ChatMessage("assistant", "Very long answer that exceeds threshold 1"),
        )
        val round1 = compressor.compress(initialMsgs)
        assertEquals(3, round1.size)
        assertEquals(1, round1.count { it.content.startsWith("[Context Summary]") })

        // Simulate next round with more long messages
        val round2Input = round1 + listOf(
            ContextCompressor.ChatMessage("user", "Another very long query exceeding threshold 2"),
            ContextCompressor.ChatMessage("assistant", "Another very long response exceeding threshold 2"),
        )
        val round2 = compressor.compress(round2Input)

        // Only ONE [Context Summary] should exist, not accumulated
        val summaryCount = round2.count { it.content.startsWith("[Context Summary]") }
        assertEquals(1, summaryCount)
        val preservedSystem = round2.first {
            it.role == "system" && !it.content.startsWith("[Context Summary]")
        }
        assertEquals("Base prompt", preservedSystem.content)
    }

    @Test
    fun `custom summarizer is invoked when provided and falls back safely on error`() {
        var summarizerCalled = false
        val customCompressor = ContextCompressor(
            config = ContextCompressor.CompressorConfig(
                maxChars = 50,
                preserveRecentN = 1,
                summaryMaxChars = 30
            ),
            summarizer = { _ ->
                summarizerCalled = true
                "Key points: Step 1 done"
            }
        )
        val msgs = listOf(
            ContextCompressor.ChatMessage("user", "Older query message that needs compression"),
            ContextCompressor.ChatMessage("assistant", "Older response message that needs compression"),
            ContextCompressor.ChatMessage("user", "Recent message"),
        )
        val (compressed, report) = customCompressor.compressWithReport(msgs)
        assertTrue(summarizerCalled)
        assertEquals("llm_summary", report.method)
        assertTrue(compressed.any { it.content.contains("Key points: Step 1 done") })

        // Test fallback on exception
        val failingCompressor = ContextCompressor(
            config = ContextCompressor.CompressorConfig(
                maxChars = 50,
                preserveRecentN = 1,
                summaryMaxChars = 30
            ),
            summarizer = { _ -> throw java.io.IOException("LLM offline") }
        )
        val (fallbackCompressed, fallbackReport) = failingCompressor.compressWithReport(msgs)
        assertEquals("truncate_older", fallbackReport.method)
        assertTrue(fallbackCompressed.isNotEmpty())
    }
}
