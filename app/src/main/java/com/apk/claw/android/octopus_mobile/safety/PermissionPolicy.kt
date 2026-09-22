package com.apk.claw.android.octopus_mobile.safety

/**
 * 权限策略 —— 把散落在 KVUtils 的多个安全开关收敛为一个统一的策略对象。
 *
 * 由 [PermissionMode] 驱动，两种预设：
 * - [APPROVAL]：审批模式策略（日常主力机）
 * - [FULL_POWER]：完全权限策略（闲置/群控机）
 *
 * 不可关闭项（两种模式都 ON，防止设备失控）：
 * - [privacyScannerEnabled]：PII/Secret 扫描
 * - [auditLogEnabled]：审计日志
 * - [circuitBreakerEnabled]：断路器（防死循环烧钱）
 * - [pathSandboxEnabled]：/sdcard 路径沙箱（防越界访问系统/私有目录）
 * - [untrustedHighRiskHardGate]：不可信来源 × 高危工具的最小硬闸门（满血模式也必须保留）
 */
data class PermissionPolicy(
    val mode: PermissionMode,

    // —— SafetyGate 宪法法官 ——
    val safetyGateEnabled: Boolean,

    // —— 工具风险策略 ——
    val highRiskAction: RiskAction,
    val mediumRiskAction: RiskAction,

    // —— 来源闸门 ——
    val trustAllSources: Boolean,
    /**
     * 不可信来源 × 高危工具的「最小硬闸门」—— 满血模式（[trustAllSources]=true）也必须保留。
     *
     * 满血模式原本整体跳过来源闸门，使母体 WS / LAN HTTP / 主动规则能在无人工确认的情况下驱动
     * 任意高危工具（shell / 文件 / 账号操作）。开启本闸门后，这类调用在满血模式下仍被强制降级为
     * [RiskAction.CONFIRM]：由 [com.apk.claw.android.tool.ToolRegistry.highRiskConfirmer] 或
     * 用户显式开启的 [com.apk.claw.android.utils.KVUtils.isRemoteHighRiskAllowed] 放行，否则回落到
     * 本地审批窗（无人值守 → 超时拒绝，fail-closed）。
     *
     * 群控机逃生舱：设置页显式打开「允许远程来源执行高危工具」后自动放行，不影响无人值守批量任务。
     */
    val untrustedHighRiskHardGate: Boolean = true,

    // —— 护栏阈值 ——
    val maxConsecutiveFailures: Int,
    val maxNoProgressSteps: Int,

    // —— 路径沙箱（不可关闭，两种模式均 true）——
    val pathSandboxEnabled: Boolean = true,

    // —— 不可关闭项 ——
    val privacyScannerEnabled: Boolean = true,
    val auditLogEnabled: Boolean = true,
    val circuitBreakerEnabled: Boolean = true,
) {
    /** 高危工具的风险处置动作 */
    enum class RiskAction { ALLOW, CONFIRM, BLOCK }

    companion object {
        /** 审批模式策略：日常主力机，安全优先 */
        val APPROVAL = PermissionPolicy(
            mode = PermissionMode.APPROVAL,
            safetyGateEnabled = true,
            highRiskAction = RiskAction.CONFIRM,
            mediumRiskAction = RiskAction.ALLOW,
            trustAllSources = false,
            maxConsecutiveFailures = 3,
            maxNoProgressSteps = 5,
            pathSandboxEnabled = true,
        )

        /** 完全权限模式策略：闲置/群控机，释放最大能力（路径沙箱仍不可关闭） */
        val FULL_POWER = PermissionPolicy(
            mode = PermissionMode.FULL_POWER,
            safetyGateEnabled = false,
            highRiskAction = RiskAction.ALLOW,
            mediumRiskAction = RiskAction.ALLOW,
            trustAllSources = true,
            maxConsecutiveFailures = 10,
            maxNoProgressSteps = 20,
        )

        /** 根据模式获取预设策略 */
        fun forMode(mode: PermissionMode): PermissionPolicy = when (mode) {
            PermissionMode.APPROVAL -> APPROVAL
            PermissionMode.FULL_POWER -> FULL_POWER
        }
    }
}
