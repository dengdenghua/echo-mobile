package com.apk.claw.android.tool.impl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Git 工具结果结构化单测(spec refine-chat-interaction Task 6)。
 *
 * 3 个 Git 工具([GitCommitTool] / [GitPushTool] / [GithubCreatePrTool])成功返回时,
 * ToolResult.data 须为结构化 JSON,顶层含 `title` / `url` / `body` 3 字段,供
 * [com.apk.claw.android.agent.DefaultAgentService.emitGitTextArtifactIfNeeded]
 * 解析后生成 TEXT Artifact 卡片(UI 侧 URL 前缀触发「打开」按钮)。
 *
 * 本测不触发 Android 框架代码:只验证 companion object 的 [buildResultJson] 纯函数
 * 返回的 JSON 字段与语义,以及辅助提取器 [extractShortHash] / [extractPrNumber]。
 * 不依赖 Robolectric。
 */
class GitToolsResultFormatTest {

    // ── GitCommitTool.buildResultJson ──────────────────────────

    @Test
    fun `GitCommitTool buildResultJson contains title body and null url`() {
        val json = GitCommitTool.buildResultJson("commit abc1234", null, "fix: 修复登录")
        val obj = JSONObject(json)
        assertTrue("has title field", obj.has("title"))
        assertTrue("has url field", obj.has("url"))
        assertTrue("has body field", obj.has("body"))
        assertEquals("title matches", "commit abc1234", obj.getString("title"))
        assertEquals("body matches", "fix: 修复登录", obj.getString("body"))
        // url 传 null 时 JSON 中应为 null(JSONObject.NULL),optString 返回空串
        assertTrue("url should be null", obj.isNull("url"))
    }

    @Test
    fun `GitCommitTool buildResultJson with non-null url preserves it`() {
        val json = GitCommitTool.buildResultJson(
            "commit abc1234",
            "https://github.com/foo/bar/commit/abc1234",
            "[main abc1234] fix: 修复登录\n 1 file changed",
        )
        val obj = JSONObject(json)
        assertEquals("commit abc1234", obj.getString("title"))
        assertEquals(
            "https://github.com/foo/bar/commit/abc1234",
            obj.getString("url"),
        )
        assertTrue(obj.getString("body").contains("fix: 修复登录"))
    }

    @Test
    fun `GitCommitTool buildResultJson handles empty body`() {
        val json = GitCommitTool.buildResultJson("commit (no changes)", null, "")
        val obj = JSONObject(json)
        assertEquals("commit (no changes)", obj.getString("title"))
        assertEquals("", obj.getString("body"))
        assertTrue(obj.isNull("url"))
    }

    // ── GitCommitTool.extractShortHash ─────────────────────────

    @Test
    fun `GitCommitTool extractShortHash parses branch and hash line`() {
        // git commit 输出形如: [main abc1234] fix: 修复登录
        assertEquals(
            "abc1234",
            GitCommitTool.extractShortHash("[main abc1234] fix: 修复登录"),
        )
        assertEquals(
            "deadbeef",
            GitCommitTool.extractShortHash("[feature/foo deadbeef] feat: 新功能"),
        )
    }

    @Test
    fun `GitCommitTool extractShortHash handles long hash`() {
        // 40 字符完整 hash 也应匹配(取整串)
        val longHash = "abc1234def5678901234567890abcdef12345678"
        assertEquals(
            longHash,
            GitCommitTool.extractShortHash("[main $longHash] msg"),
        )
    }

    @Test
    fun `GitCommitTool extractShortHash returns null for non-matching output`() {
        assertNull(GitCommitTool.extractShortHash("nothing to commit, working tree clean"))
        assertNull(GitCommitTool.extractShortHash(""))
        assertNull(GitCommitTool.extractShortHash("[main] msg without hash"))
    }

    // ── GitPushTool.buildResultJson ────────────────────────────

    @Test
    fun `GitPushTool buildResultJson contains title body and null url`() {
        // push 无 URL,故 url 恒为 null
        val json = GitPushTool.buildResultJson(
            "pushed to origin/main",
            null,
            "To github.com:foo/bar.git\n   abc1234..def5678  main -> main",
        )
        val obj = JSONObject(json)
        assertTrue("has title field", obj.has("title"))
        assertTrue("has url field", obj.has("url"))
        assertTrue("has body field", obj.has("body"))
        assertEquals("pushed to origin/main", obj.getString("title"))
        assertTrue(obj.getString("body").contains("main -> main"))
        // push 不产生可点击 URL,url 必须为 null
        assertTrue("push url should be null", obj.isNull("url"))
    }

    @Test
    fun `GitPushTool buildResultJson with force flag in title`() {
        val json = GitPushTool.buildResultJson("pushed to origin/dev (force)", null, "forced update")
        val obj = JSONObject(json)
        assertEquals("pushed to origin/dev (force)", obj.getString("title"))
        assertEquals("forced update", obj.getString("body"))
        assertTrue(obj.isNull("url"))
    }

    @Test
    fun `GitPushTool buildResultJson handles empty body`() {
        val json = GitPushTool.buildResultJson("pushed to origin/main", null, "")
        val obj = JSONObject(json)
        assertEquals("", obj.getString("body"))
    }

