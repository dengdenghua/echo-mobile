package com.apk.claw.android.octopus_mobile.safety

/**
 * 权限模式 —— 区分"日常主力机"与"闲置/群控机"两种用途。
 *
 * - [APPROVAL]：审批模式（默认）。日常主力机，安全优先。
 *   高危工具调用需弹窗人工确认；来源闸门、路径沙箱、宪法法官全部开启。
 *
 * - [FULL_POWER]：完全权限模式。闲置/群控机，释放最大能力。
 *   高危工具自动放行；常规来源闸门、路径沙箱、宪法法官旁路。
 *   但 PrivacyScanner / AuditLog / CircuitBreaker 与「不可信来源 × 高危工具」的最小硬闸门不可关闭（防失控）。
 *   硬闸门把不可信来源（母体 WS / LAN HTTP / 主动规则）的高危调用降级为 CONFIRM：需用户显式打开
 *   「允许远程来源执行高危工具」才会无人值守放行，否则回落到本地审批窗（无人在场即超时拒绝）。
 *
 * 底层存储复用 [com.apk.claw.android.utils.KVUtils.isAdvancedAutomationMode]，
 * 向后兼容现有的"满血模式"开关。
 */
enum class PermissionMode {
    APPROVAL,
    FULL_POWER,
}
