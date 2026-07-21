package com.apk.claw.android.agent

import com.apk.claw.android.utils.XLog

/**
 * 审批闸门 —— ToolRegistry.executeTool 第 8 道闸门(在 SafetyGate 之后、audit 之前)。
 *
 * 不变量 INV-T1:所有 touch 操作(tap/swipe/long_press/input_text)必经此闸门。
 *
 * 工作流:
 * 1. 读当前 PermissionMode(从 AgentConfig 注入)
 * 2. 读工具风险等级(从 ToolRiskPolicy 注入,或外部传)
 * 3. 委托 ApprovalProvider.requestApproval
 * 4. 返回 ApprovalDecision(approved=true 时放行,approved=false 时返回 PermissionDenied ToolResult)
 */
class ApprovalGate(
    private val provider: ApprovalProvider
) {
    fun check(
        toolName: String,
        params: Map<String, Any>,
        riskLevel: ApprovalRisk,
        mode: PermissionMode
    ): ApprovalDecision {
        val decision = try {
            provider.requestApproval(toolName, params, riskLevel, mode)
        } catch (e: Exception) {
            XLog.e(TAG, "approval provider threw, default deny: ${e.message}", e)
            ApprovalDecision(false, "approval error: ${e.message}", mode)
        }
        if (!decision.approved) {
            XLog.i(TAG, "tool '$toolName' denied: ${decision.reason} (mode=$mode, risk=$riskLevel)")
        }
        return decision
    }

    companion object {
        private const val TAG = "ApprovalGate"
    }
}
