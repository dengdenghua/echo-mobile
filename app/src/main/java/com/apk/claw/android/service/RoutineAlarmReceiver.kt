package com.apk.claw.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.ui.compose.screen.ChatAgentBridge
import com.apk.claw.android.ui.compose.screen.RoutineRunner
import com.apk.claw.android.utils.XLog

/**
 * 例程定时到点接收器 —— [RoutineScheduler] 注册的闹钟触发此处。
 *
 * 流程：拉起前台服务保活 → 处理续期（每天重排 / 一次性清除）→ 满足条件则重放例程，
 * 否则发通知告知用户被跳过。
 */
class RoutineAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RoutineAlarmReceiver"
        private const val MAX_BUSY_RETRIES = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(RoutineScheduler.EXTRA_ID) ?: return
        val retryAttempt = intent.getIntExtra(RoutineScheduler.EXTRA_RETRY_ATTEMPT, 0)
        val r = RoutineStore.all().find { it.id == id } ?: return
        XLog.i(TAG, "alarm fired for routine ${r.id} (${r.name})")

        // 保活：从后台执行长任务，先确保前台服务在跑
        runCatching { ForegroundService.start(context) }

        // 续期：只在原始闹钟触发时处理。忙时重试不改动用户设定。
        if (retryAttempt == 0) {
            if (r.scheduleDaily) {
                runCatching { RoutineScheduler.schedule(context, r) }
            } else {
                RoutineStore.update(r.copy(scheduleHour = null, scheduleMinute = null, scheduleDaily = false))
            }
        }

        // 执行
        if (!RoutineRunner.canRun()) {
            notify(context, r.id.hashCode(), context.getString(R.string.routine_alarm_skipped_title), context.getString(R.string.routine_alarm_skipped_text, r.name))
        } else if (ChatAgentBridge.isBusy() && retryAttempt < MAX_BUSY_RETRIES) {
            val nextAttempt = retryAttempt + 1
            RoutineScheduler.scheduleRetry(context, r.id, nextAttempt)
            notify(context, r.id.hashCode(), context.getString(R.string.routine_alarm_retry_title), context.getString(R.string.routine_alarm_retry_text, r.name, nextAttempt, MAX_BUSY_RETRIES))
        } else if (ChatAgentBridge.isBusy()) {
            notify(context, r.id.hashCode(), context.getString(R.string.routine_alarm_skipped_title), context.getString(R.string.routine_alarm_busy_give_up_text, r.name))
        } else {
            val status = RoutineRunner.run(context, r)
            notify(context, r.id.hashCode(), context.getString(R.string.routine_alarm_running_title), context.getString(R.string.routine_alarm_running_text, r.name, status))
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 复用前台服务的通知渠道（若尚未创建，前台服务启动时会创建）
        val n = NotificationCompat.Builder(ctx, ForegroundService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }
}
