package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.ui.compose.screen.ChatMessage
import com.apk.claw.android.ui.compose.screen.ChatStore
import org.json.JSONObject

/**
 * 解析 [SearchCodeTool] 的结构化 JSON 结果,生成 [ChatMessage.ArtifactKind.CODE_SNIPPET] Artifact。
 *
 * Task 5(refine-chat-interaction):search_code 工具结果从纯文本摘要改为结构化 JSON,
 * 顶层含 file/startLine/endLine/snippet 4 字段(top-1 命中)。本 helper 提取这 4 字段,
 * 把 snippet 存入 [ChatStore] 独立 payload 键(避免主消息列表臃肿),并返回一个
 * CODE_SNIPPET Artifact 供对话页渲染为可展开的代码卡片。
 *
 * 调用时机:Agent 工具结果回调链路中,当 toolName == "search_code" 且结果成功时调用。
 * 调用方持有会话 ID([sessionId])以保证 payload 引用键跨会话不冲突。
 *
 * @param toolResult  SearchCodeTool.execute() 的返回值,data 应为 JSON 字符串
 * @param sessionId   当前会话 ID,用于生成全局唯一的 payloadRef
 * @return CODE_SNIPPET Artifact;若结果非成功 / JSON 解析失败 / 无 4 字段则返回 null
 */
fun handleSearchCodeResult(
    toolResult: ToolResult,
    sessionId: String,
): ChatMessage.Artifact? {
    if (!toolResult.isSuccess) return null
    val data = toolResult.data ?: return null

    val json = try {
        JSONObject(data)
    } catch (e: Exception) {
        return null
    }

    // totalMatches=0 或缺 file 字段 → 无命中,不生成 Artifact
    val totalMatches = json.optInt("totalMatches", -1)
    if (totalMatches == 0) return null
    val file = json.optString("file", "")
    if (file.isEmpty()) return null

    val startLine = json.optInt("startLine", 0)
    val endLine = json.optInt("endLine", 0)
    val snippet = json.optString("snippet", "")
    if (snippet.isEmpty()) return null

    val refId = "${sessionId}_${System.currentTimeMillis()}_code"
    ChatStore.savePayload(refId, snippet)

    return ChatMessage.Artifact(
        kind = ChatMessage.ArtifactKind.CODE_SNIPPET,
        title = "$file:$startLine-$endLine",
        payloadRef = refId,
    )
}
