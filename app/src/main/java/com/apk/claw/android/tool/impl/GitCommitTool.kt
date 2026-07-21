package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.io.File

/**
 * git_commit —— 暂存并提交变更到本地 git 仓库。
 *
 * 行为:
 *  - 在 [path] 目录执行 `git add <files 或 -A>` 然后 `git commit -m <message>`。
 *  - [files] 为空时执行 `git add -A`(全部暂存);非空时仅暂存指定文件。
 *  - 超时 30s。
 *  - 检测到「nothing to commit / no changes」时返回 success(空提交不算失败)。
 *
 * 风险等级:MEDIUM(改本地仓库状态)。需在 [ToolRiskPolicy] 中归类为 MEDIUM。
 */
class GitCommitTool : BaseTool() {

    companion object {
        private const val TIMEOUT_SEC = 30L
    }

    override fun getName(): String = "git_commit"

    override fun getDescriptionEN(): String =
        "Stage and commit changes to a local git repository."

    override fun getDescriptionCN(): String =
        "暂存并提交变更到本地 git 仓库。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "path",
            "string",
            "Local git repository path (must contain a .git directory).",
            true,
        ),
        ToolParameter(
            "message",
            "string",
            "Commit message.",
            true,
        ),
        ToolParameter(
            "files",
            "array",
            "Specific files to stage. If empty or omitted, stages all changes (git add -A).",
            false,
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val path = requireString(params, "path").trim()
        val message = requireString(params, "message")
        if (path.isEmpty()) {
            return ToolResult.error("path 不能为空", ToolErr.INVALID_PARAM)
        }
        if (message.isBlank()) {
            return ToolResult.error("message 不能为空", ToolErr.INVALID_PARAM)
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

        @Suppress("UNCHECKED_CAST")
        val files = (params["files"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: emptyList()

        // Stage
        val addCmd = buildList {
            add("git")
            add("add")
            if (files.isEmpty()) add("-A") else addAll(files)
        }.toTypedArray()
        val addResult = GitCommandRunner.runCommand(repoDir, *addCmd, timeoutSec = TIMEOUT_SEC)
        if (!addResult.success) {
            val msg = addResult.stderr.ifBlank { addResult.stdout }.ifBlank { "(no output)" }
            return ToolResult.error(
                "git add 失败 (exitCode=${addResult.exitCode}): $msg",
                ToolErr.INTERNAL,
            )
        }

        // Commit
        val commitResult = GitCommandRunner.runCommand(
            repoDir, "git", "commit", "-m", message,
            timeoutSec = TIMEOUT_SEC,
        )
        val combined = commitResult.stderr.ifBlank { commitResult.stdout }
        if (!commitResult.success) {
            // 「nothing to commit」对调用方通常不是失败 —— 上游可能 add 后忘了已 commit 过。
            if (combined.contains("nothing to commit", ignoreCase = true) ||
                combined.contains("no changes", ignoreCase = true) ||
                combined.contains("nothing added", ignoreCase = true)
            ) {
                return ToolResult.success("无变更可提交: ${combined.trim().take(500)}")
            }
            val msg = combined.ifBlank { "(no output)" }
            return ToolResult.error(
                "git commit 失败 (exitCode=${commitResult.exitCode}): $msg",
                ToolErr.INTERNAL,
            )
        }
        return ToolResult.success(
            "已提交。" +
                if (combined.isNotBlank()) "\n${combined.trim().take(1000)}" else "",
        )
    }
}
