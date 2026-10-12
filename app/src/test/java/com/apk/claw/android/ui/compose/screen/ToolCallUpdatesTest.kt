package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.agent.ToolExecutionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallUpdatesTest {
    private fun snapshot(
        id: String, phase: ToolExecutionProgress.Phase, output: String = "", success: Boolean? = null,
    ) =
        ToolExecutionProgress(id, "run_shell", "执行命令", "{command:pwd}", "batch", phase,
            output, success, if (phase == ToolExecutionProgress.Phase.FINISHED) 123L else null)

    @Test
    fun `stream updates preserve row identity and full output beyond the summary`() {
        val messages = mutableListOf<ChatMessage>()
        updateToolCall(messages, snapshot("a", ToolExecutionProgress.Phase.STARTED))
        val rowId = messages.single().id
        updateToolCall(messages, snapshot("a", ToolExecutionProgress.Phase.OUTPUT, "partial"))
        val output = "A".repeat(160) + "\nlast line"
        updateToolCall(messages, snapshot("a", ToolExecutionProgress.Phase.FINISHED, output, true))
        val call = messages.single() as ChatMessage.ToolCall
        assertEquals(rowId, call.id)
        assertEquals(output, call.result)
        assertEquals(ChatMessage.ToolCallStatus.SUCCESS, call.status)
        assertEquals(123L, call.durationMs)
        assertEquals("batch", call.batchId)
        assertTrue(toolCallSummary(call).length < output.length)
    }

    @Test
    fun `parallel repeated tool names update independently`() {
        val messages = mutableListOf<ChatMessage>()
        updateToolCall(messages, snapshot("a", ToolExecutionProgress.Phase.STARTED))
        updateToolCall(messages, snapshot("b", ToolExecutionProgress.Phase.STARTED))
        updateToolCall(messages, snapshot("b", ToolExecutionProgress.Phase.FINISHED, "failure", false))
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.ToolCallStatus.RUNNING, (messages[0] as ChatMessage.ToolCall).status)
        assertEquals(ChatMessage.ToolCallStatus.FAILURE, (messages[1] as ChatMessage.ToolCall).status)
        assertTrue(toolCallSummary(messages[1] as ChatMessage.ToolCall).startsWith("✗"))
    }

    @Test
    fun `stop preserves partial log and ignores late successful completion`() {
        val messages = mutableListOf<ChatMessage>()
        updateToolCall(messages, snapshot("a", ToolExecutionProgress.Phase.OUTPUT, "partial"))
        interruptToolCalls(messages, "已停止")
        val stopped = messages.single()
        updateToolCall(messages, snapshot("a", ToolExecutionProgress.Phase.FINISHED, "late", true))
        assertEquals(stopped, messages.single())
        assertEquals("partial\n已停止", (stopped as ChatMessage.ToolCall).result)
        assertEquals(ChatMessage.ToolCallStatus.CANCELLED, stopped.status)
    }

    @Test
    fun `historical status is compatible and unfinished persisted tasks are stopped`() {
        assertEquals(ChatMessage.ToolCallStatus.SUCCESS, restoredToolStatus(null))
        assertEquals(ChatMessage.ToolCallStatus.FAILURE, restoredToolStatus("FAILURE"))
        assertEquals(ChatMessage.ToolCallStatus.CANCELLED, restoredToolStatus("RUNNING"))
    }
}
