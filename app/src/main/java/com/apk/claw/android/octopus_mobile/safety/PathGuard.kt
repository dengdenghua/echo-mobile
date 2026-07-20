package com.apk.claw.android.octopus_mobile.safety

import android.util.Log
import java.io.File

/**
 * 路径安全守卫 —— 从母体 runtime/safety/immunity/path_guard.py 移植.
 *
 * 防止 agent 读写敏感文件：
 *  - SSH 密钥（~/.ssh）
 *  - 云凭证（~/.aws, ~/.gcp, ~/.kube）
 *  - 系统文件（/etc/passwd, /etc/shadow）
 *  - Docker 配置（~/.docker）
 *  - GPG 密钥（~/.gnupg）
 *  - 沙箱逃逸（路径跳出指定目录）
 *
 * 用法：
 * ```kotlin
 * val verdict = PathGuard.check("/data/data/com.app/files/config.json")
 * if (!verdict.allow) { /* 阻止访问 */ }
 *
 * val verdict2 = PathGuard.check("/etc/passwd")
 * // verdict2.allow = false, reason = "sensitive_abs_path: /etc/passwd"
 * ```
 */
object PathGuard {

    private const val TAG = "PathGuard"

    // ── 敏感路径 ──────────────────────────────────────

    private val SENSITIVE_HOME_SUFFIXES = listOf(
        ".ssh",
        ".aws",
        ".gcp",
        ".kube",
        ".docker",
        ".gnupg",
        ".config/gh",
        ".netrc",
        ".pgpass",
    )

    private val SENSITIVE_ABS_PREFIXES = listOf(
        "/etc/passwd",
        "/etc/shadow",
        "/etc/sudoers",
        "/root",
        "/proc/self/environ",
        "/sys/class/",
        "/var/log/auth",
    )

    private val DOS_DEVICE_NAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    // ── 结果 ──────────────────────────────────────────

    data class PathVerdict(
        val allow: Boolean,
        val path: String,
        val resolved: String? = null,
        val reason: String = "",
    )

    // ── 检查 ──────────────────────────────────────────

    fun check(
        path: String,
        sandboxDir: String? = null,
        allowSensitive: Boolean = false,
        mustExist: Boolean = false,
    ): PathVerdict {
        if (path.isBlank()) {
            return PathVerdict(false, path, reason = "empty_path")
        }

        // DOS 设备名检查（Windows）
        if (hasDosDevice(path)) {
            return PathVerdict(false, path, reason = "dos_device_name")
        }

        // 解析路径
        val resolved = try {
            if (sandboxDir != null && !File(path).isAbsolute) {
                File(sandboxDir).resolve(path).canonicalPath
            } else {
                File(path).canonicalPath
            }
        } catch (e: Exception) {
            return PathVerdict(false, path, reason = "resolve_error: ${e.message}")
        }

        // 沙箱逃逸检查
        if (sandboxDir != null) {
            val base = try {
                File(sandboxDir).canonicalPath
            } catch (e: Exception) {
                sandboxDir
            }
            // 边界匹配：必须等于沙箱根或位于其下（带分隔符），避免同前缀兄弟目录
            // （如 /sdcard 与 /sdcard_evil）绕过沙箱。
            if (resolved != base && !resolved.startsWith(base + File.separator)) {
                return PathVerdict(
                    false, path, resolved = resolved,
                    reason = "escapes_sandbox: not under $base",
                )
            }
        }

        // 敏感路径检查
        if (!allowSensitive) {
            val sensitiveReason = checkSensitive(resolved, path)
            if (sensitiveReason != null) {
                return PathVerdict(
                    false, path, resolved = resolved,
                    reason = sensitiveReason,
                )
            }
        }

        // 存在性检查
        if (mustExist && !File(resolved).exists()) {
            return PathVerdict(
                false, path, resolved = resolved,
                reason = "not_found",
            )
        }

        return PathVerdict(true, path, resolved = resolved)
    }

    fun isSafePath(
        path: String,
        sandboxDir: String? = null,
        allowSensitive: Boolean = false,
    ): Boolean {
        return check(path, sandboxDir = sandboxDir, allowSensitive = allowSensitive).allow
    }

    /** 共享外部存储根 —— agent 文件工具的沙箱边界。 */
    const val SDCARD_SANDBOX = "/sdcard"

    /**
     * 远程工作空间缓存根 —— 由 [com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceCache.init] 设置。
     *
     * 通常为 `context.cacheDir/remote_workspace/`。设置后 [underSdcard] 也会放行此目录下的路径,
     * 让 file_ops/browse_files 等工具能透明操作远程文件的本地缓存副本。
     * 为 null 时(未初始化)不放宽任何检查。
     */
    @Volatile
    var remoteCacheRoot: String? = null

    /**
     * 便捷方法：校验 agent 提供的路径是否安全地位于 /sdcard 沙箱内。
     * 用于 file_ops / browse_files / search_files / backup_app 等工具，
     * 防止越界访问 /system、/proc、其他 App 的 /data/data 私有目录。
     */
    fun underSdcard(path: String): PathVerdict {
        // /sdcard 沙箱是无条件安全边界 —— 即使 FULL_POWER 模式也不解除。
        // 防止越界访问 /system、/proc、其他 App 的 /data/data 私有目录。
        val verdict = check(path, sandboxDir = SDCARD_SANDBOX)
        if (verdict.allow) return verdict
        // 回退:远程工作空间缓存目录(若已初始化)
        val cacheRoot = remoteCacheRoot ?: return verdict
        return check(path, sandboxDir = cacheRoot)
    }

    // ── 内部 ──────────────────────────────────────────

    private fun hasDosDevice(pathStr: String): Boolean {
        val normalized = pathStr.replace("\\", "/").lowercase()
        for (part in normalized.split("/")) {
            if (part.isBlank()) continue
            val stem = part.substringBefore(".")  // con.txt → con
            if (stem in DOS_DEVICE_NAMES) return true
        }
        return false
    }

    private fun checkSensitive(resolved: String, raw: String = resolved): String? {
        val normalized = resolved.replace("\\", "/").lowercase()
        val normalizedRaw = raw.replace("\\", "/").lowercase()

        // 绝对路径前缀检查（同时比对原始路径与规范化路径）。
        // canonicalPath 会把 /etc 解析成 /private/etc(macOS)或 /system/etc
        // (Android 部分系统)，只查 resolved 会漏掉 /etc/passwd 等敏感前缀。
        for (prefix in SENSITIVE_ABS_PREFIXES) {
            val p = prefix.lowercase()
            if (normalized.startsWith(p) || normalizedRaw.startsWith(p)) {
                return "sensitive_abs_path: $prefix"
            }
        }

        // 用户目录下的敏感路径
        val home = System.getProperty("user.home") ?: return null
        val homeNormalized = home.replace("\\", "/").lowercase()
        if (normalized.startsWith(homeNormalized)) {
            val relPath = normalized.removePrefix(homeNormalized).removePrefix("/")
            for (suffix in SENSITIVE_HOME_SUFFIXES) {
                val suffixLc = suffix.lowercase()
                if (relPath == suffixLc || relPath.startsWith("$suffixLc/")) {
                    return "sensitive_home_path: ~/$suffix"
                }
            }
        }

        // Android 特有敏感路径:系统配置与凭据目录,文件工具不应触碰。
        // 注:不做 "/data/data/" 全量拦截 —— 无 Context 无法在此放行本 App 自身的
        // /data/data/<pkg>,盲目拦截会误伤对自身私有目录的正常访问;跨 App 目录读取的
        // 收口交由上层来源闸门 + 权限门。这里只拦明确的系统/凭据目录。
        val androidSensitive = listOf(
            "/data/system/",
            "/data/misc/",
            "/system/etc/",
            "/proc/",
            "/sys/fs/",
        )
        for (prefix in androidSensitive) {
            if (normalized.startsWith(prefix) || normalizedRaw.startsWith(prefix)) {
                return "sensitive_android_path: $prefix"
            }
        }

        return null
    }
}
