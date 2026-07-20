package com.apk.claw.android.octopus_mobile.uitree

import android.graphics.Bitmap
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.RemoteActions
import com.apk.claw.android.root.RootShellService
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.utils.XLog

/**
 * UI 动作路由器：统一所有读写通道的降级逻辑，与 [UiTreeCoordinator] 对仗。
 *
 * 读 UI 树降级已统一在 [UiTreeCoordinator]（A11y → Shizuku → Root）；
 * 写操作（tap/longPress/swipe/keyEvent/screenshot）此前散落在：
 *  - [ClawAccessibilityService.performTap] 等 3 个方法内硬编码 Shizuku→A11y 两级（无 Root）
 *  - [com.apk.claw.android.tool.impl.mobile.TapTool] 等 3 个工具内重复"远程分叉 + Service 调用"模板
 *  - [com.apk.claw.android.root.VirtualDisplayService] 误用 Root input 但白名单未开（死代码）
 *
 * 路由策略：
 * 1. 远程目标（[ControlTarget.remoteTarget] != null）→ [RemoteActions] 单通道，HTTP 失败即整体失败
 * 2. 本机目标 → 三级 fallback：
 *    a. Shizuku（shell UID 2000，同步、安全窗口有效）
 *    b. A11y dispatchGesture（主屏主路径，无 shell 依赖）
 *    c. Root input（root UID 0，最低优先级兜底，覆盖"Shizuku 未授权 + A11y 被系统杀"场景）
 *
 * 调用方（TapTool/LongPressTool/SwipeTool/ScrollToFindTool 等）零关心通道选择，
 * 只看 [tap]/[longPress]/[swipe] 的 Boolean 返回值。
 */
object UiActionRouter {
    private const val TAG = "UiActionRouter"

    // ── 目标路由 ──
    fun isRemote(): Boolean = ControlTarget.isRemote()
    fun remoteTarget(): DeviceInfo? = ControlTarget.remoteTarget()

    // ── 触控：tap ──
    /** 点击 (x,y)。返回 true=成功，false=所有通道都失败 */
    fun tap(x: Int, y: Int, durationMs: Long = 100): Boolean {
        // 远程
        remoteTarget()?.let { d ->
            val ok = runCatching { RemoteActions.tap(d, x, y) }.getOrDefault(false)
            if (!ok) XLog.w(TAG, "remote tap failed on ${d.deviceName}")
            return ok
        }
        // 本机：Shizuku → A11y → Root
        ShizukuShellService.tap(x, y)?.let { if (it) return true }
        ClawAccessibilityService.getInstance()?.let { svc ->
            if (svc.performTap(x, y, durationMs)) return true
        }
        RootShellService.tap(x, y)?.let { if (it) return true }
        return false
    }

    // ── 触控：longPress ──
    fun longPress(x: Int, y: Int, durationMs: Long): Boolean {
        remoteTarget()?.let { d ->
            val ok = runCatching { RemoteActions.longPress(d, x, y, durationMs) }.getOrDefault(false)
            if (!ok) XLog.w(TAG, "remote longPress failed on ${d.deviceName}")
            return ok
        }
        ShizukuShellService.longPress(x, y, durationMs)?.let { if (it) return true }
        ClawAccessibilityService.getInstance()?.let { svc ->
            if (svc.performLongPress(x, y, durationMs)) return true
        }
        RootShellService.longPress(x, y, durationMs)?.let { if (it) return true }
        return false
    }

    // ── 触控：swipe ──
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        remoteTarget()?.let { d ->
            val ok = runCatching { RemoteActions.swipe(d, startX, startY, endX, endY, durationMs) }
                .getOrDefault(false)
            if (!ok) XLog.w(TAG, "remote swipe failed on ${d.deviceName}")
            return ok
        }
        ShizukuShellService.swipe(startX, startY, endX, endY, durationMs)?.let { if (it) return true }
        ClawAccessibilityService.getInstance()?.let { svc ->
            if (svc.performSwipe(startX, startY, endX, endY, durationMs)) return true
        }
        RootShellService.swipe(startX, startY, endX, endY, durationMs)?.let { if (it) return true }
        return false
    }

    // ── 按键：keyEvent ──
    fun keyEvent(keyCode: Int): Boolean {
        remoteTarget()?.let { d ->
            val ok = runCatching { RemoteActions.key(d, keyCode) }.getOrDefault(false)
            if (!ok) XLog.w(TAG, "remote keyEvent failed on ${d.deviceName}")
            return ok
        }
        ClawAccessibilityService.getInstance()?.let { svc ->
            if (svc.sendKeyEvent(keyCode)) return true
        }
        RootShellService.keyEvent(keyCode)?.let { if (it) return true }
        return false
    }

    // ── 截图：screenshot ──
    fun screenshot(timeoutMs: Long = 5_000): Bitmap? {
        remoteTarget()?.let { d ->
            val bmp = runCatching { RemoteActions.screenshot(d) }.getOrNull()
            if (bmp == null) XLog.w(TAG, "remote screenshot failed on ${d.deviceName}")
            return bmp
        }
        ClawAccessibilityService.getInstance()?.let { svc ->
            return runCatching { svc.takeScreenshot(timeoutMs) }.getOrNull()
        }
        // Root 通道：screencap -p 输出 PNG（白名单已含 screencap）
        // 注：screencap 输出二进制到 stdout，RootShellService.exec 当前返回 String 会丢字节，
        // 暂不支持 Root 截图，需要时单独加 RootShellService.execRaw(bytes) 接口。
        return null
    }
}
