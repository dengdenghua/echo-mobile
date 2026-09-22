package com.apk.claw.android.tool.mcp

/**
 * MCP STDIO 命令守卫 —— 校验用户配置的 stdio MCP server 启动命令。
 *
 * 威胁模型：stdio 传输用 `ProcessBuilder(argv)` 直接在 App UID 下 fork 子进程。命令来自「MCP Server
 * 配置」页（可被 UI 输入、配置导入或未来的远程下发写入），若不校验，等于把「任意命令执行」能力暴露给
 * 配置层 —— Android 沙箱内仍是本 App 的全部权限（/sdcard 读写、已授权应用数据、网络、本机 token）。
 *
 * 防线（纯 JVM，无 Android 依赖，可直接单测）：
 *  1. argv[0] 必须是白名单里的**裸命令名**（npx/node/uvx/python…）：禁止路径分隔符、`..`、前后空白，
 *     也不接受绝对路径 —— 避免被换成 /system/bin/sh、./payload 之类的任意可执行文件。
 *  2. 参数禁止换行/回车/NUL 等控制字符与 `;` `|` `` ` `` `<` `>` 等无法出现在合法 MCP 参数里的字符。
 *     （ProcessBuilder 不经过 shell，这里属于纵深防御：防止未来被包一层 `sh -c` 时逃逸。
 *     刻意放行 `&` 与 `$`，URL query、版本范围等合法参数会用到。）
 *  3. 环境变量名/值白名单化，并拒绝 `LD_PRELOAD`/`PATH`/`NODE_OPTIONS`/`JAVA_TOOL_OPTIONS`/
 *     `PYTHONSTARTUP` 等能劫持子进程加载器的键。
 *
 * 用法：
 * ```kotlin
 * McpStdioGuard.validate(command, env)?.let { reason -> /* 拒绝启动并提示 reason */ }
 * ```
 */
object McpStdioGuard {

    /** 允许作为 argv[0] 的裸命令名（不含任何路径分隔符）。 */
    val ALLOWED_COMMANDS: Set<String> = setOf(
        "npx", "npm", "node", "bun", "bunx", "deno",
        "uvx", "uv", "pipx", "python", "python3",
    )

    /** 环境变量名的合法形态。 */
    private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** 会劫持子进程动态加载/启动路径的环境变量：一律拒绝。 */
    private val BLOCKED_ENV_KEYS: Set<String> = setOf(
        "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT",
        "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH",
        "PATH", "SHELL", "BASH_ENV", "ENV",
        "NODE_OPTIONS", "NODE_PATH", "NODE_REPL_EXTERNAL_MODULE",
        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "CLASSPATH",
        "PYTHONSTARTUP", "PYTHONPATH", "PYTHONHOME", "PYTHONINSPECT",
        "PERL5OPT", "RUBYOPT",
        "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ASSETS", "ANDROID_STORAGE",
    )

    /** 参数中禁止出现的字符（控制字符 + 只可能用于 shell 注入的元字符）。 */
    private val FORBIDDEN_ARG_CHARS = charArrayOf(
        ';', '|', '`', '<', '>', '\n', '\r', '\u0000',
    )

    private const val MAX_ARGS = 32
    private const val MAX_ARG_LEN = 1024
    private const val MAX_ENV_ENTRIES = 32
    private const val MAX_ENV_VALUE_LEN = 4096

    /**
     * 校验 stdio 启动命令与环境变量。
     * @return null=通过；非 null=拒绝原因（可直接展示给用户）
     */
    fun validate(command: List<String>, env: Map<String, String> = emptyMap()): String? {
        validateCommand(command)?.let { return it }
        return validateEnv(env)
    }

    /** 只校验命令 argv（供不关心环境变量的调用方使用）。 */
    fun validateCommand(command: List<String>): String? {
        if (command.isEmpty()) return "STDIO 命令不能为空"
        if (command.size > MAX_ARGS) return "STDIO 参数过多（上限 $MAX_ARGS）"

        val argv0 = command[0]
        if (argv0.isBlank()) return "STDIO 命令不能为空"
        if (argv0 != argv0.trim()) return "STDIO 命令名不能含空白：${sanitize(argv0)}"
        if (argv0.contains('/') || argv0.contains('\\')) {
            return "STDIO 命令只接受裸命令名，不允许路径：${sanitize(argv0)}"
        }
        if (argv0.contains("..")) return "STDIO 命令名非法：${sanitize(argv0)}"
        if (argv0 !in ALLOWED_COMMANDS) {
            return "STDIO 命令不在白名单内：${sanitize(argv0)}" +
                "（允许：${ALLOWED_COMMANDS.sorted().joinToString("/")}）"
        }

        for (arg in command.drop(1)) {
            if (arg.length > MAX_ARG_LEN) return "STDIO 参数过长（上限 $MAX_ARG_LEN 字符）"
            if (arg.isBlank()) return "STDIO 参数不能为空串"
            if (arg.any { it in FORBIDDEN_ARG_CHARS }) {
                return "STDIO 参数含禁用字符：${sanitize(arg)}"
            }
            if (arg.any { it.isISOControl() }) return "STDIO 参数含控制字符：${sanitize(arg)}"
        }
        return null
    }

    /** 只校验环境变量。 */
    fun validateEnv(env: Map<String, String>): String? {
        if (env.size > MAX_ENV_ENTRIES) return "环境变量过多（上限 $MAX_ENV_ENTRIES）"
        for ((k, v) in env) {
            if (!ENV_KEY_REGEX.matches(k)) return "环境变量名非法：${sanitize(k)}"
            if (k.uppercase() in BLOCKED_ENV_KEYS) return "环境变量被禁用：${sanitize(k)}"
            if (v.length > MAX_ENV_VALUE_LEN) return "环境变量值过长：${sanitize(k)}"
            if (v.any { it == '\u0000' || it == '\n' || it == '\r' }) {
                return "环境变量值含控制字符：${sanitize(k)}"
            }
        }
        return null
    }

    /** 去掉控制字符后截断，避免把恶意内容原样回显到日志/UI。 */
    private fun sanitize(raw: String): String =
        raw.filterNot { it.isISOControl() }.take(64)
}
