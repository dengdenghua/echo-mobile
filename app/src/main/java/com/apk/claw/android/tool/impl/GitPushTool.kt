package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import org.json.JSONObject
import java.io.File

/**
 * git_push —— 推送本地提交到远程仓库。
 *
 * 行为:
 *  - 在 [path] 目录执行 `git push [--force] <remote> <branch>`。
 *  - [branch] 留空时执行 `git push <remote>`(由 git 自行决定跟踪分支)。
 *  - [force]=true 时追加 `--force`,会覆盖远程历史 —— 应归类为 HIGH 风险。
 *  - 超时 60s。
 *
 * 风险等级:MEDIUM(默认);force=true 时事实性为 HIGH —— 调用方应在 [ToolRiskPolicy]
 * 中按 force 参数动态判定,或保守地整体归 HIGH。本工具仅声明风险描述,不自行覆盖策略。
 */
class GitPushTool : BaseTool() {

    companion object {
        private const val DEFAULT_REMOTE = "origin"
        private const val TIMEOUT_SEC = 60L

        /**
         * 构建 refine-chat-interaction Task 6 结构化结果 JSON。
         *
         * 含 title / url / body 三字段,供 DefaultAgentService 解析后生成 TEXT Artifact。
         * 暴露为 companion 方法便于单测直接验证 JSON 格式。
         */
        fun buildResultJson(title: String, url: String?, body: String): String {
            val json = JSONObject()
            json.put("title", title)
            json.put("url", url ?: JSONObject.NULL)
            json.put("body", body)
            return json.toString()
        }
    }

    override fun getName(): String = "git_push"

    override fun getDescriptionEN(): String =
        "Push local commits to remote repository."

    override fun getDescriptionCN(): String =
        "推送本地提交到远程仓库。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "path",
            "string",
            "Local git repository path (must contain a .git directory).",
            true,
        ),
        ToolParameter(
            "remote",
            "string",
            "Remote name. Default: $DEFAULT_REMOTE.",
            false,
        ),
        ToolParameter(
            "branch",
            "string",
            "Branch to push. If omitted, pushes the current tracking branch.",
            false,
        ),
        ToolParameter(
            "force",
            "boolean",
            "Force push (rewrites remote history, dangerous). Default: false.",
            false,
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val path = requireString(params, "path").trim()
        if (path.isEmpty()) {
            return ToolResult.error("path 不能为空", ToolErr.INVALID_PARAM)
        }
        val repoDir = File(path)
        if (!repoDir.exists() || !repoDir.isDirectory) {
            return ToolResult.error("仓库目录不存在: ${repoDir.absolutePath}", ToolErr.NOT_FOUND)
        }
        if (!File(repoDir, ".git").exists()) {
            return ToolResult.error(
                "不是 Git 仓库(缺少 .git 目录): ${repoDir.absolutePath}",
                ToolErr.NOT_FOUND,
            )
        }

        val remote = optionalString(params, "remote", DEFAULT_REMOTE).trim().ifEmpty { DEFAULT_REMOTE }
        val branch = optionalString(params, "branch", "").trim()
        val force = optionalBoolean(params, "force", false)

        val cmd = buildList {
            add("git")
            add("push")
            if (force) add("--force")
            add(remote)
            if (branch.isNotEmpty()) add(branch)
        }.toTypedArray()

        val result = GitCommandRunner.runCommand(repoDir, *cmd, timeoutSec = TIMEOUT_SEC)
        if (!result.success) {
            val msg = result.stderr.ifBlank { result.stdout }.ifBlank { "(no output)" }
            return ToolResult.error(
                "git push 失败 (exitCode=${result.exitCode}): $msg",
                ToolErr.INTERNAL,
            )
        }
        val tail = result.stderr.ifBlank { result.stdout }
        val title = buildString {
            append("pushed to ")
            append(remote)
            if (branch.isNotEmpty()) append("/").append(branch)
            if (force) append(" (force)")
        }
        val body = buildString {
            append("已推送")
            if (force) append("(force)")
            append("。")
            if (tail.isNotBlank()) append("\n").append(tail.trim().take(1000))
        }
        return ToolResult.success(buildResultJson(title, null, body))
    }
}
