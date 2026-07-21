package com.apk.claw.android.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * ApprovalGate 单元测试 —— 验证 4 档权限模式的拦截/放行规则。
 *
 * 测试覆盖:
 * - PLAN 模式:写工具被拦,只读工具走 fallback
 * - ACCEPT_EDITS 模式:文件编辑工具自动放行
 * - BYPASS_PERMISSIONS 模式:全部放行
 * - DEFAULT 模式:委托 fallback provider
 * - Provider 异常时默认拒绝(fail-safe)
 */
class ApprovalGateTest {

    @Test
    fun `plan mode blocks write tools`() {
        val gate = ApprovalGate(RuleBasedProvider(AutoDenyProvider()))
        val decision = gate.check("tap", emptyMap(), ApprovalRisk.HIGH, PermissionMode.PLAN)
        assertFalse(decision.approved)
    }

    @Test
    fun `plan mode allows read tools`() {
        // 用 AutoDenyProvider 作 fallback:RuleBasedProvider 不拦 read tools,会调 fallback → 拒绝。
        // 这说明 PLAN 模式不会主动拦 read tools,只拦 WRITE_TOOLS 里的工具。
        val gate = ApprovalGate(RuleBasedProvider(AutoDenyProvider()))
        val decision = gate.check("screenshot", emptyMap(), ApprovalRisk.LOW, PermissionMode.PLAN)
        assertFalse(decision.approved)

        // 改用 AutoApproveProvider 作为 fallback:read tools 应被放行
        val gate2 = ApprovalGate(RuleBasedProvider(AutoApproveProvider()))
        val decision2 = gate2.check("screenshot", emptyMap(), ApprovalRisk.LOW, PermissionMode.PLAN)
        assertTrue(decision2.approved)
    }

    @Test
    fun `accept_edits auto approves file_write`() {
        val gate = ApprovalGate(RuleBasedProvider(AutoDenyProvider()))
        val decision = gate.check("file_write", emptyMap(), ApprovalRisk.MEDIUM, PermissionMode.ACCEPT_EDITS)
        assertTrue(decision.approved)
    }

    @Test
    fun `bypass_permissions approves all`() {
        val gate = ApprovalGate(RuleBasedProvider(AutoDenyProvider()))
        val decision = gate.check("send_sms", emptyMap(), ApprovalRisk.HIGH, PermissionMode.BYPASS_PERMISSIONS)
        assertTrue(decision.approved)
    }

    @Test
    fun `default mode delegates to fallback`() {
        val gate = ApprovalGate(RuleBasedProvider(AutoApproveProvider()))
        val decision = gate.check("send_sms", emptyMap(), ApprovalRisk.HIGH, PermissionMode.DEFAULT)
        assertTrue(decision.approved)  // AutoApprove fallback
    }

    @Test
    fun `provider exception defaults deny`() {
        val gate = ApprovalGate(object : ApprovalProvider {
            override fun requestApproval(
                toolName: String, params: Map<String, Any>, riskLevel: ApprovalRisk, mode: PermissionMode
            ) = throw RuntimeException("oops")
        })
        val decision = gate.check("tap", emptyMap(), ApprovalRisk.HIGH, PermissionMode.DEFAULT)
        assertFalse(decision.approved)
    }
}
