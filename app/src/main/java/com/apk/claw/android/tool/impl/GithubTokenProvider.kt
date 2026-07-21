package com.apk.claw.android.tool.impl

/**
 * GitHub 访问令牌提供者契约。
 *
 * 设计动机:[GithubCreatePrTool] 需要一个 GitHub Personal Access Token 才能调 GitHub
 * REST API 创建 PR。但工具本身不应该直接 import [com.apk.claw.android.utils.KVUtils]
 * (会形成「工具 → 设置存储」的硬依赖,既不利于单测,也违反工具层与存储层的解耦),
 * 故声明此接口,由上层(SettingsActivity / ToolRegistry 初始化)注入具体实现。
 *
 * 约定:
 *  - 返回非空 token:工具用它作为 Authorization: token <...>。
 *  - 返回 null:工具直接返回 PERMISSION 错误,提示用户在设置中配置 github_token。
 *  - 实现方可缓存读取结果,避免每次工具调用都过一层 MMKV/SharedPreferences。
 */
interface GithubTokenProvider {

    /** 返回当前用户的 GitHub Personal Access Token;未配置返回 null。 */
    fun getToken(): String?
}

/**
 * 空实现 —— 永远返回 null。
 *
 * 用于:
 *  - 默认场景(用户未配置 token):工具直接报 PERMISSION 错误,指引去设置页填 token。
 *  - 单元测试:不依赖任何 Android Context / MMKV 即可实例化 [GithubCreatePrTool]。
 */
class NoopGithubTokenProvider : GithubTokenProvider {
    override fun getToken(): String? = null
}
