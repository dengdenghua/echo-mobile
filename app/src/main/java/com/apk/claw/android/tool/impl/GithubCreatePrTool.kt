package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * github_create_pr —— 通过 GitHub REST API v3 创建 Pull Request。
 *
 * 流程:
 *  1. 从 `path/.git/config` 解析 `[remote "origin"]` 的 url 字段,提取 owner/repo。
 *     支持 SSH(`git@github.com:foo/bar.git`)、HTTPS(`https://github.com/foo/bar.git`)
 *     及 `ssh://git@github.com/foo/bar.git` 三种形式。
 *  2. 从 [GithubTokenProvider] 拿 GitHub Personal Access Token —— 这是与存储层解耦的
 *     契约:工具不直接 import KVUtils,由上层注入具体实现(测试用 [NoopGithubTokenProvider])。
 *  3. POST https://api.github.com/repos/{owner}/{repo}/pulls,body `{title, body, head, base}`,
 *     `Authorization: token <github_token>`。
 *  4. 解析响应 `html_url` 字段返回 PR URL。
 *
 * 超时:30s(connect 15s,read 30s)。
 *
 * 风险等级:MEDIUM(对外发 HTTP,创建远程资源)。需在 [ToolRiskPolicy] 中归类为 MEDIUM。
 */
