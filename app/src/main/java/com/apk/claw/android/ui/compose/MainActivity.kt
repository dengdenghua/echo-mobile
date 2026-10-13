package com.apk.claw.android.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import com.apk.claw.android.base.KeepAliveBack
import com.apk.claw.android.ui.compose.theme.LocalWindowWidthSizeClass
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import com.apk.claw.android.update.AppUpdater

/**
 * Compose 入口 Activity —— 只负责挂载 [OctopusApp] 到 window。
 * 真正的导航/UI 树在 [NavigationGraph.kt]。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 无障碍保活:根页滑动返回改退后台,别 finish 掉进程连带杀无障碍服务。
        KeepAliveBack.install(this)
        setContent {
            // 计算窗口宽度尺寸等级并通过 CompositionLocal 全局下发,
            // 供各页面按大屏(TV/平板)/手机断点自适应布局。
            // 断点与 Material3 WindowSizeClass 规范一致:< 600 Compact / 600-840 Medium / > 840 Expanded。
            val widthDp = LocalConfiguration.current.screenWidthDp
            val widthSizeClass = when {
                widthDp < 600 -> WindowWidthSizeClass.Compact
                widthDp < 840 -> WindowWidthSizeClass.Medium
                else -> WindowWidthSizeClass.Expanded
            }
            CompositionLocalProvider(
                LocalWindowWidthSizeClass provides widthSizeClass
            ) {
                OctopusTheme {
                    OctopusApp()
                    // 在线更新:根部挂弹窗宿主 + 启动静默查一次(有新版才弹,已最新无感)。
                    // 每个进程只查一次:Activity 重建(旋转/换主题/换语言)不再重复请求。
                    AppUpdateHost()
                    LaunchedEffect(Unit) { runCatching { AppUpdater.checkOncePerProcess() } }
                }
            }
        }
    }
}
