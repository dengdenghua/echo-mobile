package com.apk.claw.android.agent

import com.apk.claw.android.tool.ToolResult

interface AgentCallback {
    /**
     * 新的一轮 Agent Loop 开始时的回调
     * @param round 当前轮数（从 1 开始）
     */
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult)
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int)
    fun onError(round: Int, error: Exception, totalTokens: Int)
    fun onSystemDialogBlocked(round: Int, totalTokens: Int)

    /**
     * refine-chat-interaction Task 6:Git 工具(git_commit / git_push / github_create_pr)
     * 成功执行后,DefaultAgentService 已把结构化结果(JSON 含 title/url/body)的 body 保存到
     * [com.apk.claw.android.ui.compose.screen.ChatStore](通过 [refId]),UI 层据此创建
     * TEXT 类 Artifact 卡片。
     *
     * @param round 当前轮数
     * @param toolName 工具名(git_commit / git_push / github_create_pr)
     * @param title Artifact 标题(如 `commit abc123` / `PR #42: feat: xxx`)
     * @param refId 已保存到 ChatStore 的 payload 引用键,UI 用 [ChatStore.loadPayload] 读取 body
     */
    fun onTextArtifact(round: Int, toolName: String, title: String, refId: String) {}
}
