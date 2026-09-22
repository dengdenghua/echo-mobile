package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * PermissionPolicy 测试 —— 两种模式预设的安全不变量（纯逻辑）。
 *
 * 锁定关键安全语义,防止误改预设把主力机变成"高危自动放行":
 *  - APPROVAL(默认/主力机):高危=CONFIRM、不信任所有来源、路径沙箱开、宪法法官开。
 *  - FULL_POWER(闲置/群控机):高危=ALLOW、信任所有来源、旁路法官。路径沙箱仍不可关。
 *  - 两模式都不可关:PrivacyScanner / AuditLog / CircuitBreaker / PathSandbox，
 *    以及「不可信来源 × 高危工具」的最小硬闸门(untrustedHighRiskHardGate，见 SourceGatePolicyTest)。
 */
class PermissionPolicyTest {

    @Test
    fun `APPROVAL preset is safety-first`() {
        val p = PermissionPolicy.APPROVAL
        assertEquals(PermissionMode.APPROVAL, p.mode)
        assertEquals(PermissionPolicy.RiskAction.CONFIRM, p.highRiskAction)
        assertEquals(PermissionPolicy.RiskAction.ALLOW, p.mediumRiskAction)
        assertFalse("主力机不应信任所有来源", p.trustAllSources)
        assertTrue(p.safetyGateEnabled)
        assertTrue(p.pathSandboxEnabled)
        assertEquals(3, p.maxConsecutiveFailures)
        assertEquals(5, p.maxNoProgressSteps)
    }

    @Test
    fun `FULL_POWER preset releases capability`() {
        val p = PermissionPolicy.FULL_POWER
        assertEquals(PermissionMode.FULL_POWER, p.mode)
        assertEquals(PermissionPolicy.RiskAction.ALLOW, p.highRiskAction)
        assertTrue(p.trustAllSources)
        assertFalse(p.safetyGateEnabled)
        assertTrue("路径沙箱不可关", p.pathSandboxEnabled)
        assertEquals(10, p.maxConsecutiveFailures)
        assertEquals(20, p.maxNoProgressSteps)
    }

    @Test
    fun `non-disableable guards stay on in both modes`() {
        for (p in listOf(PermissionPolicy.APPROVAL, PermissionPolicy.FULL_POWER)) {
            assertTrue("${p.mode}: privacyScanner 不可关", p.privacyScannerEnabled)
            assertTrue("${p.mode}: auditLog 不可关", p.auditLogEnabled)
            assertTrue("${p.mode}: circuitBreaker 不可关", p.circuitBreakerEnabled)
            assertTrue("${p.mode}: pathSandbox 不可关", p.pathSandboxEnabled)
            assertTrue("${p.mode}: 不可信来源高危硬闸门不可关", p.untrustedHighRiskHardGate)
        }
    }

    @Test
    fun `forMode maps to matching preset`() {
        assertEquals(PermissionPolicy.APPROVAL, PermissionPolicy.forMode(PermissionMode.APPROVAL))
        assertEquals(PermissionPolicy.FULL_POWER, PermissionPolicy.forMode(PermissionMode.FULL_POWER))
        assertEquals(PermissionMode.APPROVAL, PermissionPolicy.forMode(PermissionMode.APPROVAL).mode)
    }

    @Test
    fun `the two presets genuinely differ on high-risk handling`() {
        assertNotEquals(
            PermissionPolicy.APPROVAL.highRiskAction,
            PermissionPolicy.FULL_POWER.highRiskAction,
        )
    }
}
