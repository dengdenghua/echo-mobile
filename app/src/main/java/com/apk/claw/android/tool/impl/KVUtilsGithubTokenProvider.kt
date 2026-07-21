package com.apk.claw.android.tool.impl

import com.apk.claw.android.utils.KVUtils

/**
 * 从 KVUtils.KEY_GITHUB_TOKEN 读取 GitHub Personal Access Token。
 *
 * 由 ClawApplication.onCreate 注入到 GithubCreatePrTool(通过 ToolRegistry 注册时)。
 * 默认 NoopGithubTokenProvider 返回 null —— 工具会返回 "no github token" 错误。
 */
class KVUtilsGithubTokenProvider : GithubTokenProvider {
    override fun getToken(): String? =
        KVUtils.getGithubToken().takeIf { it.isNotBlank() }
}
