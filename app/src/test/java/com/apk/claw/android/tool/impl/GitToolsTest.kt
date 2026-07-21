package com.apk.claw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Git 工具集 JVM 单元测试。
 *
 * 不依赖 Robolectric:被测对象([GitCommandRunner] / [GithubCreatePrTool.parseOwnerRepo]
 * / 工具参数 schema)均不触发 Android 框架代码加载。
 *
 * 覆盖:
 *  - [GitCommandRunner] 跑 `git --version` 成功
 *  - [GitCommandRunner] 超时返回 success=false
 *  - [GithubCreatePrTool.parseOwnerRepo] 解析 SSH / HTTPS 两种 origin URL
 *  - [GitCloneTool] / [GitCommitTool] 参数 schema 完整(必填/可选/类型正确)
 */
class GitToolsTest {

    // ── GitCommandRunner ────────────────────────────────────────

    @Test
    fun `GitCommandRunner runs git --version successfully`() {
        val result = GitCommandRunner.runCommand(
            File("."),
            "git", "--version",
            timeoutSec = 30,
        )
        assertTrue("git --version should succeed", result.success)
        assertEquals("exitCode should be 0", 0, result.exitCode)
        assertTrue(
            "stdout should contain 'git version', got: ${result.stdout}",
            result.stdout.contains("git version"),
        )
    }

    @Test
    fun `GitCommandRunner returns failure on timeout`() {
        // `sleep 10` 不会在 1 秒内结束,触发超时分支。
        // CI 容器若没有 sleep 命令会以非零退出码失败,这两种情况 success 都应为 false。
        val result = GitCommandRunner.runCommand(
            File("."),
            "sleep", "10",
            timeoutSec = 1,
        )
        assertFalse("timeout should produce success=false", result.success)
        assertEquals("exitCode should be -1 on timeout", -1, result.exitCode)
    }

    @Test
    fun `GitCommandRunner captures non-zero exit code`() {
        // `false` 命令总是返回退出码 1(POSIX)。
        val result = GitCommandRunner.runCommand(
            File("."),
            "false",
            timeoutSec = 5,
        )
        assertFalse("false command should fail", result.success)
        assertEquals("exitCode should be 1", 1, result.exitCode)
    }

    // ── GithubCreatePrTool.parseOwnerRepo ───────────────────────

    @Test
    fun `parseOwnerRepo parses SSH scp-like URL`() {
        val pair = GithubCreatePrTool.parseOwnerRepo("git@github.com:foo/bar.git")
        assertNotNull("should parse SSH URL", pair)
        assertEquals("owner", "foo", pair?.first)
        assertEquals("repo", "bar", pair?.second)
    }

    @Test
    fun `parseOwnerRepo parses HTTPS URL with git suffix`() {
        val pair = GithubCreatePrTool.parseOwnerRepo("https://github.com/foo/bar.git")
        assertNotNull("should parse HTTPS URL", pair)
        assertEquals("owner", "foo", pair?.first)
        assertEquals("repo", "bar", pair?.second)
    }

    @Test
    fun `parseOwnerRepo parses HTTPS URL without git suffix`() {
        val pair = GithubCreatePrTool.parseOwnerRepo("https://github.com/foo/bar")
        assertNotNull("should parse HTTPS URL without .git", pair)
        assertEquals("owner", "foo", pair?.first)
        assertEquals("repo", "bar", pair?.second)
    }

    @Test
    fun `parseOwnerRepo parses ssh slash-form URL`() {
        val pair = GithubCreatePrTool.parseOwnerRepo("ssh://git@github.com/foo/bar.git")
        assertNotNull("should parse ssh:// URL", pair)
        assertEquals("owner", "foo", pair?.first)
        assertEquals("repo", "bar", pair?.second)
    }

    @Test
    fun `parseOwnerRepo returns null for non-github URL`() {
        assertEquals(null, GithubCreatePrTool.parseOwnerRepo("https://gitlab.com/foo/bar.git"))
        assertEquals(null, GithubCreatePrTool.parseOwnerRepo(""))
        assertEquals(null, GithubCreatePrTool.parseOwnerRepo("not a url"))
    }

    // ── GitCloneTool schema ─────────────────────────────────────

    @Test
    fun `GitCloneTool has correct name`() {
        assertEquals("git_clone", GitCloneTool().getName())
    }

    @Test
    fun `GitCloneTool parameter schema is complete`() {
        val tool = GitCloneTool()
        val byName = tool.getParameters().associateBy { it.name }

        // 两个必填参数
        listOf("url", "path").forEach { name ->
            val p = byName[name]
            assertNotNull("missing required param: $name", p)
            assertEquals("$name should be required", true, p?.isRequired)
            assertEquals("$name should be string type", "string", p?.type)
        }
        // 可选 branch
        val branch = byName["branch"]
        assertNotNull("missing optional param: branch", branch)
        assertEquals("branch should be optional", false, branch?.isRequired)
        assertEquals("branch should be string type", "string", branch?.type)
    }

