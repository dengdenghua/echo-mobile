package com.apk.claw.android.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.apk.claw.android.R
import com.apk.claw.android.ui.browser.BrowserActivity
import com.apk.claw.android.ui.splash.SplashActivity
import com.apk.claw.android.ui.voice.VoiceCallActivity

/**
 * Octopus 桌面小组件(4x1 横条)。
 *
 * 设计原则(对齐 App 整体 Obsidian 扁平风格):
 *  - 深色背景 + 章鱼 logo + 应用名 + 状态文本
 *  - 无阴影、无圆角气泡、无 backdrop blur
 *  - 两个快捷按钮:语音助手 / 浏览器
 *
 * 交互:
 *  - 点击 logo / 标题区:启动 SplashActivity(走完整初始化流程)
 *  - 点击语音按钮:直启 VoiceCallActivity(实时语音对话)
 *  - 点击浏览器按钮:直启 BrowserActivity(快速搜索)
 *
 * 更新策略:
 *  - updatePeriodMillis=0(不自动轮询,省电)
 *  - 仅在安装 / 系统启动 / 配置变化时触发 [onUpdate]
 *  - 后续可扩展:onAppWidgetOptionsChanged 按宽度调整文案
 *
 * 安全:
 *  - 所有 Intent 显式指向 Octopus 内部 Activity,exported=false 的 Activity 也能通过 PendingIntent 启动
 *  - PendingIntent.FLAG_IMMUTABLE(Android 12+ 强制),防止 Intent 劫持
 */
class OctopusAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            val views = buildViews(context)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    /**
     * 构建 widget RemoteViews。
     *
     * 用 RemoteViews(非 Compose)因 widget 跑在 launcher 进程,只能用 RemoteViews 跨进程渲染。
     * @param context 任意 Context,用于 inflate 和构造 PendingIntent
     */
    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_octopus_quick)

        // logo + 标题区:点击启动 App
        val launchIntent = Intent(context, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launchPi = PendingIntent.getActivity(
            context, REQ_LAUNCH, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_icon, launchPi)
        views.setOnClickPendingIntent(R.id.widget_title, launchPi)
        views.setOnClickPendingIntent(R.id.widget_subtitle, launchPi)

        // 语音按钮:直启 VoiceCallActivity
        val voiceIntent = Intent(context, VoiceCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val voicePi = PendingIntent.getActivity(
            context, REQ_VOICE, voiceIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_btn_voice, voicePi)

        // 浏览器按钮:直启 BrowserActivity
        val browserIntent = Intent(context, BrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val browserPi = PendingIntent.getActivity(
            context, REQ_BROWSER, browserIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_btn_browser, browserPi)

        return views
    }

    companion object {
        private const val REQ_LAUNCH = 1
        private const val REQ_VOICE = 2
        private const val REQ_BROWSER = 3

        /**
         * 主动触发所有已添加 widget 的更新。
         *
         * 用于:配置变化(如连接状态变更)后让 widget 刷新文案。
         * AppWidgetManager 会回调 [onUpdate]。
         */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, OctopusAppWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, OctopusAppWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
