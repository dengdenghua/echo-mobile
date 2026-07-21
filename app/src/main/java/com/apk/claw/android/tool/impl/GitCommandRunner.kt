package com.apk.claw.android.tool.impl

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Git 命令执行器 —— 用 ProcessBuilder 在本地 shell 跑 git 命令的统一入口。
 *
 * 设计:
 *  - stdout / stderr 分流读出(各自起独立线程),避免子进程写满 pipe 缓冲区后阻塞死锁。
 *  - waitFor(timeout) 兜底,超时 destroyForcibly 强杀,避免长跑命令拖垮 Agent。
 *  - 返回 [GitCommandResult] 一次性给全 exitCode/stdout/stderr/success,调用方不必再拼装。
 *
 * 仅用于 git_* 工具内部,不对外暴露为工具本身。
 */
object GitCommandRunner {

    /**
     * 单次命令执行结果。
     *
     * @param exitCode 进程退出码;-1 表示超时或未正常退出。
     * @param stdout 进程标准输出(已完整读取)。
     * @param stderr 进程标准错误(已完整读取)。
     * @param success exitCode == 0 时为 true;超时/非零退出码均为 false。
     */
    data class GitCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean,
    )

    /**
     * 在 [workingDir] 下执行 [command],超时 [timeoutSec] 秒强杀。
     *
     * @param workingDir 工作目录,不存在时退化为当前 JVM 工作目录。
     * @param command 命令及参数(第一项为可执行文件,如 "git")。
     * @param timeoutSec 超时秒数,默认 60s。
     */
    fun runCommand(
        workingDir: File,
        vararg command: String,
        timeoutSec: Long = 60,
    ): GitCommandResult {
        val dir = if (workingDir.exists() && workingDir.isDirectory) workingDir else File(".")
        val process = ProcessBuilder(command.toList())
            .directory(dir)
            .redirectErrorStream(false)
            .start()

        // stdout / stderr 必须并发读,否则子进程写满 pipe 缓冲区(默认 ~64KB)后会阻塞死锁。
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }

        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        val stderr = try { stderrFuture.get() } catch (_: Exception) { "" }

        if (!finished) {
            process.destroyForcibly()
            return GitCommandResult(
                exitCode = -1,
                stdout = stdout,
                stderr = stderr,
                success = false,
            )
        }
        val exit = process.exitValue()
        return GitCommandResult(
            exitCode = exit,
            stdout = stdout,
            stderr = stderr,
            success = exit == 0,
        )
    }
}
