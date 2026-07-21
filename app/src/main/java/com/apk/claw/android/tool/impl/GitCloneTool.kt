package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.io.File

/**
 * git_clone —— 克隆远程仓库到本地路径。
 *
 * 行为:
 *  - 在 shell 中执行 `git clone -b <branch> <url> <path>`,工作目录为 [path] 的父目录。
 *  - 超时 60s,超时强杀进程并返回失败。
 *  - 目标目录已存在且非空时直接拒绝,避免覆盖用户既有数据。
 *
 * 风险等级:MEDIUM(写入外部仓库,可能落敏感代码到本地磁盘)。需在 [ToolRiskPolicy]
 * 中归类为 MEDIUM 后才走审计/来源闸门 —— 见集成清单。
 */
class GitCloneTool : BaseTool() {

    companion object {
        private const val DEFAULT_BRANCH = "main"
        private const val TIMEOUT_SEC = 60L
    }

    override fun getName(): String = "git_clone"

    override fun getDescriptionEN(): String =
        "Clone a git repository to local path. Executed in shell environment."

    override fun getDescriptionCN(): String =
        "克隆 Git 仓库到本地路径,在 shell 环境执行。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "url",
            "string",
            "Repository URL to clone (HTTPS or SSH, e.g. https://github.com/foo/bar.git).",
            true,
        ),
        ToolParameter(
            "path",
            "string",
            "Local directory path to clone into. Must not exist or be empty.",
            true,
        ),
        ToolParameter(
            "branch",
            "string",
            "Branch name to checkout after clone. Default: $DEFAULT_BRANCH.",
            false,
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        val url = requireString(params, "url").trim()
        val path = requireString(params, "path").trim()
        val branch = optionalString(params, "branch", DEFAULT_BRANCH).trim().ifEmpty { DEFAULT_BRANCH }

        if (url.isEmpty()) {
            return ToolResult.error("url 不能为空", ToolErr.INVALID_PARAM)
        }
        if (path.isEmpty()) {
            return ToolResult.error("path 不能为空", ToolErr.INVALID_PARAM)
        }

        val target = File(path)
        val parent = target.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) {
            return ToolResult.error("无法创建父目录: ${parent.absolutePath}", ToolErr.INTERNAL)
        }
        if (target.exists() && (target.list()?.isNotEmpty() == true)) {
            return ToolResult.error(
                "目标目录已存在且非空: ${target.absolutePath}",
                ToolErr.INVALID_PARAM,
            )
        }

        val result = GitCommandRunner.runCommand(
            parent,
            "git", "clone", "-b", branch, url, target.absolutePath,
            timeoutSec = TIMEOUT_SEC,
        )
        if (!result.success) {
            val msg = result.stderr.ifBlank { result.stdout }.ifBlank { "(no output)" }
            return ToolResult.error(
                "git clone 失败 (exitCode=${result.exitCode}): $msg",
                ToolErr.INTERNAL,
            )
        }
        val tail = result.stderr.ifBlank { result.stdout }
        return ToolResult.success(
            "已克隆仓库到 ${target.absolutePath}" +
                if (tail.isNotBlank()) "\n$tail" else "",
        )
    }
}
