package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.runtime.Composable

/**
 * 右侧栏详情面板接口 —— refine-chat-interaction Task 3。
 *
 * 所有 Artifact 详情(Plan/Code/Diff/Text/Tools)实现此接口,
 * 在 [DetailDrawer] 中渲染。消息流不展开详情(INV-U2)。
 */
interface DetailPane {
    /** 面板标题(显示在抽屉顶栏)。 */
    val title: String

    /** 渲染面板内容。 */
    @Composable
    fun Render()
}
