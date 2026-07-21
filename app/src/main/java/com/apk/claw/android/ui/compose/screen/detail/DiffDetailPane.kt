package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * refine-chat-interaction Task 4 —— Diff 详情面板。
 *
 * ChatScreen.kt 内的 [DiffView] 是 private,无法跨文件复用;
 * 本类在 detail 包内重新实现一个简化版(增行绿 / 删行红 / hunk 头主色 / 其余次要色),
 * 行为与 ChatScreen.kt DiffView 一致,但不截断(详情面板可滚动)。
 *
 * 极简 flat UI(INV-U5):OctopusShape.small 圆角、SurfaceDeep 单色实底、无阴影、无渐变。
 *
 * @param diff unified diff 文本
 */
class DiffDetailPane(
    private val diff: String,
) : DetailPane {

    override val title: String = "代码改动"

    @Composable
    override fun Render() {
        val lines = remember(diff) { diff.lineSequence().toList() }
        Surface(
            shape = OctopusShape.small,
            color = OctopusColors.SurfaceDeep,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(OctopusSpacing.sm)) {
                if (lines.isEmpty()) {
                    Text(
                        "（diff 为空）",
                        fontSize = OctopusType.caption,
                        color = OctopusColors.TextMuted,
                    )
                } else {
                    lines.forEach { line -> DiffLine(line) }
                }
            }
        }
    }

    @Composable
    private fun DiffLine(line: String) {
        val color = when {
            line.startsWith("+") && !line.startsWith("+++") -> OctopusColors.Success
            line.startsWith("-") && !line.startsWith("---") -> OctopusColors.Error
            line.startsWith("@@") -> OctopusColors.Primary
            else -> OctopusColors.TextSecondary
        }
        Text(
            line.ifBlank { " " },
            fontSize = OctopusType.caption,
            color = color,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
