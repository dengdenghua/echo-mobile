package com.apk.claw.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.apk.claw.android.R
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.shizuku.UnprivilegedShellFallback
import com.apk.claw.android.ui.home.HomeActivity
import com.apk.claw.android.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Core accessibility service that provides all device interaction capabilities.
 * Singleton-pattern: the running instance is accessible via [getInstance].
 */
class ClawAccessibilityService : AccessibilityService() {

    // ======================== Singleton ========================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 记录「用户确实开过本 App 的无障碍」—— 日后被系统杀掉时,A11ySelfHeal 才会用 Shizuku 写回启用。
        // 只在成功连接过之后才自愈,绝不主动开启用户从没开过的服务。
        runCatching { A11ySelfHeal.markWanted() }
        // 请求过滤按键事件（导航录制器需要）
        serviceInfo?.let { info ->
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }
        // 关键保活：让 AccessibilityService 自身作为前台服务运行
        // AccessibilityService 由系统直接绑定，在此处启动前台服务是最可靠的时机
        // （不会触发 Android 12+ 后台启动限制，且生命周期与系统绑定更紧密）
        startAsForeground()
        // 双保险：同时确保独立的 ForegroundService 也启动
        runCatching { ForegroundService.start(this) }
        // 调度定期守护Job
        runCatching { KeepAliveJobService.schedule(this) }
        XLog.i(TAG, "Accessibility service connected, foreground started")
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        // 仅处理 ACTION_DOWN，避免重复
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        // 转发给导航录制器（被动学习 + 主动录制）
        runCatching {
            com.apk.claw.android.tool.impl.NavigateTool.recorder.onKeyEvent(event.keyCode)
        }
        return false // 不消费按键
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Forward to ScreenStreamer (Phase F — 屏幕状态增量上报)
        runCatching {
            com.apk.claw.android.octopus_mobile.ScreenStreamer.dispatchEvent(event)
        }
        // 示范录制态：把用户的点按/输入录成例程步骤（非录制态零开销）
        runCatching {
            com.apk.claw.android.octopus_mobile.DemoRecorder.ingest(event)
        }
    }

    override fun onInterrupt() {
        XLog.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        XLog.i(TAG, "Accessibility service destroyed")
        runCatching {
            val restartIntent = Intent(this, ForegroundService::class.java)
            val pendingRestart = PendingIntent.getService(
                this, RESTART_REQUEST_CODE, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, pendingRestart)
        }
    }

    // ======================== Foreground Service KeepAlive ========================

    private fun startAsForeground() {
        runCatching {
            createNotificationChannel()
            val notification = createKeepAliveNotification()
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(A11Y_NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(A11Y_NOTIFICATION_ID, notification)
            }
        }.onFailure {
            XLog.e(TAG, "startAsForeground failed", it)
        }
    }

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

    private fun createKeepAliveNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(getString(R.string.notification_content_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    // ======================== Gesture Operations ========================

    /** Performs a tap at the given screen coordinates. */
    @JvmOverloads
    fun performTap(x: Int, y: Int, durationMs: Long = 100): Boolean {
        // Shizuku 增强：shell 级触控注入（同步、安全窗口有效）
        ShizukuShellService.tap(x, y)?.let { result ->
            XLog.d(TAG, "Shizuku tap ($x,$y): $result")
            return result
        }
        // Fallback: AccessibilityService dispatchGesture
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    /** Performs a long press at the given screen coordinates. */
    fun performLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        // Shizuku 增强：shell 级长按（同步、安全窗口有效）
        ShizukuShellService.longPress(x, y, durationMs)?.let { result ->
            XLog.d(TAG, "Shizuku longPress ($x,$y,${durationMs}ms): $result")
            return result
        }
        // Fallback: AccessibilityService dispatchGesture
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    /** Performs a swipe gesture from (startX, startY) to (endX, endY). */
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        // Shizuku 增强：shell 级滑动（同步、安全窗口有效）
        ShizukuShellService.swipe(startX, startY, endX, endY, durationMs)?.let { result ->
            XLog.d(TAG, "Shizuku swipe: $result")
            return result
        }
        // Fallback: AccessibilityService dispatchGesture
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    /** Dispatches a gesture and waits for it to complete synchronously. */
    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                result.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                result.set(false)
                latch.countDown()
            }
        }, null)

        if (!dispatched) return false

        val deadline = System.currentTimeMillis() + 5000
        return try {
            while (System.currentTimeMillis() < deadline) {
                if (Thread.currentThread().isInterrupted) return false
                if (latch.await(200, TimeUnit.MILLISECONDS)) return result.get()
            }
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // ======================== Node Operations ========================

    /** Finds all nodes matching the given text. */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            root.findAccessibilityNodeInfosByText(text) ?: emptyList()
        } finally {
            root.recycle()
        }
    }

    /** Finds all nodes matching the given view ID (e.g. "com.example:id/button"). */
    fun findNodesById(viewId: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
        } finally {
            root.recycle()
        }
    }

    /** Clicks on a node. */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        var result = false
        var found = false
        while (parent != null) {
            if (parent.isClickable) {
                result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                found = true
                break
            }
            val next = parent.parent
            parent.recycle()
            parent = next
        }
        if (found) return result
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return performTap(bounds.centerX(), bounds.centerY())
    }

    /** Sets text on a node (for EditText fields). */
    fun setNodeText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Collects a tree representation of the current screen for AI analysis.
     * Exposed as a property so Kotlin callers use `service.screenTree` and Java
     * callers use `getScreenTree()` (matches the previous Java implementation).
     */
    val screenTree: String?
        get() {
            val root = rootInActiveWindow ?: return null
            return try {
                buildString { buildNodeTree(root, this, 0) }
            } finally {
                root.recycle()
            }
        }

    /**
     * Collects a FULL tree representation of the current screen (debug only).
     * Includes ALL nodes with all properties, no filtering.
     */
    val screenTreeFull: String?
        get() {
            val root = rootInActiveWindow ?: return null
            return try {
                buildString { buildNodeTreeFull(root, this, 0) }
            } finally {
                root.recycle()
            }
        }

    private fun buildNodeTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        // 跳过不在屏幕可见区域内的节点（滚动容器中超出屏幕的元素）
        if (!node.isVisibleToUser) {
            // 仍然遍历子节点，因为父节点不可见不代表所有子节点都不可见
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    buildNodeTree(child, sb, depth)
                    child.recycle()
                }
            }
            return
        }

        // 判断当前节点是否有"信息量"（有 text/desc/可交互/可滚动/可编辑/进度条/滑块）
        val hasText = !node.text.isNullOrEmpty()
        val hasDesc = !node.contentDescription.isNullOrEmpty()
        val isInteractive = node.isClickable || node.isScrollable || node.isEditable
                || node.isCheckable || node.isLongClickable
        val isSlider = node.isSliderNode
        val isProgress = node.className?.toString()?.contains("ProgressBar") == true
        val isMeaningful = hasText || hasDesc || isInteractive || isSlider || isProgress

        if (isMeaningful) {
            val indent = "  ".repeat(depth)
            sb.append(indent)

            // 简化 className：只保留最后一段（如 android.widget.TextView → TextView）
            node.className?.toString()?.let { cls ->
                val dotIdx = cls.lastIndexOf('.')
                sb.append("[").append(if (dotIdx >= 0) cls.substring(dotIdx + 1) else cls).append("]")
            }

            if (hasText) {
                val text = node.text
                if (text != null) {
                    if (text.length > 100) {
                        sb.append(" text=\"").append(text.subSequence(0, 100)).append("...\"")
                    } else {
                        sb.append(" text=\"").append(text).append("\"")
                    }
                }
            }
            if (hasDesc) {
                sb.append(" desc=\"").append(node.contentDescription).append("\"")
            }
            if (node.isClickable) sb.append(" [clickable]")
            if (node.isLongClickable) sb.append(" [long-clickable]")
            if (node.isScrollable) sb.append(" [scrollable]")
            if (node.isEditable) sb.append(" [editable]")
            if (node.isCheckable) {
                sb.append(if (node.isChecked) " [checked]" else " [unchecked]")
            }
            if (!node.isEnabled) sb.append(" [disabled]")
            if (node.isFocused) sb.append(" [focused]")
            if (isProgress) sb.append(" [loading]")

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            sb.append(" bounds=").append(bounds.toShortString())
            sb.append("\n")
        }

        // 子节点层级：如果当前节点被跳过（非 meaningful），子节点保持同层级，不增加 depth
        val childDepth = if (isMeaningful) depth + 1 else depth
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                buildNodeTree(child, sb, childDepth)
                child.recycle()
            }
        }
    }

    /** Full node tree builder - outputs ALL nodes with ALL properties, no filtering. */
    private fun buildNodeTreeFull(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        sb.append(indent)

        // className
        node.className?.toString()?.let { cls ->
            val dotIdx = cls.lastIndexOf('.')
            sb.append("[").append(if (dotIdx >= 0) cls.substring(dotIdx + 1) else cls).append("]")
        }

        // text
        if (!node.text.isNullOrEmpty()) {
            val text = node.text
            if (text != null) {
                if (text.length > 200) {
                    sb.append(" text=\"").append(text.subSequence(0, 200)).append("...\"")
                } else {
                    sb.append(" text=\"").append(text).append("\"")
                }
            }
        }

        // contentDescription
        if (!node.contentDescription.isNullOrEmpty()) {
            sb.append(" desc=\"").append(node.contentDescription).append("\"")
        }

        // resource-id
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let {
            sb.append(" id=\"$it\"")
        }

        // package
        node.packageName?.let { sb.append(" pkg=\"$it\"") }

        // interaction states
        if (node.isClickable) sb.append(" [clickable]")
        if (node.isLongClickable) sb.append(" [long-clickable]")
        if (node.isScrollable) sb.append(" [scrollable]")
        if (node.isEditable) sb.append(" [editable]")
        if (node.isCheckable) sb.append(if (node.isChecked) " [checked]" else " [unchecked]")
        if (!node.isEnabled) sb.append(" [disabled]")
        if (node.isFocused) sb.append(" [focused]")
        if (node.isSelected) sb.append(" [selected]")
        if (!node.isVisibleToUser) sb.append(" [invisible]")

        // slider range info
        if (node.isSliderNode) {
            sb.append(" [slider]")
            node.rangeInfo?.let { range ->
                sb.append(" range=[${range.min.toInt()}-${range.max.toInt()}, current=${range.current.toInt()}]")
            }
        }

        // progress bar
        if (node.className?.toString()?.contains("ProgressBar") == true) {
            sb.append(" [loading]")
        }

        // bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        sb.append(" bounds=").append(bounds.toShortString())
        sb.append("\n")

        // recurse all children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                buildNodeTreeFull(child, sb, depth + 1)
                child.recycle()
            }
        }
    }

    /** Finds a specific node and returns detailed info as a string. */
    fun getNodeDetail(node: AccessibilityNodeInfo?): String {
        if (node == null) return "null"
        return buildString {
            append("class=").append(node.className)
            node.text?.let { append(", text=\"$it\"") }
            node.contentDescription?.let { append(", desc=\"$it\"") }
            append(", clickable=").append(node.isClickable)
            append(", enabled=").append(node.isEnabled)
            append(", visible=").append(node.isVisibleToUser)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            append(", bounds=").append(bounds.toShortString())
        }
    }

    // ======================== Slider Detection ========================

    private val AccessibilityNodeInfo.isSliderNode: Boolean
        get() {
            val cls = className?.toString() ?: return false
            return cls.contains("SeekBar")
                    || cls.contains("Slider")
                    || cls.contains("RatingBar")
                    || rangeInfo != null
        }

    // ======================== Global Actions ========================

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecentApps(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun expandNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun collapseNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    /**
     * Attempts to unlock the screen: wake up + swipe up.
     * Works for no-password / swipe lock screens.
     */
    fun unlockScreen(): Boolean {
        return try {
            // 1. 唤醒屏幕
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "OctopusMobile:unlock"
                )
                wl.acquire(3000)
                wl.release()
                // 等屏幕亮起
                @Suppress("BlockingMethodInNonBlockingContext")
                Thread.sleep(500)
            }

            // 2. 模拟上滑手势解锁
            val dm: DisplayMetrics = resources.displayMetrics
            val centerX = dm.widthPixels / 2
            val bottomY = (dm.heightPixels * 0.8).toInt()
            val topY = (dm.heightPixels * 0.2).toInt()
            performSwipe(centerX, bottomY, centerX, topY, 300)
        } catch (e: Exception) {
            XLog.e(TAG, "unlockScreen failed", e)
            false
        }
    }

    // ======================== Screenshot ========================

    /**
     * Takes a screenshot (requires API 30+).
     * Returns the bitmap or null on failure.
     */
    fun takeScreenshot(timeoutMs: Long): Bitmap? {
        // Shizuku 增强：shell 级截屏（全版本有效、安全窗口可见）
        ShizukuShellService.screenshot()?.let { bmp ->
            XLog.d(TAG, "Shizuku screenshot success")
            return bmp
        }
        // Fallback: AccessibilityService.takeScreenshot (API 30+ only)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            XLog.w(TAG, "Screenshot requires API 30+ and Shizuku is not available")
            return null
        }
        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap?>(null)
        val resolved = AtomicBoolean(false)

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    if (!resolved.compareAndSet(false, true)) {
                        // 已超时或已处理：方法已返回 null，此时设置的 bitmapRef
                        // 永远不会被读取，直接回收资源避免泄漏
                        bmp?.recycle()
                        result.hardwareBuffer.close()
                        return
                    }
                    bitmapRef.set(bmp)
                    result.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    XLog.e(TAG, "Screenshot failed with error code: $errorCode")
                    resolved.compareAndSet(false, true)
                    latch.countDown()
                }
            })

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                // 超时：标记已超时，后续到达的 onSuccess 会进入回收分支
                if (resolved.compareAndSet(false, true)) {
                    return null
                }
                // 回调已赢得 CAS 但可能尚未设置 bitmapRef，返回当前值（可能为 null）
                return bitmapRef.get()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (resolved.compareAndSet(false, true)) {
                return null
            }
            return bitmapRef.get()
        }
        return bitmapRef.get()
    }

    // ======================== Key Event Injection (TV Remote) ========================

    /**
     * Sends a key event via shell command. Works reliably on Android TV boxes.
     */
    fun sendKeyEvent(keyCode: Int): Boolean {
        // Shizuku 增强：shell 级按键注入（修复手机上被 SELinux 拒绝的问题）
        ShizukuShellService.keyEvent(keyCode)?.let { result ->
            XLog.d(TAG, "Shizuku keyEvent $keyCode: $result")
            return result
        }
        // 显式降级：Shizuku 不可用 → 以 App UID 执行 `input keyevent`（无 shell 权限；手机上通常被拒，TV 盒子可用）。
        // 不再只看退出码：app UID 下 `input` 常以 0 退出但打印 SecurityException，这种情况按失败处理。
        XLog.w(TAG, "Shizuku unavailable, injecting key $keyCode WITHOUT shell privileges (app UID)")
        return try {
            val process = ProcessBuilder("input", "keyevent", keyCode.toString())
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(KEY_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                XLog.e(TAG, "Unprivileged key event $keyCode timed out")
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.exitValue()
                val ok = UnprivilegedShellFallback.appUidCommandSucceeded(exit, output)
                if (!ok) {
                    XLog.e(TAG, "Unprivileged key event $keyCode failed (exit=$exit): ${output.take(200)}")
                }
                ok
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send key event: $keyCode", e)
            false
        }
    }

    // ======================== App Launch ========================

    /** Opens an app by its package name. */
    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                XLog.e(TAG, "Cannot resolve launch intent for $packageName")
                return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to open app: $packageName", e)
            false
        }
    }

    companion object {
        private const val TAG = "ClawA11yService"
        private const val CHANNEL_ID = "octopus_mobile_foreground_channel"
        private const val A11Y_NOTIFICATION_ID = 1002
        private const val RESTART_DELAY_MS = 1000L
        private const val RESTART_REQUEST_CODE = 2
        private const val KEY_EVENT_TIMEOUT_MS = 5_000L

        @Volatile
        private var instance: ClawAccessibilityService? = null

        @JvmStatic
        fun getInstance(): ClawAccessibilityService? = instance

        @JvmStatic
        fun isRunning(): Boolean {
            // 1. 快速路径：静态 instance 非空表示服务已连接
            if (instance != null) return true
            // 2. 回退路径：instance 为 null 时（服务被系统重启的窗口期），
            //    检查系统是否仍把本服务列为已启用，避免 UI 误报"已关闭"
            return isServiceEnabledInSystem()
        }

        /**
         * 是否「实际连接中」(进程内 instance 存活,自托管前台服务在岗)。
         * 保活决策(前台服务降级/重启闹钟/自愈判断)必须用这个而非 [isRunning]:
         * 后者带 enabled 列表回退,在「系统仍列为启用但服务已死」的窗口(如崩溃惩罚期)
         * 会误判成在跑,导致既不拉前台服务也不排重启,保活静默丢失。UI 展示仍用 [isRunning]。
         */
        @JvmStatic
        fun isConnected(): Boolean = instance != null

        /**
         * 通过 AccessibilityManager 检查本服务是否仍处于系统已启用列表。
         * 用于覆盖 onServiceConnected/onDestroy 之间的重启窗口期。
         */
        private fun isServiceEnabledInSystem(): Boolean = runCatching {
            val app = com.apk.claw.android.ClawApplication.instance
            val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val targetPkg = app.packageName
            val targetCls = "com.apk.claw.android.service.ClawAccessibilityService"
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si?.packageName == targetPkg && si.name == targetCls
                }
        }.getOrDefault(false)

        /** Recycles a list of AccessibilityNodeInfo nodes (static convenience). */
        @JvmStatic
        fun recycleNodes(nodes: List<AccessibilityNodeInfo>?) {
            nodes ?: return
            for (node in nodes) {
                runCatching { node.recycle() }
            }
        }
    }
}
