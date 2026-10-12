package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
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
 * - 默认展开参数与完整 result;点击行可折叠
 *
 * 极简 flat UI(INV-U5):OctopusShape.small 圆角、单色实底、无阴影、无渐变。
 *
 * @param calls 同一批次的工具调用列表(来自 ToolBatchCard)
 */
class ToolsDetailPane(
    calls: List<ChatMessage.ToolCall> = emptyList(),
    private val callsProvider: () -> List<ChatMessage.ToolCall> = { calls },
) : DetailPane {

    override val title: String = "工具执行详情"

    @Composable
    override fun Render() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            val calls = callsProvider()
            if (calls.isEmpty()) Text("暂无执行记录", color = OctopusColors.TextMuted)
            calls.forEach { call -> ToolCallRow(call) }
        }
    }

    @Composable
    private fun ToolCallRow(call: ChatMessage.ToolCall) {
        var expanded by remember(call.id) { mutableStateOf(true) }
        Surface(
            shape = OctopusShape.small,
            color = OctopusColors.SurfaceDeep,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = OctopusSpacing.xs)
                .clickable { expanded = !expanded },
        ) {
            Column(modifier = Modifier.padding(OctopusSpacing.sm)) {
                ToolCallHeader(call)
                if (expanded) {
                    Spacer(modifier = Modifier.height(OctopusSpacing.sm))
                    SelectionContainer {
                        Column {
                            if (call.args.isNotBlank()) {
                                Text(call.args, fontSize = OctopusType.tag,
                                    color = OctopusColors.TextSecondary, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(OctopusSpacing.sm))
                            }
                            Text(call.result ?: if (call.status == ChatMessage.ToolCallStatus.RUNNING)
                                "执行中，等待输出…" else "无文本输出",
                                fontSize = OctopusType.tag, color = OctopusColors.TextMuted,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    Text(com.apk.claw.android.ui.compose.screen.toolCallSummary(call),
                        fontSize = OctopusType.tag, color = OctopusColors.TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }


    @Composable
    private fun ToolCallHeader(call: ChatMessage.ToolCall) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (call.status) {
                    ChatMessage.ToolCallStatus.SUCCESS -> "✓"
                    ChatMessage.ToolCallStatus.FAILURE -> "✗"
                    ChatMessage.ToolCallStatus.RUNNING -> "⏳"
                    ChatMessage.ToolCallStatus.CANCELLED -> "■"
                },
                color = when (call.status) {
                    ChatMessage.ToolCallStatus.SUCCESS -> OctopusColors.Success
                    ChatMessage.ToolCallStatus.FAILURE -> OctopusColors.Error
                    ChatMessage.ToolCallStatus.RUNNING -> OctopusColors.Primary
                    ChatMessage.ToolCallStatus.CANCELLED -> OctopusColors.TextMuted
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
    }
}
