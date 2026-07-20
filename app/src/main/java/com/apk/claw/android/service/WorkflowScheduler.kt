package com.apk.claw.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.apk.claw.android.octopus_mobile.workflow.Workflow
import com.apk.claw.android.octopus_mobile.workflow.WorkflowStore
import com.apk.claw.android.utils.XLog
import java.util.Calendar

/**
 * 工作流定时器 —— 用 [AlarmManager] 在指定时刻唤醒 [WorkflowAlarmReceiver] 执行工作流。
 *
 * 与 [RoutineScheduler] 平行：例程是单步指令重放，工作流是多步 DAG 编排，两者互不干扰。
 * - 每条已定时的工作流对应一个精确闹钟（requestCode = workflow id 的 hashCode）。
 * - 每天重复：触发后由接收器再排下一天；仅一次：触发后清除定时。
 * - 开机后闹钟会被系统清空，需 [rescheduleAll]（[BootReceiver] 调用）重新注册。
 */
object WorkflowScheduler {

    private const val TAG = "WorkflowScheduler"
    const val ACTION = "com.apk.claw.android.RUN_WORKFLOW"
    const val EXTRA_ID = "workflow_id"

    /** 注册/更新一条工作流的定时闹钟。 */
    fun schedule(ctx: Context, wf: Workflow) {
        val h = wf.scheduleHour ?: return
        val m = wf.scheduleMinute ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextTrigger(h, m)
        val pi = pendingIntent(ctx, wf.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            XLog.i(TAG, "scheduled ${wf.id} at $at (exact=$canExact, daily=${wf.scheduleDaily})")
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            XLog.w(TAG, "exact alarm denied, fell back to inexact: ${e.message}")
        }
    }

    /** 取消一条工作流的定时闹钟。 */
    fun cancel(ctx: Context, workflowId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, workflowId))
        XLog.i(TAG, "cancelled $workflowId")
    }

    /** 开机后重新注册所有已定时的工作流。 */
    fun rescheduleAll(ctx: Context) {
        WorkflowStore.all(ctx).filter { it.isScheduled }.forEach { schedule(ctx, it) }
    }

    /** 下一次触发时刻：今天的 HH:MM 若已过则顺延到明天。 */
    private fun nextTrigger(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(ctx: Context, workflowId: String): PendingIntent {
        val intent = Intent(ctx, WorkflowAlarmReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_ID, workflowId)
        }
        return PendingIntent.getBroadcast(
            ctx, workflowId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
