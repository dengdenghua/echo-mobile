package com.apk.claw.android.shizuku

/**
 * Shizuku 进程创建失败（`Shizuku.newProcess` 不可用）时的显式降级策略。
 *
 * 以前会静默回退到 `Runtime.exec()`（app UID）并把结果当作 shell 权限下的结果返回，
 * 调用方无法区分“以 shell 身份成功”和“以 app 身份执行（多半失败或结果不完整）”。现在：
 *  - 需要 shell 权限的命令（输入注入、截屏、settings/am/pm/cmd/dumpsys/wm/...）直接拒绝，返回明确错误；
 *  - 其余命令（/sdcard 下的文件读写等）可以以 app UID 执行，但结果会被标记为 [ShizukuShellService.ShellResult.privileged] = false，
 *    并在 stderr 前追加 [UNPRIVILEGED_NOTE]。
 */
object UnprivilegedShellFallback {

    const val UNPRIVILEGED_NOTE =
        "[unprivileged] Shizuku process unavailable; command ran as the app UID without shell privileges"

    const val REFUSED_MESSAGE =
        "Shizuku process unavailable; refusing to run a command that requires shell privileges as the app UID"

    /**
     * 取命令首个 token（兼容 `sh -c "xxx ..."` 包装）判断是否需要 shell 权限。
     * 只有白名单内的文件类命令允许以 app UID 降级执行；input/screencap/settings/am/pm/cmd/dumpsys/wm 等
     * 以及任何未知命令都按“需要权限”处理（失败安全）。
     */
    fun requiresElevation(command: String): Boolean {
        var trimmed = command.trimStart()
        if (trimmed.startsWith("sh -c ")) {
            trimmed = trimmed.removePrefix("sh -c ").trimStart().trimStart('"', '\'').trimStart()
        }
        val program = trimmed.substringBefore(' ').substringAfterLast('/')
        return program !in UNPRIVILEGED_OK_COMMANDS
    }

    /** 以 app UID 执行仍有意义的命令（作用于 app 可访问的 /sdcard 等路径）。 */
    private val UNPRIVILEGED_OK_COMMANDS = setOf(
        "ls", "stat", "du", "df", "find", "grep", "cp", "mv", "rm", "mkdir", "head", "cat", "echo",
    )

    /**
     * app UID 下执行的输入类命令：`input` 在缺少 INJECT_EVENTS 时常以 0 退出但打印 SecurityException，
     * 因此除退出码外还要检查输出中的异常信息。
     */
    fun appUidCommandSucceeded(exitCode: Int, output: String): Boolean =
        exitCode == 0 && FAILURE_MARKERS.none { output.contains(it, ignoreCase = true) }

    private val FAILURE_MARKERS = listOf("Exception", "Permission denial", "not allowed", "Error:")

    fun refused():ShizukuShellService.ShellResult =
        ShizukuShellService.ShellResult(exitCode = -1, stdout = "", stderr = REFUSED_MESSAGE, privileged = false)

    fun markUnprivileged(result: ShizukuShellService.ShellResult): ShizukuShellService.ShellResult =
        result.copy(
            stderr = if (result.stderr.isEmpty()) UNPRIVILEGED_NOTE else "$UNPRIVILEGED_NOTE\n${result.stderr}",
            privileged = false,
        )
}
