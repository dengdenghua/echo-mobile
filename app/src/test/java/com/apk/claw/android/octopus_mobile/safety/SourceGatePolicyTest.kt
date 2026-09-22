package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源闸门决策单测 —— 重点锁定「满血模式的不可绕过硬闸门」安全不变量。
 *
 * 背景:修复前 FULL_POWER(trustAllSources=true) 会整体跳过来源闸门,母体 WS / LAN HTTP / 主动规则
 * 可在无人工确认的情况下静默执行任意高危工具(shell/文件/账号)。现在这类调用必须至少走一次 CONFIRM。
 */
class SourceGatePolicyTest {

    private val approval = PermissionPolicy.APPROVAL
    private val fullPower = PermissionPolicy.FULL_POWER

    @Test
    fun `full power still gates untrusted high risk tools`() {
        assertEquals(
            PermissionPolicy.RiskAction.CONFIRM,
            SourceGatePolicy.actionFor(fullPower, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_HIGH),
        )
    }

    @Test
    fun `full power does not gate trusted high risk tools`() {
        // 本机用户自己发起的调用不受来源闸门约束(满血模式语义保持不变)
        assertNull(SourceGatePolicy.actionFor(fullPower, untrustedSource = false, riskLevel = ToolRiskPolicy.RISK_HIGH))
    }

    @Test
    fun `full power does not gate untrusted medium risk tools`() {
        assertNull(SourceGatePolicy.actionFor(fullPower, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_MEDIUM))
    }

    @Test
    fun `full power does not gate untrusted low risk tools`() {
        assertNull(SourceGatePolicy.actionFor(fullPower, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_LOW))
    }

    @Test
    fun `approval mode confirms untrusted high risk tools`() {
        assertEquals(
            PermissionPolicy.RiskAction.CONFIRM,
            SourceGatePolicy.actionFor(approval, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_HIGH),
        )
    }

    @Test
    fun `approval mode allows untrusted medium risk tools by default`() {
        assertEquals(
            PermissionPolicy.RiskAction.ALLOW,
            SourceGatePolicy.actionFor(approval, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_MEDIUM),
        )
    }

    @Test
    fun `approval mode ignores trusted calls`() {
        assertNull(SourceGatePolicy.actionFor(approval, untrustedSource = false, riskLevel = ToolRiskPolicy.RISK_HIGH))
        assertNull(SourceGatePolicy.actionFor(approval, untrustedSource = false, riskLevel = ToolRiskPolicy.RISK_MEDIUM))
    }

    @Test
    fun `approval mode honours tightened actions`() {
        val tightened = approval.copy(
            highRiskAction = PermissionPolicy.RiskAction.BLOCK,
            mediumRiskAction = PermissionPolicy.RiskAction.CONFIRM,
        )
        assertEquals(
            PermissionPolicy.RiskAction.BLOCK,
            SourceGatePolicy.actionFor(tightened, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_HIGH),
        )
        assertEquals(
            PermissionPolicy.RiskAction.CONFIRM,
            SourceGatePolicy.actionFor(tightened, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_MEDIUM),
        )
    }

    @Test
    fun `hard gate off restores legacy full power behaviour`() {
        // 仅用于锁定开关语义:关掉硬闸门后满血模式回到「自动放行」。两种预设都必须保持 true。
        val legacy = fullPower.copy(untrustedHighRiskHardGate = false)
        assertEquals(
            PermissionPolicy.RiskAction.ALLOW,
            SourceGatePolicy.actionFor(legacy, untrustedSource = true, riskLevel = ToolRiskPolicy.RISK_HIGH),
        )
    }

    @Test
    fun `both presets keep the hard gate on`() {
        assertTrue(approval.untrustedHighRiskHardGate)
        assertTrue(fullPower.untrustedHighRiskHardGate)
    }

    @Test
    fun `unclosable safety switches stay on in both presets`() {
        for (p in listOf(approval, fullPower)) {
            assertTrue(p.privacyScannerEnabled)
            assertTrue(p.auditLogEnabled)
            assertTrue(p.circuitBreakerEnabled)
            assertTrue(p.pathSandboxEnabled)
        }
    }
}