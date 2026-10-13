package com.apk.claw.android.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountGate
import com.apk.claw.android.account.Landing
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.ui.compose.MainActivity
import com.apk.claw.android.ui.desktop.DesktopActivity
import com.apk.claw.android.utils.KVUtils

/**
 * 启动页：未登录且没选过「不登录使用」→ 登录闸；已登录或本地模式 → 首页（或用户设的横屏桌面）。
 * 未配置 LLM 也可进入首页，可在设置中配置。
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* 启动页不允许返回 */ }
        })

        val intent = when (AccountGate.landing(desktopDefault = KVUtils.isDesktopModeDefault())) {
            Landing.LOGIN -> LoginActivity.gateIntent(this)
            Landing.DESKTOP -> Intent(this, DesktopActivity::class.java)
            Landing.MAIN -> Intent(this, MainActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
