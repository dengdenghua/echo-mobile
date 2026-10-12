package com.apk.claw.android.agent

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.impl.ScriptSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionProgressTest {
    @Suppress("EmptyFunctionBlock") // These events are irrelevant to this progress observer fixture.
    private class Callback : AgentCallback {
        val progress = mutableListOf<ToolExecutionProgress>()
        var starts = 0
        override fun onToolProgress(progress: ToolExecutionProgress) { this.progress.add(progress) }
        override fun onLoopStart(round: Int) {}
        override fun onContent(round: Int, content: String) {}
        override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) { starts++ }
        override fun onToolResult(
            round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult,
        ) {}
        override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {}
        override fun onError(round: Int, error: Exception, totalTokens: Int) {}
        override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {}
    }

    @Test
    fun `repeated names get unique IDs but every phase keeps the same ID`() {
        val cb = Callback()
        val first = ToolExecutionReporter(cb, 1, "run_shell", "命令", "{}", "batch")
        first.execute { ToolOutputContext.emit("first\n") }
        first.finish(ToolResult.success("first\nlast"))
        first.finish(ToolResult.error("late"))
        ToolExecutionReporter(cb, 1, "run_shell", "命令", "{}", "batch").finish(ToolResult.success("ok"))
        assertEquals(2, cb.starts)
        assertEquals(listOf(ToolExecutionProgress.Phase.STARTED, ToolExecutionProgress.Phase.OUTPUT,
            ToolExecutionProgress.Phase.FINISHED), cb.progress.take(3).map { it.phase })
        assertEquals(1, cb.progress.take(3).map { it.callId }.distinct().size)
        assertNotEquals(cb.progress[0].callId, cb.progress[3].callId)
        assertTrue(cb.progress.all { it.batchId == "batch" })
        assertEquals("first\nlast", cb.progress[2].output)
        assertNotNull(cb.progress[2].durationMs)
    }

    @Test
    fun `failure retains preceding stdout and flushes throttled tail`() {
        val cb = Callback()
        val execution = ToolExecutionReporter(cb, 1, "run_shell", "命令", "{}")
        execution.execute { repeat(100) { ToolOutputContext.emit("line $it\n") } }
        execution.finish(ToolResult.error("timeout"))
        val completed = cb.progress.last()
        assertEquals(false, completed.success)
        assertTrue(completed.output.contains("line 99\n"))
        assertTrue(completed.output.endsWith("timeout"))
        assertTrue(cb.progress.size < 100)
    }

    @Test
    fun `bounded tail retains final diagnostic and never splits an emoji`() {
        val value = "x".repeat(20_001) + "😀" + "z".repeat(19_999)
        val tail = boundedToolOutput(value)
        assertTrue(tail.startsWith(TOOL_OUTPUT_TRUNCATED))
        assertFalse(tail.removePrefix(TOOL_OUTPUT_TRUNCATED).first().isLowSurrogate())
        assertTrue(tail.endsWith("z".repeat(19_999)))
        assertTrue(tail.length <= TOOL_OUTPUT_LIMIT + TOOL_OUTPUT_TRUNCATED.length)
        assertEquals(tail, boundedToolOutput(tail))
    }

    @Test
    fun `observer scope restores after nested execution and exception`() {
        val outer = StringBuilder()
        val inner = StringBuilder()
        ToolOutputContext.withSink({ outer.append(it) }) {
            assertThrows(IllegalStateException::class.java) {
                ToolOutputContext.withSink({ inner.append(it) }) {
                    ToolOutputContext.emit("inner")
                    error("failed")
                }
            }
            ToolOutputContext.emit("outer")
        }
        assertEquals("inner", inner.toString())
        assertEquals("outer", outer.toString())
        assertNull(ToolOutputContext.current())
    }

    @Test
    fun `JavaScript print arrives during execution before its error result`() {
        val chunks = mutableListOf<String>()
        val result = ToolOutputContext.withSink({ chunks.add(it) }) {
            ScriptSandbox.execute("print('开始😀'); throw new Error('失败');", 5_000)
        }
        assertFalse(result.isSuccess)
        assertEquals("开始😀\n", chunks.joinToString(""))
        assertNull(ToolOutputContext.current())
    }
}
