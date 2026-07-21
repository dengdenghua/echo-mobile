package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.ui.compose.screen.BatchAggregation.Batch
import com.apk.claw.android.ui.compose.screen.BatchAggregation.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * refine-chat-interaction Task 2:并行工具折叠卡片聚合逻辑单测。
 *
 * 不渲染 Compose(那需 Robolectric),只测 [groupToolCallsByBatch] 这个纯函数 ——
 * 它是 [turnRows] 内 batchId 聚合的核心,决定哪些 ToolCall 会被 [ToolBatchCard] 渲染。
 *
 * 覆盖 spec 要求的 3 个场景:
 *  - 3 个 ToolCall 同 batchId → 1 组
 *  - 1 个 ToolCall batchId=null → 独立项
 *  - 混合(batchId=null + batchId="x")→ 2 项
 */
class ToolBatchCardTest {

    private fun toolCall(
        name: String,
        batchId: String? = null,
        durationMs: Long? = null,
        status: ChatMessage.ToolCallStatus = ChatMessage.ToolCallStatus.SUCCESS,
    ): ChatMessage.ToolCall = ChatMessage.ToolCall(
        icon = "🔧",
        toolName = name,
        args = "{}",
        result = null,
        batchId = batchId,
        durationMs = durationMs,
        status = status,
    )

    @Test
    fun `empty messages returns empty list`() {
        val result = groupToolCallsByBatch(emptyList())
        assertTrue("空消息列表应返回空聚合结果", result.isEmpty())
    }

