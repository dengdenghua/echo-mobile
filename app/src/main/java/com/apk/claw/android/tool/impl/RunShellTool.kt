package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * Linux Shell 执行工具 —— 在内置 Linux 容器(基于 PRoot,无需 root)中执行任意 shell 命令。
 *
 * 支持两种发行版(由 `distro` 参数选):
 *  - `alpine`(默认):Alpine minirootfs,~3MB,musl libc,apk 包管理。开箱即用,APK 内置。
 *  - `ubuntu`:Ubuntu 24.04 Base,~28MB,glibc,apt 包管理。pip 装包几乎 100% 兼容,支持 .deb。
 *    需要用户先在设置页下载 Ubuntu rootfs(28MB,从 cdimage.ubuntu.com 官方源)。
 *
 * 与 [RunCodeTool](Rhino JS) / [RunPythonTool](Chaquopy Python) 的区别:
 *  - run_code / run_python 在 JVM 内嵌解释器跑,无法 `apt install` / `pip install` / 跑 ELF 二进制
 *  - run_shell 在真实 Linux 用户态跑,能 `apk add` / `apt install` 装任何包,
 *    能跑任意 shell 脚本 / 编译型语言二进制 / systemd 外的守护进程
 *
 * 适用场景:
 *  - 需要 apt/apk 装包的命令(ffmpeg 转码、imagemagick 处理、ripgrep 搜索)
 *  - 复杂 shell 管道、find/xargs/awk/sed 组合
 *  - 跑 Python/Node/Rust/Go 二进制(JS/Python 沙箱表达力不足时)
 *  - Git 操作、SSH、curl/wget 复杂请求
 *  - 需要 glibc 生态(选 ubuntu):pip install numpy/pandas、跑 .deb 包
 *
 * 安全模型:
 *  - 登记 HIGH 风险 + NON_IDEMPOTENT(与 run_code/run_python 一致)
 *  - 容器根目录隔离(PRoot chroot),看不到 App data/data、其他 App、/system
 *  - Bind mount 白名单:默认只挂 /sdcard/Download 和 /sdcard/Documents
 *  - 不可信来源走来源闸门弹审批 + 全程审计
 *  - 超时默认 30s(上限 120s),超时强杀进程
 *  - 输出上限 64KB
 *
 * 限制:
 *  - ptrace 有 10-30% 性能开销(对秒级命令可忽略)
 *  - 部分 ROM(华为/小米)SELinux 严格可能拒 ptrace → 工具返回明确错误
 *  - 不能跑 systemd / 需要 CAP_SYS_ADMIN 的操作
 *  - 容器内 raw socket 不走宿主 UrlGuard,需 Agent 自行确认目标安全
 *  - MVP 仅支持 arm64-v8a 设备(覆盖 95%+ 在网设备)
 */
class RunShellTool : BaseTool() {

    companion object {
        private const val MAX_COMMAND_LEN = 100_000
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MIN_TIMEOUT_MS = 1_000L
    }

    override fun getName() = "run_shell"
    override fun getDisplayName() = if (useChineseDescription) "运行Shell(Linux容器)" else "Run Shell (Linux)"

