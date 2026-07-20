package com.apk.claw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apk.claw.android.utils.XLog

/**
 * 开机自启动广播接收器
 * 收到 BOOT_COMPLETED 后启动前台服务，保持后台运行
 * 不启动 Activity，避免与用户手动打开 App 冲突
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            XLog.i(TAG, "收到开机广播，启动保活链路")
            // 1. 启动前台服务保活进程
            ForegroundService.start(context)
            // 2. 调度 15 分钟守护 Job（FGS 被杀后重拉）
            runCatching { KeepAliveJobService.schedule(context) }
            // 3. 开机后系统清空了闹钟，重新注册所有已定时的例程
            runCatching { RoutineScheduler.rescheduleAll(context) }
            // 4. 同样重新注册所有已定时的工作流
            runCatching { WorkflowScheduler.rescheduleAll(context) }
        }
    }
}
