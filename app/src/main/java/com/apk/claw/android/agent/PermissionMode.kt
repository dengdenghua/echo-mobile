package com.apk.claw.android.agent

/**
 * 4 档权限模式(借鉴母本 octopus-agent runtime/safety/approval/approval_gate.py)。
 *
 * - DEFAULT:高危工具需用户确认(默认)
 * - ACCEPT_EDITS:文件编辑(file_write/edit_file)自动通过,其他高危仍需确认
 * - BYPASS_PERMISSIONS:全部自动通过(需用户显式开启,记录到审计)
 * - PLAN:只读 + 出方案,禁止执行写工具(tap/swipe/file_write/send_sms 等)
 */
enum class PermissionMode(val displayName: String) {
    DEFAULT("默认(高危需确认)"),
    ACCEPT_EDITS("自动接受编辑"),
    BYPASS_PERMISSIONS("跳过所有权限"),
    PLAN("规划模式(只读)");

    companion object {
        fun fromName(name: String?): PermissionMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
