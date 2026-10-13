package com.apk.claw.android.ui.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 页面临时收起底部 Dock 的开关（[OctopusApp] 提供）。
 *
 * 对话页的会话抽屉在 NavHost 内容区里，而 Dock 是 Scaffold 的 bottomBar，画在内容区之外；
 * 抽屉打开时如果 Dock 还在，它会压在抽屉遮罩上面。抽屉打开时置 true，Dock 收起，抽屉和遮罩盖满整屏。
 */
val LocalBottomBarHidden = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }
