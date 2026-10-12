package com.apk.claw.android.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import com.apk.claw.android.ui.home.HomeActivity
import com.apk.claw.android.utils.XLog

/**
 * 高清屏幕采集前台服务 —— 持有 [MediaProjection] 会话与 [ScreenProjectionSource]。
 *
 * 授权令牌由 [MediaProjectionRequestActivity] 走系统同意框拿到后,以 extra 传进来。
 * 一旦本服务在跑(采集源非空),[com.apk.claw.android.server.ScreenCaptureManager] 就自动
 * 走投屏快路;停了就无感回退无障碍截图。
 *
 * Android 14+ 强约束:foregroundServiceType=mediaProjection,且必须**先** startForeground、
 * 再 getMediaProjection —— 顺序反了系统直接抛 SecurityException。
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 先进前台(带 mediaProjection 类型),满足 API34+ 顺序要求。
        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getProjectionData(it) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            XLog.w(TAG, "缺少投屏授权令牌,服务退出")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection? = runCatching { mpm.getMediaProjection(resultCode, data) }
            .onFailure { XLog.e(TAG, "getMediaProjection 失败: ${it.message}") }
            .getOrNull()
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val (w, h, dpi) = screenSize()
        val src = ScreenProjectionSource(projection, w, h, dpi) {
            // 系统/用户撤销投屏 → 收尾并停服务。
            source = null
            stopSelf()
        }
        runCatching { src.start() }.onFailure {
            XLog.e(TAG, "采集启动失败: ${it.message}")
            src.release()
            stopSelf()
            return START_NOT_STICKY
        }
        source = src
        XLog.i(TAG, "高清屏幕采集服务已就绪")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        source?.release()
        source = null
        XLog.i(TAG, "高清屏幕采集服务已停止")
    }

    private fun startAsForeground() {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dm.densityDpi)
        } else {
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW).apply {
            description = "高清远程画面采集运行中的常驻提示"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pending = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在采集屏幕画面")
            .setContentText("远程高清画面已开启,点此返回应用")
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentIntent(pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    /** 老 API 上 getParcelableExtra 无类型重载,统一在这里屏蔽 deprecation。 */
    private object IntentCompat {
        @Suppress("DEPRECATION")
        fun getProjectionData(intent: Intent): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_DATA)
            }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "octopus_screen_capture_channel"
        private const val NOTIFICATION_ID = 1077
        private const val ACTION_STOP = "com.apk.claw.android.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"

        /** 当前采集源;非空即表示投屏快路可用。ScreenCaptureManager 读它决定走哪条路。 */
        @Volatile
        var source: ScreenProjectionSource? = null
            private set

        fun isActive(): Boolean = source != null

        /** 由授权 Activity 拿到令牌后调用,启动前台服务。 */
        fun startWithToken(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 拉起系统投屏同意框(透明 Activity),用户同意后自动启服务。 */
        fun requestStart(context: Context) {
            val intent = Intent(context, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
