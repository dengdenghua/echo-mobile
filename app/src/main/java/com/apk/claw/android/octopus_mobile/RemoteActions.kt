package com.apk.claw.android.octopus_mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 远程设备动作封装 —— 把 [DeviceRemoteControl] 的 suspend 接口包成阻塞调用，
 * 供 Java 工具在 Agent 执行线程上直接调用（工具本身是同步的）。
 *
 * 优化：全部走 Dispatchers.IO + withTimeoutOrNull 硬超时保护，防止远端设备失联钉死调用线程。
 * 对端动作由其 ConfigServer 的 control/screen 接口执行。
 */
object RemoteActions {

    private val rc = DeviceRemoteControl()
    private const val ACTION_TIMEOUT_MS = 15_000L

    @JvmStatic
    fun tap(d: DeviceInfo, x: Int, y: Int): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.tap(d, x, y) } ?: false
    }

    @JvmStatic
    fun swipe(d: DeviceInfo, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.swipe(d, x1, y1, x2, y2, durationMs) } ?: false
        }

    @JvmStatic
    fun longPress(d: DeviceInfo, x: Int, y: Int, durationMs: Long): Boolean =
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.longPress(d, x, y, durationMs) } ?: false
        }

    @JvmStatic
    fun key(d: DeviceInfo, keyCode: Int): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.sendKey(d, keyCode) } ?: false
    }

    @JvmStatic
    fun text(d: DeviceInfo, t: String): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.sendText(d, t) } ?: false
    }

    @JvmStatic
    fun back(d: DeviceInfo): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.pressBack(d) } ?: false
    }

    @JvmStatic
    fun home(d: DeviceInfo): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.pressHome(d) } ?: false
    }

    @JvmStatic
    fun recents(d: DeviceInfo): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.pressRecents(d) } ?: false
    }

    @JvmStatic
    fun openApp(d: DeviceInfo, pkg: String): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.openApp(d, pkg) } ?: false
    }

    @JvmStatic
    fun screenshot(d: DeviceInfo): Bitmap? = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) {
            rc.captureScreenshot(d)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }

    @JvmStatic
    fun screenTree(d: DeviceInfo, full: Boolean): String? = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(ACTION_TIMEOUT_MS) { rc.getScreenTree(d, full) }
    }
}
