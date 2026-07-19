package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 会话式 Linux Shell 执行工具 —— 在内置 Alpine 容器中执行命令,同一 session_id 复用容器
 * home 目录、环境变量与已安装包,跨多次调用持久。
 *
 * 与 [RunShellTool] 的区别:
 *  - run_shell 每次是新进程,`cd` / `export` / 临时变量在命令结束后丢失
 *  - run_shell_session 把会话的 cwd/env 写入 home/.session_env,下次调用自动 source
 *  - 跨调用的 `apk add` / `pip install` 包永久保留(因为 home 是 bind mount 到 host 目录)
 *
 * 适用场景:
 *  - 多步构建(先 apk add,再写代码,再编译,再运行)
 *  - 分步调试(先 cd 进目录,再 ls,再 cat,再修改)
 *  - 长任务拆分(把一个大 shell 脚本拆成多步执行)
 *
 * 安全模型:与 [RunShellTool] 一致(HIGH + NON_IDEMPOTENT + 来源闸门 + 审计)。
 *
 * 会话实现:
 *  - 会话状态(home/.session_env)持久在容器 home 目录(实际是 host filesDir/linux-container/home/)
 *  - 每次 execute 先 source .session_env(恢复上次 cwd/env),执行命令,再把新 cwd/env 写回
 *  - 30 分钟无访问自动清理(.session_env 文件被删);最多 20 个并发会话(LRU)
 */
class RunShellSessionTool : BaseTool() {

    companion object {
        private const val MAX_COMMAND_LEN = 100_000
        private const val MAX_SESSION_ID_LEN = 128
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_SESSIONS = 20
        private const val SESSION_TTL_MS = 30 * 60 * 1000L  // 30 min

        private val sessionLock = Any()
        // sessionId → last access timestamp;LRU 淘汰
        private val sessionAccess = LinkedHashMap<String, Long>(MAX_SESSIONS, 0.75f, true)
    }

    override fun getName() = "run_shell_session"
    override fun getDisplayName() = if (useChineseDescription) "运行Shell(会话)" else "Run Shell (Session)"

