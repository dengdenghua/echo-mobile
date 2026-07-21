package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * refine-chat-interaction Task 4 —— 代码片段详情面板。
 *
 * 渲染 [file] 路径(标题)+ 行号区间 + 代码片段。代码用单色等宽字体,
 * 不引入语法高亮库(INV-U5)。行号左对齐(TextMuted),代码右对齐占主区域(TextSecondary)。
 * 长代码可纵向滚动。
 *
 * @param file 文件路径(如 `app/Foo.kt`)
 * @param startLine 起始行号(1-based)
 * @param endLine 结束行号(1-based,包含)
 * @param snippet 代码片段原文(不含行号前缀)
 */
class CodeDetailPane(
    private val file: String,
    private val startLine: Int,
    private val endLine: Int,
    private val snippet: String,
) : DetailPane {

    override val title: String = "$file:$startLine-$endLine"

    @Composable
    override fun Render() {
        val lines = remember(snippet) { snippet.lineSequence().toList() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "$file · 行 $startLine-$endLine",
                fontSize = OctopusType.caption,
                color = OctopusColors.TextMuted,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(OctopusSpacing.sm))
            lines.forEachIndexed { idx, line ->
                val lineNo = startLine + idx
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "$lineNo",
                        fontSize = OctopusType.caption,
                        color = OctopusColors.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.Top),
                    )
                    Spacer(modifier = Modifier.width(OctopusSpacing.md))
                    Text(
                        line.ifBlank { " " },  // 空行占位,避免 Compose 折叠高度
                        fontSize = OctopusType.caption,
                        color = OctopusColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
