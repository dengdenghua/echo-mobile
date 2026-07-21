package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing

/**
 * 右侧详情抽屉 —— refine-chat-interaction Task 3。
 *
 * 与左侧会话历史抽屉对称,从右侧滑出。
 * 用于展示 Artifact 详情(Plan/Code/Diff/Text/Tools),
 * 不在消息流内展开(INV-U2)。
 *
 * 关闭时 [currentPane] = null,消息流可独立浏览(INV-U4)。
 *
 * 实现说明:Material3 的 ModalNavigationDrawer 默认左侧,右侧需自定义;
 * 此处采用 ModalBottomSheet 作为简化承载(扁平 UI、无阴影、圆角 8dp),
 * 与项目既有抽屉风格一致。后续如需真正的右侧侧滑抽屉可替换实现,
 * [DetailPane] 接口不变。
 *
 * @param currentPane 当前要展示的详情面板;null 表示关闭
 * @param onClose 关闭抽屉的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailDrawer(
    currentPane: DetailPane?,
    onClose: () -> Unit,
) {
    if (currentPane == null) return
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = OctopusColors.Surface,
        shape = OctopusShape.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(OctopusSpacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    currentPane.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            currentPane.Render()
        }
    }
}
