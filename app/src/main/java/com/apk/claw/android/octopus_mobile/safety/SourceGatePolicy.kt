package com.apk.claw.android.octopus_mobile.safety

/**
 * 来源闸门决策 —— 「不可信来源调用工具」时的处置动作。
 *
 * 不可信来源指母体 WS 的 tool/execute、LAN HTTP debug execute、以及由不可信触发源(通知/短信/
 * 屏幕文本)自动触发的主动规则。这些路径不经过 LLM agent，也没有人工确认，历史上可直接驱动最高权限工具。
 *
 * 抽成纯函数的原因：[com.apk.claw.android.tool.ToolRegistry.executeTool] 依赖 Android 运行时与
 * KVUtils，无法在纯 JVM 单测中覆盖；而「满血模式下不可信来源 × 高危工具仍必须过闸门」是一条必须被
 * 锁死的安全不变量，值得直接单测。
 *
 * 决策表（untrusted=不可信来源）：
 * | 策略 \ 风险 | HIGH | MEDIUM | 其他 |
 * |---|---|---|---|
 * | APPROVAL（trustAllSources=false） | policy.highRiskAction | policy.mediumRiskAction | null |
 * | FULL_POWER（trustAllSources=true） | **CONFIRM**（硬闸门，不可绕过） | null | null |
 *
 * 硬闸门在 [PermissionPolicy.untrustedHighRiskHardGate]=false 时才回到满血模式的旧语义（ALLOW），
 * 该开关仅供测试/极端场景，两种预设都保持 true。
 *
 * CONFIRM 的放行链路（见 ToolRegistry）：UI 逐次确认回调 →
 * `KVUtils.isRemoteHighRiskAllowed()`（群控机显式逃生舱）→ 本地审批窗（无人在场 → 超时拒绝，fail-closed）。
 */
object SourceGatePolicy {

    /**
     * 计算闸门动作。
     *
     * @param policy 当前权限策略
     * @param untrustedSource 调用是否来自不可信来源
     * @param riskLevel 工具风险等级（[ToolRiskPolicy.RISK_HIGH] / [ToolRiskPolicy.RISK_MEDIUM] / 其他）
     * @return null = 无需闸门（直接放行）；非 null = 该调用的处置动作
     */
    fun actionFor(
        policy: PermissionPolicy,
        untrustedSource: Boolean,
        riskLevel: String,
    ): PermissionPolicy.RiskAction? {
        if (!untrustedSource) return null

        val isHigh = riskLevel == ToolRiskPolicy.RISK_HIGH
        val isMedium = riskLevel == ToolRiskPolicy.RISK_MEDIUM
        if (!isHigh && !isMedium) return null

        // 不可绕过的最小硬闸门：不可信来源 × 高危工具，满血模式的 trustAllSources 对它无效。
        val hardGate = isHigh && policy.untrustedHighRiskHardGate

        if (isHigh) {
            // 满血模式 + 硬闸门 → 强制 CONFIRM（仍是可放行的，只是不再「默认无确认」）。
            if (hardGate && policy.trustAllSources) return PermissionPolicy.RiskAction.CONFIRM
            return policy.highRiskAction
        }

        // 中危：满血模式整表跳过；审批模式按 mediumRiskAction（默认 ALLOW，用户可收紧为 CONFIRM）。
        if (policy.trustAllSources) return null
        return policy.mediumRiskAction
    }
}