    @Test
    fun `GitCloneTool descriptions are not empty`() {
        val tool = GitCloneTool()
        assertTrue(tool.getDescriptionCN().isNotBlank())
        assertTrue(tool.getDescriptionEN().isNotBlank())
    }

    @Test
    fun `GitCloneTool rejects missing url or path`() {
        val tool = GitCloneTool()
        // 缺 url
        val r1 = tool.execute(mapOf("path" to "/tmp/foo"))
        assertFalse("missing url should fail", r1.isSuccess)
        // 缺 path
        val r2 = tool.execute(mapOf("url" to "https://github.com/foo/bar.git"))
        assertFalse("missing path should fail", r2.isSuccess)
        // 空 url
        val r3 = tool.execute(mapOf("url" to "  ", "path" to "/tmp/foo"))
        assertFalse("blank url should fail", r3.isSuccess)
    }

    // ── GitCommitTool schema ────────────────────────────────────

    @Test
    fun `GitCommitTool has correct name`() {
        assertEquals("git_commit", GitCommitTool().getName())
    }

    @Test
    fun `GitCommitTool parameter schema is complete`() {
        val tool = GitCommitTool()
        val byName = tool.getParameters().associateBy { it.name }

        // 两个必填参数
        listOf("path", "message").forEach { name ->
            val p = byName[name]
            assertNotNull("missing required param: $name", p)
            assertEquals("$name should be required", true, p?.isRequired)
            assertEquals("$name should be string type", "string", p?.type)
        }
        // 可选 files(数组)
        val files = byName["files"]
        assertNotNull("missing optional param: files", files)
        assertEquals("files should be optional", false, files?.isRequired)
        assertEquals("files should be array type", "array", files?.type)
    }

    @Test
    fun `GitCommitTool descriptions are not empty`() {
        val tool = GitCommitTool()
        assertTrue(tool.getDescriptionCN().isNotBlank())
        assertTrue(tool.getDescriptionEN().isNotBlank())
    }

    @Test
    fun `GitCommitTool rejects missing path or message`() {
        val tool = GitCommitTool()
        // 缺 message
        val r1 = tool.execute(mapOf("path" to "/tmp/foo"))
        assertFalse("missing message should fail", r1.isSuccess)
        // 缺 path
        val r2 = tool.execute(mapOf("message" to "init"))
        assertFalse("missing path should fail", r2.isSuccess)
        // 空 message
        val r3 = tool.execute(mapOf("path" to "/tmp/foo", "message" to "   "))
        assertFalse("blank message should fail", r3.isSuccess)
    }

    // ── GitPushTool schema (额外覆盖) ────────────────────────────

    @Test
    fun `GitPushTool has correct name`() {
        assertEquals("git_push", GitPushTool().getName())
    }

    @Test
    fun `GitPushTool parameter schema is complete`() {
        val tool = GitPushTool()
        val byName = tool.getParameters().associateBy { it.name }
        // path 必填
        val path = byName["path"]
        assertNotNull("missing required param: path", path)
        assertEquals("path should be required", true, path?.isRequired)
        // remote / branch / force 均可选
        listOf("remote", "branch").forEach { name ->
            val p = byName[name]
            assertNotNull("missing optional param: $name", p)
            assertEquals("$name should be optional", false, p?.isRequired)
            assertEquals("$name should be string type", "string", p?.type)
        }
        val force = byName["force"]
        assertNotNull("missing optional param: force", force)
        assertEquals("force should be optional", false, force?.isRequired)
        assertEquals("force should be boolean type", "boolean", force?.type)
    }

    // ── GithubCreatePrTool schema (额外覆盖) ────────────────────

    @Test
    fun `GithubCreatePrTool has correct name`() {
        assertEquals("github_create_pr", GithubCreatePrTool().getName())
    }

    @Test
    fun `GithubCreatePrTool parameter schema is complete`() {
        val tool = GithubCreatePrTool()
        val byName = tool.getParameters().associateBy { it.name }
        // 三个必填:path / title / head
        listOf("path", "title", "head").forEach { name ->
            val p = byName[name]
            assertNotNull("missing required param: $name", p)
            assertEquals("$name should be required", true, p?.isRequired)
            assertEquals("$name should be string type", "string", p?.type)
        }
        // 两个可选:body / base
        listOf("body", "base").forEach { name ->
            val p = byName[name]
            assertNotNull("missing optional param: $name", p)
            assertEquals("$name should be optional", false, p?.isRequired)
            assertEquals("$name should be string type", "string", p?.type)
        }
    }

    @Test
    fun `GithubCreatePrTool with NoopGithubTokenProvider rejects with PERMISSION`() {
        val tool = GithubCreatePrTool(NoopGithubTokenProvider())
        // 不进入 HTTP 分支:不存在的目录直接 NOT_FOUND
        val r1 = tool.execute(
            mapOf(
                "path" to "/nonexistent/dir/xyz",
                "title" to "T",
                "head" to "feature",
            )
        )
        assertFalse(r1.isSuccess)
    }
}
