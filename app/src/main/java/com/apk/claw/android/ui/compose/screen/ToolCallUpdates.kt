package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.agent.ToolExecutionProgress
import com.apk.claw.android.agent.boundedToolOutput

private const val TOOL_SUMMARY_CHARS = 48

/** Upsert by execution ID, preserving the LazyColumn row key through every phase. */
internal fun updateToolCall(messages: MutableList<ChatMessage>, progress: ToolExecutionProgress) {
    val index = messages.indexOfFirst { it is ChatMessage.ToolCall && it.callId == progress.callId }
    val old = messages.getOrNull(index) as? ChatMessage.ToolCall
    // Late reader callbacks cannot resurrect a stopped/completed execution.
    if (old != null && old.status != ChatMessage.ToolCallStatus.RUNNING) return
    val status = if (progress.phase == ToolExecutionProgress.Phase.FINISHED) {
        if (progress.success == true) ChatMessage.ToolCallStatus.SUCCESS else ChatMessage.ToolCallStatus.FAILURE
    } else ChatMessage.ToolCallStatus.RUNNING
    val updated = (old ?: ChatMessage.ToolCall("⚙", progress.displayName, progress.arguments, null,
        callId = progress.callId, batchId = progress.batchId)).copy(
        result = boundedToolOutput(progress.output).ifBlank { null },
        status = status,
        durationMs = progress.durationMs,
    )
    if (index < 0) messages.add(updated) else messages[index] = updated
}

internal fun interruptToolCalls(messages: MutableList<ChatMessage>, reason: String) {
    messages.indices.forEach { index ->
        val call = messages[index] as? ChatMessage.ToolCall ?: return@forEach
        if (call.status == ChatMessage.ToolCallStatus.RUNNING) {
            messages[index] = call.copy(status = ChatMessage.ToolCallStatus.CANCELLED,
                result = boundedToolOutput(listOfNotNull(call.result, reason).joinToString("\n")))
        }
    }
}

internal fun toolCallSummary(call: ChatMessage.ToolCall): String = when (call.status) {
    ChatMessage.ToolCallStatus.RUNNING -> "执行中…"
    ChatMessage.ToolCallStatus.CANCELLED -> "已停止"
    ChatMessage.ToolCallStatus.SUCCESS ->
        "✓ " + (call.result?.lineSequence()?.firstOrNull() ?: "完成").take(TOOL_SUMMARY_CHARS)
    ChatMessage.ToolCallStatus.FAILURE ->
        "✗ " + (call.result?.lineSequence()?.firstOrNull() ?: "失败").take(TOOL_SUMMARY_CHARS)
}
