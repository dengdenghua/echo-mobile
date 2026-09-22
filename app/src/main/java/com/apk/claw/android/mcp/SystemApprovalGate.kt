package com.apk.claw.android.mcp

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.utils.XLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * MCP 高危工具调用审批闸门 —— 在主线程弹 AlertDialog 等用户确认,60s 无响应自动拒绝。
 *
 * 判定顺序(高危工具):
 *  1. 注入了 onPromptUser(由集成方提供 Activity Context 弹窗)→ 主线程弹窗,60s 无响应拒绝;
 *  2. 未注入 UI 但用户已打开"允许远程来源执行高危工具"→ 放行(闲置/群控机场景);
 *  3. 否则 fail-closed 拒绝。
 *
 * 现实约束:生产环境的 [ToolRegistryMcpProvider] 声明了 enforcesOwnPolicy=true,
 * 高危调用由 ToolRegistry 的 ApprovalFlow 弹窗裁决,本闸门不会参与;它只对
 * 不自带策略的第三方 provider 起兜底作用。
 *
 * 由 ClawApplication.onCreate 注入到 McpServerBootstrap。
 */
class SystemApprovalGate(
    private val timeoutSec: Long = 60L,
    private val onPromptUser: ((toolName: String, args: Map<String, Any>) -> Boolean)? = null
) : McpApprovalGate {

    override fun requestApproval(toolName: String, args: Map<String, Any>): ApprovalResult {
        // 高危判定统一以 ToolRiskPolicy 为准（含运行时 mcp_* 动态工具），避免多处名单漂移。
        val isHighRisk = ToolRiskPolicy.riskOf(toolName) == ToolRiskPolicy.RISK_HIGH
        if (!isHighRisk) {
            return ApprovalResult(true, "low/medium risk auto approved")
        }

        val callback = onPromptUser
        if (callback == null) {
            // 无 UI 注入时的判定顺序与 ToolRegistry 的 CONFIRM 分支保持一致:
            //  1) 用户已显式打开"允许远程来源执行高危工具"(闲置/群控机场景)→ 放行;
            //    没有这一步的话,那个开关对 MCP 入口是失效的。
            //  2) 否则 fail-closed 拒绝。
            if (com.apk.claw.android.utils.KVUtils.isRemoteHighRiskAllowed()) {
                return ApprovalResult(true, "remote high-risk explicitly allowed by user setting")
            }
            return ApprovalResult(false, "no UI gate bound — auto deny high-risk")
        }

        return try {
            // 主线程弹窗,阻塞等待用户响应,超时拒绝
            val future = CompletableFuture<Boolean>()
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    val approved = callback(toolName, args)
                    future.complete(approved)
                } catch (e: Exception) {
                    XLog.e(TAG, "approval callback error", e)
                    future.complete(false)
                }
            }
            val approved = future.get(timeoutSec, TimeUnit.SECONDS)
            ApprovalResult(approved, if (approved) "user approved" else "user denied")
        } catch (e: TimeoutException) {
            XLog.w(TAG, "approval timed out for '$toolName' — auto deny")
            ApprovalResult(false, "approval timed out")
        } catch (e: Exception) {
            XLog.e(TAG, "approval error", e)
            ApprovalResult(false, "approval error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SystemApprovalGate"

        /**
         * 高危工具清单 —— 别名转发到 [ToolRiskPolicy]（唯一权威来源），禁止在此另起名单。
         * 运行时 `mcp_*` 动态工具同样为 HIGH，但由前缀规则决定，不在本集合内。
         */
        val HIGH_RISK_TOOLS: Set<String> get() = ToolRiskPolicy.HIGH_RISK_TOOLS
    }
}
