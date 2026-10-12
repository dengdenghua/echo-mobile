package com.apk.claw.android.ui.compose.screen

/** Keep a sequential execution window attached to its user turn, including later tool calls. */
internal class ToolDetailSelection(messages: List<ChatMessage>, calls: List<ChatMessage.ToolCall>) {
    private val ids = calls.map { it.id }.toSet()
    private val batches = calls.mapNotNull { it.batchId }.toSet()
    private val start = messages.indexOfFirst { it.id in ids }
    private val userId = messages.take(start.coerceAtLeast(0)).lastOrNull { it is ChatMessage.UserMessage }?.id

    fun resolve(messages: List<ChatMessage>): List<ChatMessage.ToolCall> {
        val turnStart = messages.indexOfFirst { it.id == userId }
        val hasTurn = userId != null && turnStart >= 0
        val turnEnd = messages.indices.firstOrNull {
            it > turnStart && messages[it] is ChatMessage.UserMessage
        } ?: messages.size
        val turn = if (hasTurn) messages.subList(turnStart + 1, turnEnd) else messages
        return turn.filterIsInstance<ChatMessage.ToolCall>().filter {
            it.id in ids || if (batches.isNotEmpty()) it.batchId in batches else hasTurn
        }
    }
}
