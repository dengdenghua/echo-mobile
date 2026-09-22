package com.apk.claw.android.mcp

import com.google.gson.JsonObject

/**
 * Tool registry provider —— 把 [com.apk.claw.android.tool.ToolRegistry](或任何工具后端)
 * 适配成 MCP server 可用的统一接口。
 *
 * 设计目的:让 [JsonRpcDispatcher] 与具体工具后端解耦。
 * 后端可以是:
 * - 项目内的 ToolRegistry(默认集成方向)
 * - 测试用 mock
 * - NoopMcpToolRegistryProvider(MCP server 启动但未接入工具时占位)
 *
 * 与 [com.apk.claw.android.server.routes.McpToolProvider] 是平行实现:
 * 那一套面向 ConfigServer /mcp 路由(走 Map);
 * 这一套走 JsonObject + 类型化 data class,供独立 MCP server 使用。
 */
interface McpToolRegistryProvider {
    /** 列出所有可暴露给 MCP 客户端的工具。 */
    fun listTools(): List<McpToolInfo>

    /**
     * 执行指定工具。
     *
     * @param name 工具名(已剥离 MCP 前缀,如 `mcp_xxx_yyy` → 原始名)
     * @param args 参数 map(已从 JsonObject 转为 Kotlin Map)
     * @return 执行结果(success=true 时 output 为输出文本;false 时 error 描述错误)
     */
    fun executeTool(name: String, args: Map<String, Any>): McpToolResult

    /**
     * 该 provider 的 executeTool 内部是否已经完成「风险判定 + 审批」。
     *
     * 默认 alse,此时 [JsonRpcDispatcher] 会在调用前叠加自己的 [McpApprovalGate]
     * 外层闸门(对不设防的第三方 provider 是必要的兜底)。
     *
     * 置为 	rue 表示 provider 自己就是策略权威 —— 例如 [ToolRegistryMcpProvider]
     * 会走 ToolRegistry 的完整闸门链(含不可信来源 × 高危硬闸门 + ApprovalFlow 审批 +
     * 审计)。此时若外层再加一道闸门,同一次调用会被要求审批两次;更糟的是外层闸门
     * 若没有绑定 UI 就会直接 fail-closed,使得该入口的高危工具**永远无法执行**。
     */
    val enforcesOwnPolicy: Boolean get() = false
}

data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
)

/**
 * 空实现:无 provider 绑定时使用。
 * - listTools 返回空列表(MCP 客户端将看不到任何工具)
 * - executeTool 永远返回失败
 */
class NoopMcpToolRegistryProvider : McpToolRegistryProvider {
    override fun listTools(): List<McpToolInfo> = emptyList()
    override fun executeTool(name: String, args: Map<String, Any>): McpToolResult =
        McpToolResult(false, "", "no provider")
}
