package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.apk.claw.android.ui.compose.screen.UserscriptStoreScreen

/**
 * 油猴脚本商店宿主 Activity —— 从浏览器菜单「脚本商店」入口启动。
 */
class UserscriptStoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { UserscriptStoreScreen(onBack = { finish() }) }
    }
}