    // ── GithubCreatePrTool.buildResultJson ─────────────────────

    @Test
    fun `GithubCreatePrTool buildResultJson contains title body and non-null url`() {
        // PR 创建成功必须带可点击 URL,UI 据此显示「打开」按钮(ACTION_VIEW)
        val prUrl = "https://github.com/foo/bar/pull/42"
        val json = GithubCreatePrTool.buildResultJson(
            "PR #42: Add login feature",
            prUrl,
            "## Summary\n实现登录页面\n\n## Test Plan\n- [x] 单测通过",
        )
        val obj = JSONObject(json)
        assertTrue("has title field", obj.has("title"))
        assertTrue("has url field", obj.has("url"))
        assertTrue("has body field", obj.has("body"))
        assertEquals("PR #42: Add login feature", obj.getString("title"))
        assertEquals(prUrl, obj.getString("url"))
        // url 必须非空、非 null
        assertFalse("PR url must not be null", obj.isNull("url"))
        assertTrue(obj.getString("url").startsWith("https://"))
        assertTrue(obj.getString("body").contains("实现登录页面"))
    }

    @Test
    fun `GithubCreatePrTool buildResultJson with null url still valid json`() {
        // 边界:API 异常时 url 可能为 null(虽然正常路径不会),JSON 仍应合法
        val json = GithubCreatePrTool.buildResultJson("PR: title", null, "body")
        val obj = JSONObject(json)
        assertEquals("PR: title", obj.getString("title"))
        assertEquals("body", obj.getString("body"))
        assertTrue(obj.isNull("url"))
    }

    @Test
    fun `GithubCreatePrTool buildResultJson preserves multiline body`() {
        val body = "Line1\nLine2\nLine3\n- bullet"
        val json = GithubCreatePrTool.buildResultJson("PR #1: T", "https://github.com/foo/bar/pull/1", body)
        val obj = JSONObject(json)
        assertEquals(body, obj.getString("body"))
    }

    // ── GithubCreatePrTool.extractPrNumber ─────────────────────

    @Test
    fun `GithubCreatePrTool extractPrNumber parses standard PR url`() {
        assertEquals(
            "42",
            GithubCreatePrTool.extractPrNumber("https://github.com/foo/bar/pull/42"),
        )
        assertEquals(
            "123",
            GithubCreatePrTool.extractPrNumber("https://github.com/foo/bar/pull/123"),
        )
    }

    @Test
    fun `GithubCreatePrTool extractPrNumber returns null for non-pr url`() {
        assertNull(GithubCreatePrTool.extractPrNumber("https://github.com/foo/bar"))
        assertNull(GithubCreatePrTool.extractPrNumber("https://github.com/foo/bar/issues/42"))
        assertNull(GithubCreatePrTool.extractPrNumber(""))
        assertNull(GithubCreatePrTool.extractPrNumber("not a url"))
    }

    // ── 跨工具一致性:3 个 buildResultJson 都产出合法 JSON 且字段齐全 ──

    @Test
    fun `all three git tools buildResultJson produce json with title url body fields`() {
        val cases = listOf(
            "git_commit" to GitCommitTool.buildResultJson("commit abc1234", null, "body"),
            "git_push" to GitPushTool.buildResultJson("pushed to origin/main", null, "body"),
            "github_create_pr" to GithubCreatePrTool.buildResultJson(
                "PR #42: T", "https://github.com/foo/bar/pull/42", "body",
            ),
        )
        cases.forEach { (toolName, jsonStr) ->
            val obj = JSONObject(jsonStr) // 解析不抛异常即合法 JSON
            assertNotNull("$toolName: parsed JSONObject should not be null", obj)
            assertTrue("$toolName: must have title", obj.has("title"))
            assertTrue("$toolName: must have url", obj.has("url"))
            assertTrue("$toolName: must have body", obj.has("body"))
            // title 与 body 必须为非空字符串
            assertTrue("$toolName: title must be non-blank", obj.getString("title").isNotBlank())
            assertTrue("$toolName: body must be non-blank", obj.getString("body").isNotBlank())
        }
    }

    @Test
    fun `only github_create_pr buildResultJson has non-null url in normal case`() {
        // spec 要求:git_commit / git_push 的 url 在正常路径下为 null(无可点击链接),
        // github_create_pr 的 url 非 null(可跳转 PR 页面)。UI 依此决定是否显示「打开」按钮。
        val commitJson = JSONObject(GitCommitTool.buildResultJson("commit abc1234", null, "body"))
        val pushJson = JSONObject(GitPushTool.buildResultJson("pushed to origin/main", null, "body"))
        val prJson = JSONObject(
            GithubCreatePrTool.buildResultJson(
                "PR #42: T", "https://github.com/foo/bar/pull/42", "body",
            ),
        )
        assertTrue("git_commit url must be null", commitJson.isNull("url"))
        assertTrue("git_push url must be null", pushJson.isNull("url"))
        assertFalse("github_create_pr url must be non-null", prJson.isNull("url"))
    }
}
