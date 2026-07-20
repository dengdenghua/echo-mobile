package com.apk.claw.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.workflow.WorkflowEngine
import com.apk.claw.android.octopus_mobile.workflow.WorkflowStore
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 工作流定时到点接收器 —— [WorkflowScheduler] 注册的闹钟触发此处。
 *
 * 流程：拉起前台服务保活 → 处理续期（每天重排 / 一次性清除）→ 异步执行工作流。
 * 执行结果通过通知告知用户（成功/失败/跳过）。
 *
 * 不做忙时重试（与 [RoutineAlarmReceiver] 区别）：工作流通常包含 PROMPT 步骤，
 * 与正在跑的 Agent 抢资源会双输；直接跳过发通知，让用户下次手动重跑或重新定时。
 */
class WorkflowAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WorkflowAlarmReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(WorkflowScheduler.EXTRA_ID) ?: return
        val wf = WorkflowStore.get(context, id) ?: return
        XLog.i(TAG, "alarm fired for workflow ${wf.id} (${wf.name})")

        // 保活：长任务下确保前台服务在跑
        runCatching { ForegroundService.start(context) }

        // 续期：每天重排下一天；一次性则清除定时字段
        if (wf.scheduleDaily) {
            runCatching { WorkflowScheduler.schedule(context, wf) }
        } else {
            WorkflowStore.save(context, wf.copy(scheduleHour = null, scheduleMinute = null, scheduleDaily = false))
        }

        // 异步执行（WorkflowEngine.execute 是 suspend）
        // 定时触发=无人值守=不可信来源：HIGH/MEDIUM 工具走 ApprovalFlow，
        // 用户不在场时被策略拦截是预期行为（避免定时闹钟偷偷干高危操作）。
        scope.launch {
            val startMs = System.currentTimeMillis()
            val result = try {
                WorkflowEngine(context, untrustedSource = true).execute(wf)
            } catch (e: Throwable) {
                XLog.e(TAG, "workflow ${wf.id} crashed", e)
                notify(context, wf.id.hashCode(),
                    context.getString(R.string.routine_alarm_skipped_title),
                    "「${wf.name}」执行失败：${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            // 更新运行次数
            WorkflowStore.save(context, wf.copy(
                lastRunAt = startMs,
                runCount = wf.runCount + 1,
            ))
            val title = if (result.success) "工作流完成"
                else context.getString(R.string.routine_alarm_skipped_title)
            val summary = buildString {
                append("「${wf.name}」")
                append(if (result.success) "✓ " else "✗ ")
                append(if (result.success) "完成" else (result.error ?: "失败"))
                append("（${result.logs.size} 步，${result.durationMs / 1000}s）")
            }
            notify(context, wf.id.hashCode(), title, summary)
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(ctx, ForegroundService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }
}
