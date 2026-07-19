package com.apk.claw.android.root

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

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
 *
 * 安全模型:
 *  - 命令前缀白名单 + shell 元字符注入检测(与 ShizukuShell 一致)
 *  - 仅允许 [ALLOWED_COMMAND_PREFIXES] 中的命令
 *  - 登记为最高危,不可信来源走来源闸门 + 全程审计
 *  - Root 不可用时所有方法返回 null/false,上层应 fallback 到 Shizuku
 */
object RootShellService {

    private const val TAG = "RootShell"
    private const val TIMEOUT_MS = 15_000L

    /**
     * Root 命令白名单前缀。
     * 仅允许虚拟显示/截图/属性/服务管理相关命令,显式排除 rm/dd/ifconfig/iptables 等。
     */
    private val ALLOWED_COMMAND_PREFIXES = listOf(
        "dumpsys SurfaceFlinger",
        "dumpsys display",
        "service call SurfaceFlinger",
        "service call display",
        "settings get",
        "settings put",
        "getprop",
        "setprop persist.",
        "screencap",
        "wm size",
        "wm density",
        "am start",
        "am force-stop",
        "cmd display",
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
     * 执行 root 命令(白名单内)。
     * @param command 完整命令字符串
     * @return 命令输出(stdout+stderr 合并),失败返回 null
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun exec(command: String): ExecResult? {
        if (!isAvailable()) return null
        if (!isCommandAllowed(command)) return null

        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).readText()
            val exitCode = proc.waitFor()
            ExecResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            null
        }
    }

    /** 命令是否在白名单内 + 无注入字符。 */
    private fun isCommandAllowed(command: String): Boolean {
        val normalized = command.trimStart()
        // 前缀白名单匹配:每个前缀自带分隔符(空格或点),防 "getpropxxx" 骗过 "getprop"
        // - "dumpsys " → 必须后跟空格(独立命令词)
        // - "setprop persist." → 必须后跟点分隔的属性名
        val prefixMatch = ALLOWED_COMMAND_PREFIXES.any { prefix ->
            normalized.startsWith(prefix) &&
                (normalized == prefix ||
                    normalized.length > prefix.length &&
                    (prefix.last() == ' ' || prefix.last() == '.' ||
                        normalized[prefix.length] == ' '))
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

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)
}
