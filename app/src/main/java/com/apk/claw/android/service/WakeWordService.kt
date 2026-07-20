package com.apk.claw.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.apk.claw.android.R
import com.apk.claw.android.ui.voice.VoiceCallActivity
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.wakeword.EnergyVadEngine
import com.apk.claw.android.wakeword.WakeWordEngine
import com.apk.claw.android.wakeword.WakeWordSettings

/**
 * 唤醒词常驻前台服务 —— Android 14+ 必须声明 `foregroundServiceType="microphone"` 才能常驻麦克风。
 *
 * 生命周期:
 *  - [onStartCommand] 收到 ACTION_START → 资格检查(开关 + 同意 + 麦克风权限 + 电源条件)→
 *    `startForeground` 提权为 microphone FGS → 启动引擎
 *  - [onStartCommand] 收到 ACTION_STOP → 停引擎 + `stopSelf`
 *  - [onDestroy] 引擎随服务销毁一起释放 AudioRecord
 *
 * 命中后行为:
 *  - 启动 [VoiceCallActivity](与 AppWidget 语音按钮 / ASSIST 入口一致)
 *  - 由 Activity 自己申请运行时麦克风焦点(本服务在 Activity 启动后会自动 stop)
 *
 * 保活:
 *  - 不走 START_STICKY:常驻麦克风是用户主动选择,服务挂掉时不要偷偷拉回,
 *    让用户重新开关一次更尊重隐私
 *  - 与 [ForegroundService] 的"无条件保活"形成对比,这里走"用户开关即生命周期"
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_ID = "octopus_wakeword_channel"
        const val NOTIFICATION_ID = 1003
        const val ACTION_START = "com.apk.claw.android.action.START_WAKEWORD"
        const val ACTION_STOP = "com.apk.claw.android.action.STOP_WAKEWORD"

        @Volatile
        private var _isRunning = false

        /** 引擎是否在监听(给 UI 显示状态)。 */
        fun isRunning(): Boolean = _isRunning

        fun start(ctx: Context) {
            val intent = Intent(ctx, WakeWordService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, WakeWordService::class.java).setAction(ACTION_STOP)
            ctx.startService(intent)
        }
    }

    private var engine: WakeWordEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                // null intent 走 START_STICKY 重启路径:常驻麦克风场景拒绝悄悄拉回,直接停。
                if (intent?.action == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!canRun()) {
                    XLog.i(TAG, "条件不满足,拒绝启动唤醒词")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                startEngine()
            }
        }
        return START_NOT_STICKY
    }

    /** 启动前的所有资格检查 —— 任一失败都拒绝常驻麦克风(隐私 + 省电)。 */
    private fun canRun(): Boolean {
        if (!WakeWordSettings.isEnabled()) return false
        if (!WakeWordSettings.hasConsented()) return false
        if (!hasMicPermission()) return false
        if (WakeWordSettings.isOnlyCharging() && !isCharging()) return false
        if (WakeWordSettings.isOnlyScreenOff() && isScreenOn()) return false
        return true
    }

    private fun startEngine() {
        if (_isRunning) return
        val eng = createEngine()
        engine = eng
        _isRunning = true
        eng.start(
            keyword = WakeWordSettings.getKeyword(),
            sensitivity = WakeWordSettings.getSensitivity(),
            onDetected = ::onWakeWordDetected,
        )
        XLog.i(TAG, "WakeWord engine started: ${eng.id}, keyword=\"${WakeWordSettings.getKeyword()}\"")
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        _isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
    }

    /** 唤醒命中:启动语音通话页并自停(把麦克风让给 Activity)。 */
    private fun onWakeWordDetected() {
        XLog.i(TAG, "Wake word detected, launching VoiceCallActivity")
        runCatching {
            val intent = Intent(this, VoiceCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        // 让出麦克风:Activity 会接管录音
        stopEngine()
        stopSelf()
    }

    private fun createEngine(): WakeWordEngine {
        // 当前仅 ENERGY_VAD 引擎可用;后续 OpenWakeWord / Porcupine 接入后,根据 WakeWordSettings.getEngineId() 分流
        return EnergyVadEngine()
    }

    // ── 前台通知 ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wakeword_channel_name),
                NotificationManager.IMPORTANCE_LOW, // 常驻通知,低重要度不打扰
            ).apply {
                description = getString(R.string.wakeword_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须显式指定 foregroundServiceType,且与 manifest 声明一致
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { XLog.w(TAG, "startForeground failed: ${it.message}") }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, VoiceCallActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wakeword_notification_title))
            .setContentText(getString(R.string.wakeword_notification_text, WakeWordSettings.getKeyword()))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── 工具:权限 / 电源 / 屏幕 ──

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun isCharging(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // ACTION_BATTERY_CHANGED 可拿到充电状态,但需要注册接收器;用 BatteryManager 更直接
        val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive
        else @Suppress("DEPRECATION") pm.isScreenOn
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopEngine()
    }
}
