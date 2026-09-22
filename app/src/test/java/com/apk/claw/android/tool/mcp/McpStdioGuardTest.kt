package com.apk.claw.android.tool.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STDIO 命令白名单单测。
 *
 * 威胁模型:配置层写入的 argv 直接交给 ProcessBuilder 在 App UID 下 fork,等价于任意命令执行。
 */
class McpStdioGuardTest {

    @Test
    fun `accepts whitelisted bare commands`() {
        assertNull(McpStdioGuard.validate(listOf("npx", "@modelcontextprotocol/server-filesystem", "/sdcard")))
        assertNull(McpStdioGuard.validate(listOf("node", "server.js")))
        assertNull(McpStdioGuard.validate(listOf("uvx", "mcp-server-git")))
        assertNull(McpStdioGuard.validate(listOf("python3", "-m", "mcp_server")))
    }

    @Test
    fun `rejects empty command`() {
        assertNotNull(McpStdioGuard.validate(emptyList()))
    }

    @Test
    fun `rejects absolute and relative paths as argv0`() {
        assertNotNull(McpStdioGuard.validate(listOf("/system/bin/sh", "-c", "id")))
        assertNotNull(McpStdioGuard.validate(listOf("/usr/bin/node", "server.js")))
        assertNotNull(McpStdioGuard.validate(listOf("./payload", "arg")))
        assertNotNull(McpStdioGuard.validate(listOf("..\\payload", "arg")))
        assertNotNull(McpStdioGuard.validate(listOf("bin/node", "server.js")))
    }

    @Test
    fun `rejects non whitelisted commands`() {
        assertNotNull(McpStdioGuard.validate(listOf("sh", "-c", "id")))
        assertNotNull(McpStdioGuard.validate(listOf("bash", "script.sh")))
        assertNotNull(McpStdioGuard.validate(listOf("nc", "-l", "4444")))
        assertNotNull(McpStdioGuard.validate(listOf("rm", "-rf", "/sdcard")))
    }

    @Test
    fun `rejects argv0 with whitespace`() {
        assertNotNull(McpStdioGuard.validate(listOf(" node", "x")))
        assertNotNull(McpStdioGuard.validate(listOf("node ", "x")))
        assertNotNull(McpStdioGuard.validate(listOf("no de", "x")))
    }

    @Test
    fun `rejects shell metacharacters and line breaks in args`() {
        assertNotNull(McpStdioGuard.validate(listOf("node", "a;id")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "a|id")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "a`id")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "a>out")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "a" + "\n" + "id")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "a" + "\r" + "id")))
    }

    @Test
    fun `allows url style args with ampersand and dollar`() {
        // 故意放行 & 与 $:URL query、npm 版本范围等合法参数会用到(ProcessBuilder 不过 shell)
        assertNull(McpStdioGuard.validate(listOf("npx", "server", "http://host/sse?a=1&b=2")))
        assertNull(McpStdioGuard.validate(listOf("npx", "server", "--prefix=\$HOME/mcp")))
    }

    @Test
    fun `rejects blank or oversized args`() {
        assertNotNull(McpStdioGuard.validate(listOf("node", "   ")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "a".repeat(2000))))
    }

    @Test
    fun `rejects too many args`() {
        val many = listOf("node") + List(40) { "arg$it" }
        assertNotNull(McpStdioGuard.validate(many))
    }

    @Test
    fun `rejects loader hijacking env keys`() {
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("LD_PRELOAD" to "/tmp/evil.so")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("PATH" to "/tmp/bin")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("NODE_OPTIONS" to "--require /tmp/evil.js")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("PYTHONSTARTUP" to "/tmp/evil.py")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("JAVA_TOOL_OPTIONS" to "-Xbootclasspath")))
        // 大小写变体也要拦(env 名在 shell 语境下不区分大小写地危险)
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("ld_preload" to "/tmp/evil.so")))
    }

    @Test
    fun `rejects malformed env keys and values`() {
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("BAD-KEY" to "x")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("1KEY" to "x")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("OK" to "line1\nline2")))
        assertNotNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("OK" to "x".repeat(5000))))
    }

    @Test
    fun `accepts benign env`() {
        assertNull(McpStdioGuard.validate(listOf("node", "s.js"), mapOf("GITHUB_TOKEN" to "ghp_xxx")))
        assertNull(McpStdioGuard.validate(listOf("npx", "server"), mapOf("MCP_MODE" to "readonly")))
    }

    @Test
    fun `validation errors are descriptive`() {
        val err = McpStdioGuard.validate(listOf("rm", "-rf", "/"))
        assertNotNull(err)
        assertTrue(err!!.contains("白名单"))
        // 错误信息不得原样回显超长/控制字符内容
        val raw = McpStdioGuard.validate(listOf("node", "x" + "\u0007" + "y"))
        assertNotNull(raw)
        assertTrue(!raw!!.contains("\u0007"))
    }
}
