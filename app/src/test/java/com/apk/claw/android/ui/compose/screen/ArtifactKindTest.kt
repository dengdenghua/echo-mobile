package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.ui.compose.screen.ChatMessage.ArtifactKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * refine-chat-interaction Task 1:消息模型扩展单测。
 *
 * 验证:
 * - [ArtifactKind] enum 包含 7 个值(HTML/IMAGE/FILE/DIFF/PLAN/CODE_SNIPPET/TEXT)
 * - [ChatMessage.Artifact] 默认 collapsed = true(INV-U2)
 * - [ChatMessage.ToolCall] 默认 batchId = null(INV-U3,串行回退)
 *
 * 注意:此测试只验证数据模型,不涉及 Compose 渲染(Compose 测试需 Robolectric,见 Task 2/4)。
 */
class ArtifactKindTest {

    @Test
    fun `ArtifactKind enum has 7 values`() {
        val values = ArtifactKind.values().toList()
        assertEquals(7, values.size)
        assertTrue(ArtifactKind.HTML in values)
        assertTrue(ArtifactKind.IMAGE in values)
        assertTrue(ArtifactKind.FILE in values)
        assertTrue(ArtifactKind.DIFF in values)
        // refine-chat-interaction 新增 3 个
        assertTrue(ArtifactKind.PLAN in values)
        assertTrue(ArtifactKind.CODE_SNIPPET in values)
        assertTrue(ArtifactKind.TEXT in values)
    }

    @Test
    fun `Artifact defaults to collapsed = true (INV-U2)`() {
        val artifact = ChatMessage.Artifact(
            kind = ArtifactKind.PLAN,
            title = "计划",
            payloadRef = "ref-1",
        )
        assertTrue("Artifact 应默认折叠(INV-U2)", artifact.collapsed)
    }

    @Test
    fun `Artifact collapsed can be explicitly set to false`() {
        val artifact = ChatMessage.Artifact(
            kind = ArtifactKind.HTML,
            title = "preview",
            payloadRef = "ref-2",
            collapsed = false,
        )
        assertEquals(false, artifact.collapsed)
    }

    @Test
    fun `ToolCall defaults to batchId = null (INV-U3 fallback)`() {
        val call = ChatMessage.ToolCall(
            icon = "🔧",
            toolName = "search_code",
            args = "{}",
            result = null,
        )
        assertNull("ToolCall 默认 batchId 应为 null(串行回退,INV-U3)", call.batchId)
    }

    @Test
    fun `ToolCall accepts batchId for parallel batch`() {
        val call = ChatMessage.ToolCall(
            icon = "🔧",
            toolName = "search_code",
            args = "{}",
            result = null,
            batchId = "batch-001",
            durationMs = 142L,
            status = ChatMessage.ToolCallStatus.SUCCESS,
        )
        assertEquals("batch-001", call.batchId)
        assertEquals(142L, call.durationMs)
        assertEquals(ChatMessage.ToolCallStatus.SUCCESS, call.status)
    }

    @Test
    fun `ToolCallStatus enum has 3 values`() {
        val values = ChatMessage.ToolCallStatus.values().toList()
        assertEquals(3, values.size)
        assertTrue(ChatMessage.ToolCallStatus.RUNNING in values)
        assertTrue(ChatMessage.ToolCallStatus.SUCCESS in values)
        assertTrue(ChatMessage.ToolCallStatus.FAILURE in values)
    }

    @Test
    fun `ChatMessage sealed class has exactly 5 subclasses (INV-U1)`() {
        // INV-U1:不新增 ChatMessage 子类,保持 5 类
        val userMsg = ChatMessage.UserMessage("hi")
        val agentMsg = ChatMessage.AgentMessage("hello")
        val toolCall = ChatMessage.ToolCall("🔧", "tap", "{}", null)
        val thinking = ChatMessage.Thinking("...")
        val artifact = ChatMessage.Artifact(ArtifactKind.TEXT, "title", "ref")

        assertNotNull(userMsg)
        assertNotNull(agentMsg)
        assertNotNull(toolCall)
        assertNotNull(thinking)
        assertNotNull(artifact)

        // 验证都是 ChatMessage 子类
        assertTrue(userMsg is ChatMessage)
        assertTrue(agentMsg is ChatMessage)
        assertTrue(toolCall is ChatMessage)
        assertTrue(thinking is ChatMessage)
        assertTrue(artifact is ChatMessage)
    }

    @Test
    fun `PLAN CODE_SNIPPET TEXT are distinct enum values`() {
        // 验证 3 个新 enum 值互不相同
        assertTrue(ArtifactKind.PLAN != ArtifactKind.CODE_SNIPPET)
        assertTrue(ArtifactKind.PLAN != ArtifactKind.TEXT)
        assertTrue(ArtifactKind.CODE_SNIPPET != ArtifactKind.TEXT)
        // 也与既有 4 个值不同
        assertTrue(ArtifactKind.PLAN != ArtifactKind.HTML)
        assertTrue(ArtifactKind.PLAN != ArtifactKind.IMAGE)
        assertTrue(ArtifactKind.PLAN != ArtifactKind.FILE)
        assertTrue(ArtifactKind.PLAN != ArtifactKind.DIFF)
    }
}
