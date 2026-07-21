package com.apk.claw.android.agent

/**
 * 审批结果。
 */
data class ApprovalDecision(
    val approved: Boolean,
    val reason: String? = null,
    val mode: PermissionMode
)

/**
 * 审批提供者接口。实现类:
 * - AutoApproveProvider(BYPASS_PERMISSIONS 模式)
 * - AutoDenyProvider(测试用)
 * - RuleBasedProvider(根据工具风险等级决定)
 * - UserConfirmProvider(DEFAULT 模式,弹 UI 等用户)
 */
interface ApprovalProvider {
    fun requestApproval(
        toolName: String,
        params: Map<String, Any>,
        riskLevel: ApprovalRisk,
        mode: PermissionMode
    ): ApprovalDecision
}

enum class ApprovalRisk(val level: Int) {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    companion object {
        fun fromString(s: String): ApprovalRisk =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: LOW

        fun fromInt(level: Int): ApprovalRisk =
            entries.firstOrNull { it.level == level } ?: LOW
    }
}

class AutoApproveProvider : ApprovalProvider {
    override fun requestApproval(
        toolName: String, params: Map<String, Any>, riskLevel: ApprovalRisk, mode: PermissionMode
    ) = ApprovalDecision(approved = true, reason = "auto approve", mode = mode)
}

class AutoDenyProvider : ApprovalProvider {
    override fun requestApproval(
        toolName: String, params: Map<String, Any>, riskLevel: ApprovalRisk, mode: PermissionMode
    ) = ApprovalDecision(approved = false, reason = "auto deny", mode = mode)
}

/**
 * 规则审批:PLAN 模式 → 拒绝所有写工具;ACCEPT_EDITS → 文件编辑自动通过;其他 → 调用 fallbackProvider
 */
class RuleBasedProvider(
    private val fallbackProvider: ApprovalProvider
) : ApprovalProvider {
    override fun requestApproval(
        toolName: String, params: Map<String, Any>, riskLevel: ApprovalRisk, mode: PermissionMode
    ): ApprovalDecision {
        if (mode == PermissionMode.PLAN && toolName in WRITE_TOOLS) {
            return ApprovalDecision(false, "plan mode blocks write tools", mode)
        }
        if (mode == PermissionMode.ACCEPT_EDITS && toolName in EDIT_TOOLS) {
            return ApprovalDecision(true, "accept_edits auto approve", mode)
        }
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            return ApprovalDecision(true, "bypass permissions", mode)
        }
        return fallbackProvider.requestApproval(toolName, params, riskLevel, mode)
    }

    companion object {
        // 写工具清单(不允许在 PLAN 模式下执行)
        val WRITE_TOOLS: Set<String> = setOf(
            "tap", "long_press", "swipe", "input_text", "system_key",
            "send_sms", "install_app", "file_delete", "file_write", "edit_file",
            "system_setting", "payment", "account_logout",
            "git_clone", "git_commit", "git_push", "github_create_pr"
        )
        // 文件编辑工具(ACCEPT_EDITS 自动通过)
        val EDIT_TOOLS: Set<String> = setOf("file_write", "edit_file", "git_commit", "git_push")
    }
}
