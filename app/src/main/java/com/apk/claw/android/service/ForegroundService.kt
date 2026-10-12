package com.apk.claw.android.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apk.claw.android.R
import com.apk.claw.android.ui.home.HomeActivity
import com.apk.claw.android.utils.XLog

/**
 * 前台服务 - 常驻通知
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val CHANNEL_ID = "octopus_mobile_foreground_channel"
        const val NOTIFICATION_ID = 1001
        private const val RESTART_DELAY_FAST_MS = 1000L
        private const val RESTART_DELAY_BACKUP_MS = 10000L
        private const val RESTART_REQUEST_CODE_BACKUP_OFFSET = 10

        @Volatile
        private var _isRunning = false

        /**
         * 检查进程是否已被保活（前台服务或无障碍服务前台状态）
         */
        fun isRunning(): Boolean {
            // 无障碍服务自身已作为前台服务运行时，进程已被保活。
            // 用 isConnected 不用 isRunning:enabled 列表回退会在服务已死的窗口误判「已保活」。
            if (ClawAccessibilityService.isConnected()) return true
            return _isRunning
        }

        /**
         * 启动前台服务
         * @param context Context
         * @return 是否成功启动（被系统拒绝时返回 false,绝不抛异常）
         */
        fun start(context: Context): Boolean {
            // 前台服务无需 POST_NOTIFICATIONS 即可启动保活 —— 该权限只决定「运行中」通知
            // 是否可见,不影响服务能否拉起。此前缺通知权限就 return false,导致前台服务从不
            // 启动、进程无保活,无障碍服务随后台被回收一起死(真机「滑返回无障碍就关」的真凶,
            // 2026-07-05 定位)。改为无条件启动;通知可见性另由权限引导解决。
            //
            // 必须整体 runCatching:Android 12+ 禁止后台拉前台服务
            // (ForegroundServiceStartNotAllowedException)。KeepAliveJobService 等兜底路径
            // 在后台触发时会走到这里 —— 异常一旦逃逸会炸掉进程,而本进程托管着无障碍服务,
            // 反复崩溃会被系统直接禁用无障碍,比不保活更糟。
            val intent = Intent(context, ForegroundService::class.java)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                android.util.Log.w(
                    "ForegroundService",
                    "start blocked (likely background FGS restriction): ${it.message}",
                )
            }.isSuccess
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        // ConfigServer(:9527)不归本服务所有(由设置页/生命周期管理器/远程网关按需启停),
        // 这里不能停:降级弹回时进程仍活着,停了没人再拉起。
        // 只有无障碍未实际连接时,本服务的销毁才意味着保活缺位,需要排闹钟重启。
        if (!ClawAccessibilityService.isConnected()) {
            scheduleRestart(0)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 同 onDestroy：仅当无障碍服务未实际连接时才需要保活重启
        if (!ClawAccessibilityService.isConnected()) {
            scheduleRestart(1)
        }
    }

    private fun scheduleRestart(requestCode: Int) {
        val restartIntent = Intent(applicationContext, ForegroundService::class.java)
        val pendingRestart = PendingIntent.getService(
            applicationContext, requestCode, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_FAST_MS, pendingRestart)
        val pendingBackup = PendingIntent.getService(
            applicationContext, requestCode + RESTART_REQUEST_CODE_BACKUP_OFFSET, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_BACKUP_MS, pendingBackup)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果无障碍服务实际连接中且自己是前台服务，本服务可以降级——
        // ClawAccessibilityService 自身已通过 startForeground 保活进程，无需双通知。
        // 必须用 isConnected:enabled 列表回退会在「列表仍启用但服务已死」时误降级,保活全丢。
        if (ClawAccessibilityService.isConnected()) {
            XLog.i(TAG, "A11y service already foreground, stopping this duplicate foreground")
            // FGS 契约:凡经 startForegroundService 拉起,必须先调一次 startForeground 才能停,
            // 否则 AMS 异步抛 RemoteServiceException(runCatching 拦不住)炸掉托管无障碍的进程,
            // 反复崩溃触发系统自动禁用无障碍 ——「侧滑清任务无障碍开关被扳关」的确定性根因
            // (2026-07-05 定位)。先履约提升,再用 REMOVE 收走重复通知(DETACH 会留孤儿通知)。
            runCatching { startForeground(NOTIFICATION_ID, createNotification()) }
                .onFailure { android.util.Log.w("ForegroundService", "contract startForeground failed: ${it.message}") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // stopSelf(startId) 而非 stopSelf():若并发的 startForegroundService 已插队重新
            // 武装 fgRequired,AMS 会拒绝本次拆除,让新 onStartCommand 重新履约,避免带契约自停
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = createNotification()
        // 同 start() 的理由:提权失败(后台限制/类型校验)不能炸掉托管无障碍的进程,
        // 宁可降级为普通后台服务苟着。
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { android.util.Log.w("ForegroundService", "startForeground failed: ${it.message}") }
        runCatching { RoutineScheduler.rescheduleAll(this) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(getString(R.string.notification_content_text))
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
}