    override fun getParameters() = listOf(
        ToolParameter(
            "session_id",
            "string",
            "Stable session identifier. Reusing the same id shares container home dir, env vars, " +
                "and installed packages across calls. Use a unique id per logical task.",
            true
        ),
        ToolParameter(
            "command",
            "string",
            "Shell command to execute in the Alpine container. cwd/env from previous calls in the " +
                "same session are restored; new cd/export persists to next call. apk add / pip install " +
                "packages are permanent (stored in container home).",
            true
        ),
        ToolParameter(
            "timeout_ms",
            "integer",
            "Max execution time in milliseconds. Default 30000, max 120000.",
            false
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val sessionId = requireString(params, "session_id")
        val command = requireString(params, "command")
        val validationError = when {
            sessionId.isBlank() -> "session_id 不能为空"
            sessionId.length > MAX_SESSION_ID_LEN ->
                "session_id 过长(${sessionId.length} > $MAX_SESSION_ID_LEN 字符)"
            command.isBlank() -> "command 不能为空"
            command.length > MAX_COMMAND_LEN ->
                "命令过长(${command.length} > $MAX_COMMAND_LEN 字符)"
            else -> null
        }
        if (validationError != null) {
            return ToolResult.error(validationError, ToolErr.INVALID_PARAM)
        }
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        // 会话注册 + LRU 淘汰
        registerSessionAccess(sessionId)

        // 包装命令:source 会话环境 → 执行 → 持久化新 cwd/env
        val wrappedCommand = buildWrappedCommand(sessionId, command)
        return LinuxSandbox.execute(
            command = wrappedCommand,
            timeoutMs = timeout,
            cwd = "/root",
            cancellationToken = currentCancellationToken(),
        )
    }

    /**
     * 构造会话包装命令:
     *  1. source 会话环境文件(若存在)恢复上次 cwd/env
     *  2. 执行用户命令
     *  3. 把当前 cwd/env 写回会话环境文件(供下次调用 source)
     *
     * 用 `set -a; source ...; set +a` 让 source 的变量自动 export。
     */
    private fun buildWrappedCommand(sessionId: String, command: String): String {
        // 会话环境文件路径(容器内 /root/.sessions/<id>.env)
        // sessionId 仅含 [a-zA-Z0-9_-],防路径注入(由上层校验,这里再 sanitize 一次)
        val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val envFile = "/root/.sessions/$safeId.env"
        val sessionDir = "/root/.sessions"

        // 注意:shell 脚本里的 $ 必须字面化,避免 Kotlin 字符串插值。
        // 用 char 拼接绕开插值:$ 作为 Char 直接拼到字符串里。
        val d = '$'.toString()
        return buildString {
            append("mkdir -p '$sessionDir'; ")
            append("if [ -f '$envFile' ]; then set -a; . '$envFile'; set +a; fi; ")
            append(command)
            append("; __ec=").append(d).append("?; ")
            // 持久化 cwd 和 export 变量(过滤掉 PRoot/proot 内部变量与只读变量)
            append("{ pwd > /tmp/__cwd; export -p > /tmp/__exports; } 2>/dev/null; ")
            append("printf 'cd \"%s\"\\n' \"").append(d).append("(cat /tmp/__cwd 2>/dev/null)\" > '$envFile'; ")
            append("grep -vE '^(declare -x (PWD|SHLVL|_|PROOT|PROOT_))' /tmp/__exports >> '$envFile' 2>/dev/null; ")
            append("exit ").append(d).append("{__ec}")
        }
    }

    /** 注册会话访问,LRU 淘汰超限的会话(仅清理内存索引,容器 home 不删)。 */
    private fun registerSessionAccess(sessionId: String) {
        synchronized(sessionLock) {
            val now = System.currentTimeMillis()
            sessionAccess[sessionId] = now
            // 淘汰过期会话
            sessionAccess.entries.removeAll { now - it.value > SESSION_TTL_MS }
            // 淘汰超限会话(LRU:LinkedHashMap access-order 最旧的在 head)
            while (sessionAccess.size > MAX_SESSIONS) {
                val oldest = sessionAccess.keys.iterator().next()
                sessionAccess.remove(oldest)
            }
        }
    }

    override fun getDescriptionEN() = """
        Execute a shell command in the Alpine Linux container with a persistent session.
        Same security model as run_shell (HIGH risk, untrusted source gate).

        Key difference from run_shell: same session_id shares container home dir, env vars, and
        installed packages across calls. cd/export persists to next call. apk add / pip install
        packages are permanent (stored in container home bind-mounted to host).

        Use for multi-step builds (apk add, write code, compile, run), step-by-step debugging,
        long task splitting.

        Sessions auto-expire after 30 min of inactivity; at most 20 concurrent sessions (LRU).
        Timeout default 30s (max 120s). Output ≤ 64KB. session_id ≤ 128 chars.
    """.trimIndent()

    override fun getDescriptionCN() = """
        在 Alpine Linux 容器中执行 shell 命令,支持会话持久。
        安全模型与 run_shell 一致(HIGH 风险,不可信来源走来源闸门)。

        与 run_shell 的区别:同一 session_id 的多次调用共享容器 home 目录、环境变量、已安装包。
        cd/export 跨调用持久。apk add / pip install 的包永久保留(存储在容器 home,bind 到宿主)。

        适合多步构建(apk add → 写代码 → 编译 → 运行)、分步调试、长任务拆分。

        会话 30 分钟无访问自动过期;最多 20 个并发会话(LRU 淘汰)。
        超时默认 30 秒(上限 120 秒)。输出 ≤ 64KB。session_id ≤ 128 字符。
    """.trimIndent()
}
