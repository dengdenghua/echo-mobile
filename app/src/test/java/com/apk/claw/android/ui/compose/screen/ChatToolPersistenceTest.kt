package com.apk.claw.android.ui.compose.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatToolPersistenceTest {
    @Test
    fun `old history without execution metadata still loads`() {
        val call = ChatStore.decode("""[{"type":"tool","a":"⚙","b":"cmd","c":"{}","d":"old result"}]""")
            ?.single() as ChatMessage.ToolCall
        assertEquals("old result", call.result)
        assertEquals(ChatMessage.ToolCallStatus.SUCCESS, call.status)
        assertNull(call.callId)
        assertNull(call.batchId)
    }

    @Test
    fun `failure and batch survive history roundtrip while unfinished task becomes stopped`() {
        val failure = ChatMessage.ToolCall("⚙", "cmd", "{cwd:project}", "log\n".repeat(200),
            callId = "a", batchId = "batch", status = ChatMessage.ToolCallStatus.FAILURE, durationMs = 42)
        val running = failure.copy(callId = "b", status = ChatMessage.ToolCallStatus.RUNNING)
        val calls = ChatStore.decode(ChatStore.encode(listOf(failure, running)))!!
            .filterIsInstance<ChatMessage.ToolCall>()
        assertEquals(failure.result, calls[0].result)
        assertEquals("a", calls[0].callId)
        assertEquals("batch", calls[0].batchId)
        assertEquals(ChatMessage.ToolCallStatus.FAILURE, calls[0].status)
        assertEquals(42L, calls[0].durationMs)
        assertEquals(ChatMessage.ToolCallStatus.CANCELLED, calls[1].status)
    }
}
