package com.apk.claw.android.ui.compose.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.apk.claw.android.R

/** Show failures and stops even when a batch is folded. */
@Composable
internal fun toolExecutionSummary(calls: List<ChatMessage.ToolCall>): String {
    val counts = calls.groupingBy { it.status }.eachCount()
    val labels = listOf(
        ChatMessage.ToolCallStatus.RUNNING to R.plurals.tool_detail_running_count,
        ChatMessage.ToolCallStatus.SUCCESS to R.plurals.tool_detail_success_count,
        ChatMessage.ToolCallStatus.FAILURE to R.plurals.tool_detail_failure_count,
        ChatMessage.ToolCallStatus.CANCELLED to R.plurals.tool_detail_stopped_count,
    )
    return labels.mapNotNull { (status, resource) ->
        counts[status]?.let { pluralStringResource(resource, it, it) }
    }.joinToString(" · ")
}
