package com.apk.claw.android.root

import android.util.Log
import com.apk.claw.android.utils.XLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root Shell 服务 —— 通过 `su` 执行 root 权限命令。
 *
 * 与 [com.apk.claw.android.shizuku.ShizukuShellService] 的区别:
 *  - ShizukuShell: shell UID 2000,需 Shizuku 授权,无 root
 *  - RootShell: root UID 0,需设备已 root(Magisk/KernelSU),能力最强
 *
 * Root 权限增益:
 *  - 创建虚拟 Display(SurfaceFlinger 层操作,需要 CAP_SURFACEFLINGER)
 *  - 读写任意 App data(跨 App 数据访问,需要 CAP_DAC_OVERRIDE)
 *  - 修改系统属性(setprop persist.*)
 *  - iptables 网络规则(容器网络隔离)
 *  - mount/umount 文件系统
 *  - 启停系统服务
 *  - uiautomator dump（A11y/Shizuku 不可用时作为最低 fallback 通道）
 *
 * 安全模型:
 *  - 命令前缀白名单 + shell 元字符注入检测(与 ShizukuShell 一致)
 *  - 仅允许 [ALLOWED_COMMAND_PREFIXES] 中的命令
 *  - 登记为最高危,不可信来源走来源闸门 + 全程审计
 *  - Root 不可用时所有方法返回 null/false,上层应 fallback 到 Shizuku
 *  - 所有 exec 调用带 [TIMEOUT_MS] 超时，防止 uiautomator dump 等命令卡死整个调用链
 */
object RootShellService {

    private const val TAG = "RootShell"
    private const val TIMEOUT_MS = 15_000L

    /**
     * Root 命令白名单前缀。
     *
     * 命令分类：
     * 1. 系统查询/控制：dumpsys/service/settings/getprop/setprop/wm/am/cmd
     * 2. 截图：screencap
     * 3. UI 树 fallback（[RootTreeProvider] 用）：uiautomator dump + cat/rm 限定 /sdcard/octopus_ui_dump_ 前缀
     * 4. 触控写入（[UiActionRouter] Root 通道 + [VirtualDisplayService] 虚拟屏）：input tap/swipe/keyevent
     *
     * 显式排除通用 rm/dd/ifconfig/iptables/cat 任意路径（仅允许 cat/rm 我们的 dump 临时文件）。
     */
    private val ALLOWED_COMMAND_PREFIXES = listOf(
        // 系统查询/控制
        "dumpsys SurfaceFlinger",
        "dumpsys display",
        "service call SurfaceFlinger",
        "service call display",
        "settings get",
        "settings put",
        "getprop",
        "setprop persist.",
        "wm size",
        "wm density",
        "am start",
        "am force-stop",
        "cmd display",
        // 截图
        "screencap",
        // UI 树 fallback —— 仅允许 dump 到 /sdcard 下，仅允许读写 octopus_ui_dump_ 前缀的临时文件
        "uiautomator dump",
        "cat /sdcard/octopus_ui_dump_",
        "rm -f /sdcard/octopus_ui_dump_",
        // 触控写入 —— Root 作为最低优先级 fallback（Shizuku→A11y→Root）
        // VirtualDisplayService 也用 input tap/swipe -d <displayId> 注入虚拟屏
        "input tap",
        "input swipe",
        "input keyevent",
    )

    /** Shell 元字符黑名单(防注入)。 */
    private val SHELL_METACHARS = setOf(
        ';', '|', '&', '$', '(', ')', '`', '\\', '"', '\n', '\r',
        '<', '>', '*'
    )

    /** Root 可用性检测结果(缓存)。 */
    @Volatile
    private var rootChecked = false
    @Volatile
    private var rootAvailable = false

