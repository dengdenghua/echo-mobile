package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.apk.claw.android.ui.compose.screen.ChatMessage
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * refine-chat-interaction Task 4 —— 工具执行详情面板。
 *
 * 由 ToolBatchCard 的「详情」入口打开,逐行渲染工具调用:
 * - 状态图标(✓ 绿 / ✗ 红 / ⏳ 主色)+ 工具名 + 耗时 + result 摘要
 * - 点击单行展开查看完整 result(折叠默认)
 *
 * 极简 flat UI(INV-U5):OctopusShape.small 圆角、单色实底、无阴影、无渐变。
 *
 * @param calls 同一批次的工具调用列表(来自 ToolBatchCard)
 */
class ToolsDetailPane(
    private val calls: List<ChatMessage.ToolCall>,
) : DetailPane {

    override val title: String = "工具执行详情"

    @Composable
    override fun Render() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            calls.forEach { call -> ToolCallRow(call) }
        }
    }

    @Composable
    private fun ToolCallRow(call: ChatMessage.ToolCall) {
        var expanded by remember(call.id) { mutableStateOf(false) }
        Surface(
            shape = OctopusShape.small,
            color = OctopusColors.SurfaceDeep,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = OctopusSpacing.xs)
                .clickable { expanded = !expanded },
        ) {
            Column(modifier = Modifier.padding(OctopusSpacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (call.status) {
                            ChatMessage.ToolCallStatus.SUCCESS -> "✓"
                            ChatMessage.ToolCallStatus.FAILURE -> "✗"
                            ChatMessage.ToolCallStatus.RUNNING -> "⏳"
                        },
                        color = when (call.status) {
                            ChatMessage.ToolCallStatus.SUCCESS -> OctopusColors.Success
                            ChatMessage.ToolCallStatus.FAILURE -> OctopusColors.Error
                            ChatMessage.ToolCallStatus.RUNNING -> OctopusColors.Primary
                        },
                        fontSize = OctopusType.caption,
                    )
                    Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                    Text(
                        call.toolName,
                        fontSize = OctopusType.caption,
                        color = OctopusColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    call.durationMs?.let {
                        Spacer(modifier = Modifier.width(OctopusSpacing.xs))
                        Text("${it}ms", fontSize = OctopusType.tag, color = OctopusColors.TextMuted)
                    }
                }
                val summary = remember(call.result) { summarize(call.result) }
                if (summary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(OctopusSpacing.xs))
                    Text(
                        if (expanded) call.result.orEmpty() else summary,
                        fontSize = OctopusType.tag,
                        color = OctopusColors.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = if (expanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    private fun summarize(result: String?): String {
        if (result.isNullOrBlank()) return ""
        val firstLine = result.lineSequence().firstOrNull().orEmpty()
        return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
    }
}