    override fun getParameters() = listOf(
        ToolParameter(
            "command",
            "string",
            "Shell command to execute in the built-in Linux container (PRoot-based, no root). " +
                "Full Linux environment: apk add (Alpine) or apt install (Ubuntu) python3/nodejs/git/ffmpeg, " +
                "shell pipes, find/xargs/awk, compile binaries, run scripts. Container is isolated " +
                "(no access to App data/system). Default binds: /sdcard/Download and /sdcard/Documents.",
            true
        ),
        ToolParameter(
            "timeout_ms",
            "integer",
            "Max execution time in milliseconds. Default 30000, max 120000.",
            false
        ),
        ToolParameter(
            "cwd",
            "string",
            "Working directory inside container (default /root). Must be absolute path.",
            false
        ),
        ToolParameter(
            "distro",
            "string",
            "Linux distribution: 'alpine' (default, musl, apk, ~3MB built-in) or " +
                "'ubuntu' (glibc, apt, ~28MB, requires user to download rootfs first). " +
                "Use 'ubuntu' when you need glibc ecosystem (pip install numpy/pandas, .deb packages).",
            false
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val command = requireString(params, "command")
        if (command.isBlank()) {
            return ToolResult.error("command 不能为空", ToolErr.INVALID_PARAM)
        }
        if (command.length > MAX_COMMAND_LEN) {
            return ToolResult.error(
                "命令过长(${command.length} > $MAX_COMMAND_LEN 字符)。",
                ToolErr.INVALID_PARAM,
            )
        }
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val cwd = optionalString(params, "cwd", "/root")
            .takeIf { it.isNotBlank() }
        val distro = when (optionalString(params, "distro", "alpine").lowercase()) {
            "alpine" -> LinuxSandbox.Distro.ALPINE
            "ubuntu" -> LinuxSandbox.Distro.UBUNTU
            else -> return ToolResult.error(
                "不支持的 distro,可选:alpine | ubuntu",
                ToolErr.INVALID_PARAM,
            )
        }

        return LinuxSandbox.execute(
            command = command,
            timeoutMs = timeout,
            cwd = cwd,
            cancellationToken = currentCancellationToken(),
            distro = distro,
        )
    }

    override fun getDescriptionEN() = """
        Execute a shell command in the built-in Linux container (PRoot-based, no root required).
        Two distros available via 'distro' param:
          - alpine (default): musl libc, apk package manager, ~3MB, built into APK
          - ubuntu: glibc, apt package manager, ~28MB, requires user to download rootfs first
            (use ubuntu when you need pip install numpy/pandas, .deb packages, full glibc ecosystem)

        Install anything: apk add (alpine) or apt install (ubuntu) python3, nodejs, git, ffmpeg,
        imagemagick, ripgrep, etc. Supports shell pipes, find/xargs/awk/sed, compiled binaries, scripts.

        Use this when run_code (JS) or run_python (embedded CPython) are insufficient:
          - Need to apt/apk install packages
          - Complex shell pipelines
          - Run ELF binaries (Rust/Go/C compiled)
          - Git/SSH/curl/wget complex operations
          - Need glibc ecosystem (pip install numpy/pandas, .deb packages) → use ubuntu

        Security: HIGH risk, untrusted source gate applies. Container is isolated via PRoot chroot —
        no access to App data, other apps, or /system. Default binds: /sdcard/Download, /sdcard/Documents.
        Raw sockets inside container do NOT go through host SSRF guard — verify target safety yourself.

        Limitations: 10-30% ptrace overhead. Some ROMs (Huawei/Xiaomi) may block ptrace. Cannot run
        systemd. MVP supports arm64-v8a only. Timeout default 30s (max 120s). Output ≤ 64KB.
    """.trimIndent()

    override fun getDescriptionCN() = """
        在内置 Linux 容器中执行 shell 命令(基于 PRoot,无需 root)。
        通过 'distro' 参数选发行版:
          - alpine(默认):musl libc,apk 包管理,~3MB,APK 内置开箱即用
          - ubuntu:glibc,apt 包管理,~28MB,需要用户先在设置页下载 rootfs
            (需要 pip install numpy/pandas、.deb 包等完整 glibc 生态时选 ubuntu)

        可装任意包:python3、nodejs、git、ffmpeg、imagemagick、ripgrep 等。支持 shell 管道、
        find/xargs/awk/sed、编译型二进制、脚本。

        当 run_code(JS) 或 run_python(嵌入式 CPython) 不够用时用本工具:
          - 需要 apt/apk 装包
          - 复杂 shell 管道
          - 跑 ELF 二进制(Rust/Go/C 编译产物)
          - Git/SSH/curl/wget 复杂操作
          - 需要 glibc 生态(pip install numpy/pandas、.deb 包)→ 用 ubuntu

        安全:HIGH 风险,不可信来源走来源闸门。容器通过 PRoot chroot 隔离 ——
        无法访问 App data、其他 App 或 /system。默认 bind: /sdcard/Download、/sdcard/Documents。
        容器内 raw socket 不走宿主 SSRF 防护 —— 请自行确认目标安全。

        限制:ptrace 有 10-30% 性能开销。部分 ROM(华为/小米)可能拒 ptrace。不能跑 systemd。
        MVP 仅支持 arm64-v8a。超时默认 30 秒(上限 120 秒)。输出 ≤ 64KB。
    """.trimIndent()
}