    /** 检测设备是否已 root(通过 `su -c id` 是否返回 uid=0)。 */
    @Suppress("TooGenericExceptionCaught")
    fun isAvailable(): Boolean {
        if (rootChecked) return rootAvailable
        rootChecked = true
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val exitCode = proc.waitFor()
            rootAvailable = exitCode == 0 && output.contains("uid=0")
            Log.i(TAG, "Root check: available=$rootAvailable (exit=$exitCode, out=${output.trim()})")
            rootAvailable
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed: ${e.message}")
            rootAvailable = false
            false
        }
    }

    /**
     * 执行 root 命令(白名单内)，带 [TIMEOUT_MS] 超时。
     * @param command 完整命令字符串
     * @return 命令输出(stdout+stderr),失败或超时返回 null
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun exec(command: String): ExecResult? {
        if (!isAvailable()) return null
        if (!isCommandAllowed(command)) return null

        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            // 双线程读 stdout/stderr 防止管道阻塞（uiautomator dump 可能输出较大 XML）
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val stdoutThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                        r.forEachLine { stdoutBuf.append(it).append('\n') }
                    }
                }
            }
            val stderrThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                        r.forEachLine { stderrBuf.append(it).append('\n') }
                    }
                }
            }
            stdoutThread.start()
            stderrThread.start()
            val finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                XLog.w(TAG, "exec timeout after ${TIMEOUT_MS}ms: $command")
                return null
            }
            stdoutThread.join(2_000)
            stderrThread.join(2_000)
            ExecResult(proc.exitValue(), stdoutBuf.toString(), stderrBuf.toString())
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            null
        }
    }

    /** 命令是否在白名单内 + 无注入字符。 */
    private fun isCommandAllowed(command: String): Boolean {
        val normalized = command.trimStart()
        // 前缀白名单匹配规则：
        // - 前缀末尾是 ' '（命令词，如 "dumpsys SurfaceFlinger"）→ 下个字符任意（已分隔）
        // - 前缀末尾是 '.'（属性名分隔，如 "setprop persist."）→ 下个字符任意（已分隔）
        // - 前缀末尾是 '/'（路径分隔，如 "cat /sdcard/octopus_ui_dump_/"）→ 下个字符任意（已分隔）
        // - 前缀末尾是 '_'（文件名前缀，如 "cat /sdcard/octopus_ui_dump_"）→ 下个字符必须是安全文件名字符
        // - 其他（前缀末尾是字母/数字，如 "uiautomator dump"）→ 下个字符必须是空格
        //   防 "uiautomator dumpXYZ" 骗过 "uiautomator dump"
        val prefixMatch = ALLOWED_COMMAND_PREFIXES.any { prefix ->
            if (!normalized.startsWith(prefix)) return@any false
            if (normalized == prefix) return@any true
            if (normalized.length <= prefix.length) return@any false
            val nextChar = normalized[prefix.length]
            when (prefix.last()) {
                ' ', '.', '/' -> true
                '_' -> isSafeFilenameChar(nextChar)
                else -> nextChar == ' '
            }
        }
        if (!prefixMatch) {
            Log.w(TAG, "Command not in whitelist: $command")
            return false
        }
        // 元字符检测(防注入)
        if (command.any { it in SHELL_METACHARS }) {
            Log.w(TAG, "Command contains metacharacters: $command")
            return false
        }
        return true
    }

    private fun isSafeFilenameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    // ── 触控写入便捷方法（镜像 ShizukuShellService 签名，供 UiActionRouter 调用） ──
    // 返回 Boolean?：true=成功，false=命令失败 exitCode≠0，null=Root 不可用/命令被拦
    // 坐标参数由调用方（UiActionRouter）做合法性校验，这里直接拼接（白名单 + 元字符黑名单已防注入）

    /** Root 触控：input tap x y（可选 -d <displayId> 注入虚拟屏） */
    fun tap(x: Int, y: Int, displayId: Int = -1): Boolean? {
        val cmd = if (displayId >= 0) "input tap $x $y -d $displayId" else "input tap $x $y"
        return exec(cmd)?.exitCode == 0
    }

    /** Root 触控：input swipe x1 y1 x2 y2 durationMs（可选 -d <displayId>） */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300, displayId: Int = -1): Boolean? {
        val cmd = if (displayId >= 0) "input swipe $x1 $y1 $x2 $y2 $durationMs -d $displayId"
        else "input swipe $x1 $y1 $x2 $y2 $durationMs"
        return exec(cmd)?.exitCode == 0
    }

    /** Root 长按：input swipe x y x y durationMs（同点 swipe 模拟长按，与 Shizuku 一致） */
    fun longPress(x: Int, y: Int, durationMs: Long = 1000, displayId: Int = -1): Boolean? =
        swipe(x, y, x, y, durationMs, displayId)

    /** Root 按键：input keyevent <keyCode> */
    fun keyEvent(keyCode: Int): Boolean? = exec("input keyevent $keyCode")?.exitCode == 0
}
