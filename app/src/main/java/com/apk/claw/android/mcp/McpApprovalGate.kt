package com.apk.claw.android.mcp

/**
 * 工具调用审批闸门。
 *
 * MCP server 暴露的工具中,有一部分属于"高危"——可能会改动系统状态、消耗资源、
 * 修改用户数据。这类工具在被 MCP 客户端调用前,默认应经过用户显式同意。
 *
 * 设计目的:把"是否允许执行"的决策从 [JsonRpcDispatcher] 中解耦。
 * 不同部署场景的 gate 实现差异巨大:
 * - AutoDenyApprovalGate:无 UI 时强制拒绝(默认,最安全)
 * - 未来可替换为弹窗 gate / 一次性预授权 gate / 自动允许 gate(本地调试用)
 *
 * 注意:gate 只对高危工具生效,只读工具(如 take_screenshot / list_devices)
 * 不需要经过审批。具体高危工具名单见 [JsonRpcDispatcher.HIGH_RISK_TOOLS]。
 */
interface McpApprovalGate {
    /**
     * 请求审批一次工具调用。
     *
     * @param toolName 工具名
     * @param args 工具参数(只读,不应被修改)
     * @return 审批结果(approved=true 允许执行;false 拒绝,reason 描述原因)
     */
    fun requestApproval(toolName: String, args: Map<String, Any>): ApprovalResult
}

data class ApprovalResult(
    val approved: Boolean,
    val reason: String? = null,
)

/**
 * 自动拒绝闸门:无 UI 绑定时使用,所有审批请求都返回 false。
 *
 * 这是 MCP server 启动后的默认 gate —— 在集成方未注入真正的 UI gate 前,
 * 所有高危工具调用都会被拒绝,确保安全默认。
 */
class AutoDenyApprovalGate : McpApprovalGate {
    override fun requestApproval(toolName: String, args: Map<String, Any>): ApprovalResult =
        ApprovalResult(false, "auto deny (no UI bound)")
}