    @Test
    fun `3 ToolCalls with same batchId aggregate into 1 Batch`() {
        val messages = listOf<ChatMessage>(
            toolCall("search_code", batchId = "batch-1"),
            toolCall("read_file", batchId = "batch-1"),
            toolCall("grep", batchId = "batch-1"),
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals("3 个同 batchId 的 ToolCall 应聚合成 1 项", 1, result.size)
        assertTrue("聚合项应为 Batch", result[0] is Batch)
        val batch = result[0] as Batch
        assertEquals("Batch 应含全部 3 个 ToolCall", 3, batch.calls.size)
        assertEquals("search_code", batch.calls[0].toolName)
        assertEquals("read_file", batch.calls[1].toolName)
        assertEquals("grep", batch.calls[2].toolName)
        // 所有 call 的 batchId 一致
        assertTrue(batch.calls.all { it.batchId == "batch-1" })
    }

    @Test
    fun `ToolCall with batchId=null stays as Single`() {
        val messages = listOf<ChatMessage>(
            toolCall("tap", batchId = null),
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals("batchId=null 的 ToolCall 应作为独立 Single 项", 1, result.size)
        assertTrue("应为 Single", result[0] is Single)
        val single = result[0] as Single
        assertTrue("Single 内应为 ToolCall", single.msg is ChatMessage.ToolCall)
        assertEquals("tap", (single.msg as ChatMessage.ToolCall).toolName)
        assertNull((single.msg as ChatMessage.ToolCall).batchId)
    }

    @Test
    fun `mixed batchId=null and batchId=x yields 2 items`() {
        val messages = listOf<ChatMessage>(
            toolCall("tap", batchId = null),
            toolCall("search_code", batchId = "x"),
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals("混合 batchId=null 与 batchId=x 应得 2 项", 2, result.size)
        assertTrue("首项 tap(batchId=null)应为 Single", result[0] is Single)
        assertTrue("次项 search_code(batchId=x)应为 Batch", result[1] is Batch)
        val batch = result[1] as Batch
        assertEquals(1, batch.calls.size)
        assertEquals("search_code", batch.calls[0].toolName)
    }

    @Test
    fun `same batchId ToolCalls at non-consecutive positions still aggregate`() {
        // batchId="x" 的两个 ToolCall 中间夹一条非 ToolCall 消息,仍应跨位置聚合
        val messages = listOf<ChatMessage>(
            toolCall("search_code", batchId = "x"),
            ChatMessage.AgentMessage("thinking..."),
            toolCall("read_file", batchId = "x"),
        )

        val result = groupToolCallsByBatch(messages)

        // 期望:Batch(x) / Single(AgentMessage) —— 第二个 batchId="x" 被吸收,不单独出现
        assertEquals("跨位置聚合后应得 2 项(Batch + AgentMessage)", 2, result.size)
        assertTrue("首项应为 Batch(batchId=x 跨位置聚合)", result[0] is Batch)
        assertTrue("次项应为 Single(AgentMessage)", result[1] is Single)
        val batch = result[0] as Batch
        assertEquals("Batch 应含 2 个 ToolCall(跨位置聚合)", 2, batch.calls.size)
        assertEquals("search_code", batch.calls[0].toolName)
        assertEquals("read_file", batch.calls[1].toolName)
        assertTrue(result[1] is Single && (result[1] as Single).msg is ChatMessage.AgentMessage)
    }

    @Test
    fun `two distinct batchIds yield two Batches`() {
        val messages = listOf<ChatMessage>(
            toolCall("a", batchId = "x"),
            toolCall("b", batchId = "y"),
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals("两个不同 batchId 应得 2 项", 2, result.size)
        assertTrue(result[0] is Batch)
        assertTrue(result[1] is Batch)
        assertEquals("a", (result[0] as Batch).calls[0].toolName)
        assertEquals("b", (result[1] as Batch).calls[0].toolName)
    }

    @Test
    fun `non-ToolCall messages pass through as Single`() {
        val messages = listOf<ChatMessage>(
            ChatMessage.UserMessage("hi"),
            ChatMessage.AgentMessage("hello"),
            ChatMessage.Thinking("..."),
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals("非 ToolCall 消息应原样作为 Single 透传", 3, result.size)
        assertTrue(result[0] is Single && (result[0] as Single).msg is ChatMessage.UserMessage)
        assertTrue(result[1] is Single && (result[1] as Single).msg is ChatMessage.AgentMessage)
        assertTrue(result[2] is Single && (result[2] as Single).msg is ChatMessage.Thinking)
    }

    @Test
    fun `Batch preserves durationMs and status for ToolBatchCard rendering`() {
        // 验证 Batch 里的 calls 保留 durationMs / status,供 ToolBatchCard 展开态渲染
        val messages = listOf<ChatMessage>(
            toolCall("fast", batchId = "b1", durationMs = 12L, status = ChatMessage.ToolCallStatus.SUCCESS),
            toolCall("slow", batchId = "b1", durationMs = 350L, status = ChatMessage.ToolCallStatus.FAILURE),
            toolCall("pending", batchId = "b1", durationMs = null, status = ChatMessage.ToolCallStatus.RUNNING),
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals(1, result.size)
        assertTrue(result[0] is Batch)
        val calls = (result[0] as Batch).calls
        assertEquals(3, calls.size)
        assertEquals(12L, calls[0].durationMs)
        assertEquals(ChatMessage.ToolCallStatus.SUCCESS, calls[0].status)
        assertEquals(350L, calls[1].durationMs)
        assertEquals(ChatMessage.ToolCallStatus.FAILURE, calls[1].status)
        assertNull(calls[2].durationMs)
        assertEquals(ChatMessage.ToolCallStatus.RUNNING, calls[2].status)
    }

    @Test
    fun `Batch emits at first occurrence position`() {
        // 首次出现位置发射整组,后续位置不重复
        val messages = listOf<ChatMessage>(
            ChatMessage.UserMessage("q"),
            toolCall("a", batchId = "x"),
            toolCall("b", batchId = "x"),
            ChatMessage.AgentMessage("ans"),
        )

        val result = groupToolCallsByBatch(messages)

        // 期望顺序:Single(User) / Batch(x) / Single(Agent)
        assertEquals(3, result.size)
        assertTrue("首项应为 Single(UserMessage)", result[0] is Single)
        assertTrue("次项应为 Batch(首次出现位置发射)", result[1] is Batch)
        assertTrue("末项应为 Single(AgentMessage)", result[2] is Single)
        val batch = result[1] as Batch
        assertEquals(2, batch.calls.size)
        assertNotNull((result[0] as Single).msg as? ChatMessage.UserMessage)
        assertNotNull((result[2] as Single).msg as? ChatMessage.AgentMessage)
    }

    @Test
    fun `interleaved batches and singletons preserve first-occurrence order`() {
        // batchId=x, batchId=null, batchId=y, batchId=x(再次) —— 期望:Batch(x) / Single / Batch(y)
        // 第二个 batchId=x 被吸收到 Batch(x),不单独出现
        val messages = listOf<ChatMessage>(
            toolCall("a", batchId = "x"),
            toolCall("serial", batchId = null),
            toolCall("b", batchId = "y"),
            toolCall("a2", batchId = "x"),  // 同 batchId=x,已被吸收
        )

        val result = groupToolCallsByBatch(messages)

        assertEquals("吸收后应得 3 项", 3, result.size)
        assertTrue(result[0] is Batch)
        assertTrue(result[1] is Single)
        assertTrue(result[2] is Batch)
        val firstBatch = result[0] as Batch
        assertEquals("Batch(x) 应含 2 个 calls(跨位置吸收)", 2, firstBatch.calls.size)
        assertEquals("a", firstBatch.calls[0].toolName)
        assertEquals("a2", firstBatch.calls[1].toolName)
        assertEquals("serial", ((result[1] as Single).msg as ChatMessage.ToolCall).toolName)
        assertEquals("b", (result[2] as Batch).calls[0].toolName)
    }
}
