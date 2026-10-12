package com.apk.claw.android.ui.compose.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReadingTest {
    @Test fun sameRowKeepsTheOffsetAfterEarlierRowsAreTrimmed() {
        val rows = listOf(ReadingRow("m20", listOf(20L)), ReadingRow("m30", listOf(30L)))
        assertEquals(ReadingPosition(1, 117), resolveReadingPosition(ReadingAnchor("m30", 30, 8, 117), rows))
    }

    @Test fun regroupedToolsKeepTheirMessageAnchorWithoutReusingTheOldCardOffset() {
        val rows = listOf(ReadingRow("g20", listOf(20L, 30L)), ReadingRow("m40", listOf(40L)))
        assertEquals(ReadingPosition(0, 0), resolveReadingPosition(ReadingAnchor("g30", 30, 4, 91), rows))
    }

    @Test fun expiredAnchorChoosesTheNextSurvivingMessageAndEmptyHistoryIsSafe() {
        val anchor = ReadingAnchor("m20", 20, 8, 117)
        assertEquals(ReadingPosition(0, 0), resolveReadingPosition(anchor,
            listOf(ReadingRow("m30", listOf(30L)), ReadingRow("m40", listOf(40L)))))
        assertEquals(ReadingPosition(0, 0), resolveReadingPosition(anchor, emptyList()))
    }

    @Test fun onlyCompletedAnswersAndRejectedRequestsAreNewResults() {
        val streaming = ChatMessage.AgentMessage("partial", streaming = true)
        val answer = ChatMessage.AgentMessage("finished", remoteTaskId = "task")
        val failed = ChatMessage.UserMessage("prompt", requestId = "request", delivery = MessageDeliveryStatus.FAILED)
        val messages = listOf(streaming, answer, failed, ChatMessage.Thinking("thinking"),
            ChatMessage.ToolCall("", "tool", "{}", null, status = ChatMessage.ToolCallStatus.RUNNING))
        assertEquals(setOf("task:task", "rejected:request"), conversationResultKeys(messages))
        assertEquals(setOf("task:task"), conversationResultKeys(listOf(answer, answer.copy(text = "updated"))))
        assertFalse(conversationResultKeys(listOf(failed.copy(delivery = MessageDeliveryStatus.SENDING)))
            .contains("rejected:request"))
    }

    @Test fun allPersistedMessageKindsKeepTheirIdsAndNewMessagesCannotReuseThem() {
        val messages = listOf(ChatMessage.UserMessage("prompt"), ChatMessage.AgentMessage("answer"),
            ChatMessage.ToolCall("", "tool", "{}", "done"),
            ChatMessage.Artifact(ChatMessage.ArtifactKind.FILE, "file", "ref"))
        val restored = ChatStore.decode(ChatStore.encode(messages))!!
        assertEquals(messages.map { it.id }, restored.map { it.id })
        assertTrue(ChatMessage.UserMessage("next").id > restored.maxOf { it.id })
    }

    @Test fun mixedLegacyAndStableHistoryAllocatesIdsAboveTheEntireLoadedRange() {
        val restored = ChatStore.decode("""[
            {"type":"user","a":"legacy"},
            {"type":"agent","a":"saved","messageId":9000001}
        ]""")!!
        assertEquals(9000001L, restored[1].id)
        assertTrue(restored[0].id > restored[1].id)
        assertEquals(restored.map { it.id }, ChatStore.decode(ChatStore.encode(restored))!!.map { it.id })
    }
}