class GithubCreatePrTool(
    private val tokenProvider: GithubTokenProvider = NoopGithubTokenProvider(),
) : BaseTool() {

    companion object {
        private const val DEFAULT_BASE = "main"
        private const val API_BASE = "https://api.github.com"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * 从 GitHub remote URL 中解析 `(owner, repo)`。
         *
         * 支持:
         *  - `git@github.com:foo/bar.git`
         *  - `https://github.com/foo/bar.git`
         *  - `https://github.com/foo/bar`(无 .git 后缀)
         *  - `ssh://git@github.com/foo/bar.git`
         *  - `ssh://github.com/foo/bar.git`
         *
         * 不匹配返回 null。companion object 暴露便于单测。
         */
        fun parseOwnerRepo(remoteUrl: String): Pair<String, String>? {
            val trimmed = remoteUrl.trim()
            if (trimmed.isEmpty()) return null

            // SSH scp-like 语法:git@github.com:owner/repo(.git)?
            Regex("""git@github\.com:([^/]+)/([^/]+?)(?:\.git)?$""")
                .find(trimmed)?.let { return it.groupValues[1] to it.groupValues[2] }

            // HTTPS:https:// 或 http://
            Regex("""https?://github\.com/([^/]+)/([^/]+?)(?:\.git)?$""")
                .find(trimmed)?.let { return it.groupValues[1] to it.groupValues[2] }

            // ssh:// 形式:ssh://[git@]github.com/owner/repo(.git)?
            Regex("""ssh://(?:[^@]+@)?github\.com/([^/]+)/([^/]+?)(?:\.git)?$""")
                .find(trimmed)?.let { return it.groupValues[1] to it.groupValues[2] }

            return null
        }

        /**
         * 构建 refine-chat-interaction Task 6 结构化结果 JSON。
         *
         * 含 title / url / body 三字段,供 DefaultAgentService 解析后生成 TEXT Artifact。
         * 暴露为 companion 方法便于单测直接验证 JSON 格式,无需发真实 HTTP 请求。
         */
        fun buildResultJson(title: String, url: String?, body: String): String {
            val json = JSONObject()
            json.put("title", title)
            json.put("url", url ?: JSONObject.NULL)
            json.put("body", body)
            return json.toString()
        }

        /** 从 PR URL 中提取 PR 编号,用于 title。URL 形如 `https://github.com/owner/repo/pull/42`。 */
        internal fun extractPrNumber(prUrl: String): String? {
            val regex = Regex("""/pull/(\d+)""")
            return regex.find(prUrl)?.groupValues?.getOrNull(1)
        }
    }

    override fun getName(): String = "github_create_pr"

    override fun getDescriptionEN(): String =
        "Create a pull request on GitHub via REST API."

    override fun getDescriptionCN(): String =
        "通过 GitHub REST API 创建 Pull Request。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "path",
            "string",
            "Local git repository path (used to read .git/config for owner/repo).",
            true,
        ),
        ToolParameter(
            "title",
            "string",
            "Pull request title.",
            true,
        ),
        ToolParameter(
            "body",
            "string",
            "Pull request body (markdown). Default empty.",
            false,
        ),
        ToolParameter(
            "head",
            "string",
            "Source branch name (the branch you want to merge from).",
            true,
        ),
        ToolParameter(
            "base",
            "string",
            "Target branch name. Default: $DEFAULT_BASE.",
            false,
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val path = requireString(params, "path").trim()
        val title = requireString(params, "title")
        val head = requireString(params, "head").trim()
        val body = optionalString(params, "body", "")
        val base = optionalString(params, "base", DEFAULT_BASE).trim().ifEmpty { DEFAULT_BASE }

        if (path.isEmpty()) return ToolResult.error("path 不能为空", ToolErr.INVALID_PARAM)
        if (title.isBlank()) return ToolResult.error("title 不能为空", ToolErr.INVALID_PARAM)
        if (head.isEmpty()) return ToolResult.error("head 不能为空", ToolErr.INVALID_PARAM)

        val repoDir = File(path)
        if (!repoDir.exists() || !repoDir.isDirectory) {
            return ToolResult.error("仓库目录不存在: ${repoDir.absolutePath}", ToolErr.NOT_FOUND)
        }
        val gitConfig = File(repoDir, ".git/config")
        if (!gitConfig.exists() || !gitConfig.isFile) {
            return ToolResult.error(
                "不是 Git 仓库(缺少 .git/config): ${repoDir.absolutePath}",
                ToolErr.NOT_FOUND,
            )
        }

        val originUrl = parseOriginUrl(gitConfig)
            ?: return ToolResult.error(
                "无法从 .git/config 解析 origin 远端 URL",
                ToolErr.NOT_FOUND,
            )
        val (owner, repo) = parseOwnerRepo(originUrl)
            ?: return ToolResult.error(
                "无法从 origin URL 解析 owner/repo: $originUrl",
                ToolErr.INVALID_PARAM,
            )

        val token = tokenProvider.getToken()
            ?: return ToolResult.error(
                "未配置 GitHub Token,请在设置中填写 github_token。",
                ToolErr.PERMISSION,
            )

        val jsonBody = buildJsonBody(title, body, head, base)
        return try {
            createPrViaApi(owner, repo, token, jsonBody, title, body)
        } catch (e: IOException) {
            ToolResult.error("GitHub API 请求失败: ${e.message}", ToolErr.UPSTREAM)
        }
    }

    /**
     * 从 .git/config 文本中提取 `[remote "origin"]` 段的 `url = ...` 值。
     * 按行扫描,不依赖正则贪婪匹配,处理多种格式(等号两侧空格 / 制表符 / 引号)。
     */
    private fun parseOriginUrl(gitConfig: File): String? {
        val lines = try {
            gitConfig.bufferedReader().use { it.readLines() }
        } catch (_: IOException) {
            return null
        }
        var inOrigin = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                val section = line.removeSurrounding("[", "]").trim()
                val normalized = section.replace(Regex("\\s+"), " ")
                inOrigin = normalized.equals("remote \"origin\"", ignoreCase = true)
                continue
            }
            if (!inOrigin) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.equals("url", ignoreCase = true)) {
                val value = line.substring(eq + 1).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    /** 调 GitHub REST API 创建 PR,返回 ToolResult。 */
    private fun createPrViaApi(
        owner: String,
        repo: String,
        token: String,
        jsonBody: String,
        prTitle: String,
        prBody: String,
    ): ToolResult {
        val urlStr = "$API_BASE/repos/$owner/$repo/pulls"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Octopus-Mobile")
            doOutput = true
        }
        try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
            val respCode = conn.responseCode
            val respBody = (if (respCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (respCode !in 200..299) {
                return ToolResult.error(
                    "GitHub API 失败 (HTTP $respCode): ${respBody.take(500)}",
                    ToolErr.UPSTREAM,
                )
            }
            val prUrl = extractHtmlUrl(respBody)
                ?: return ToolResult.error(
                    "GitHub API 返回成功但未解析到 PR URL。响应: ${respBody.take(500)}",
                    ToolErr.UPSTREAM,
                )
            val prNumber = extractPrNumber(prUrl)
            val resultTitle = if (prNumber != null) "PR #$prNumber: $prTitle" else "PR: $prTitle"
            return ToolResult.success(buildResultJson(resultTitle, prUrl, prBody))
        } finally {
            conn.disconnect()
        }
    }

    /** 从 GitHub API 响应 JSON 中提取 `html_url` 字段值。 */
    private fun extractHtmlUrl(json: String): String? {
        val regex = Regex(""""html_url"\s*:\s*"([^"]+)"""")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    /** 手搓 JSON body,避免引入 JSON 库依赖。 */
    private fun buildJsonBody(title: String, body: String, head: String, base: String): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"title\":").append(jsonEscape(title)).append(',')
        sb.append("\"body\":").append(jsonEscape(body)).append(',')
        sb.append("\"head\":").append(jsonEscape(head)).append(',')
        sb.append("\"base\":").append(jsonEscape(base))
        sb.append('}')
        return sb.toString()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
