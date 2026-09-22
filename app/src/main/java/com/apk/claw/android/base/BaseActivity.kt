package com.apk.claw.android.base

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.blankj.utilcode.util.AdaptScreenUtils
import com.blankj.utilcode.util.BarUtils
import com.apk.claw.android.R
import com.apk.claw.android.utils.DeviceUtils

/**
 *
 * 屏幕适配单位用的是pt, 如果用dp在有些设备上会导致toast换行位置不正确
 */
open class BaseActivity : AppCompatActivity() {

    override fun getResources(): Resources {
        val resources = super.getResources()
        return AdaptScreenUtils.adaptWidth(resources, getDesignWidth())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStatusBarMode()

        // 统一处理状态栏高度 - 在布局加载后应用
        applyStatusBarPadding()

        // 无障碍保活的返回拦截(实现与说明见 KeepAliveBack;MainActivity/DesktopActivity
        // 不经过 BaseActivity,它们各自的 onCreate 里也要装)。
        KeepAliveBack.install(this)
    }

    /**
     * 主题切换时自动回调，更新状态栏文字颜色
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarMode()
    }

    /**
     * TV 遥控器按键兜底:DPAD_CENTER/ENTER → 模拟点击当前焦点元素
     * (确保 Compose 可点击元素能被遥控器"确认")。
     *
     * 这里刻意**不**拦截 KEYCODE_BACK:Manifest 已开
     * android:enableOnBackInvokedCallback="true" 且 targetSdk 36,返回事件统一由
     * OnBackPressedDispatcher 处理(AndroidX 在低版本上也会把物理返回键汇入 dispatcher)。
     * 在 dispatchKeyEvent 里再拦一次属于重复拦截,且对 Android 13+ 的返回手势无效
     * (lint GestureBackNavigation 报的就是这个)。
     *
     * 手机上走默认分发,不影响触摸交互。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (DeviceUtils.isTvDevice(this)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        // 模拟点击当前焦点元素
                        val focused = currentFocus
                        if (focused != null) {
                            focused.performClick()
                            return true
                        }
                    }
                }

            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 根据当前主题模式设置状态栏文字颜色
     * 浅色主题 → 深色文字（light mode）
     * 深色主题 → 浅色文字（dark mode）
     */
    private fun applyStatusBarMode() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        BarUtils.setStatusBarLightMode(this, !isNightMode)
    }

    /**
     * 为根视图添加状态栏高度的顶部 padding
     * 子类可通过重写 isApplyStatusBarPadding() 来禁用
     */
    private fun applyStatusBarPadding() {
        window.decorView.post {
            val rootView = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            rootView?.let { applyPaddingToRootView(it) }
        }
    }

    /**
     * 为根视图应用状态栏高度 padding
     * 如果布局已经处理了 insets，可以重写此方法自定义行为
     */
    protected open fun applyPaddingToRootView(rootView: View) {
        if (!isApplyStatusBarPadding()) return

        val statusBarHeight = BarUtils.getStatusBarHeight()
        val existingPaddingTop = rootView.paddingTop

        // 只在没有 padding 或 padding 小于状态栏高度时才添加
        if (existingPaddingTop < statusBarHeight) {
            rootView.updatePadding(top = statusBarHeight)
        }
    }

    /**
     * 子类可重写此方法来禁用自动添加状态栏高度
     */
    protected open fun isApplyStatusBarPadding(): Boolean = true

    /**
     * 屏幕适配-设计稿尺寸
     */
    open fun getDesignWidth(): Int {
        return 402
    }
}