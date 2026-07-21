package com.apk.claw.android.mcp

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.utils.XLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * MCP 高危工具调用审批闸门 —— 在主线程弹 AlertDialog 等用户确认,60s 无响应自动拒绝。
 *
 * 实际 UI 弹窗需要 Activity Context;此处先实现基础逻辑,UI 集成可后续扩展。
 * 默认行为:
 * - 低/中危工具:自动通过
 * - 高危工具:60s 超时拒绝(等 UI 注入后改为弹窗)
 *
 * 由 ClawApplication.onCreate 注入到 McpServerBootstrap。
 */
class SystemApprovalGate(
    private val timeoutSec: Long = 60L,
    private val onPromptUser: ((toolName: String, args: Map<String, Any>) -> Boolean)? = null
) : McpApprovalGate {

    override fun requestApproval(toolName: String, args: Map<String, Any>): ApprovalResult {
        // 高危工具清单(与 JsonRpcDispatcher.HIGH_RISK_TOOLS 对齐)
        val isHighRisk = toolName in HIGH_RISK_TOOLS
        if (!isHighRisk) {
            return ApprovalResult(true, "low/medium risk auto approved")
        }

        val callback = onPromptUser
        if (callback == null) {
            // 无 UI 注入 → 自动拒绝高危工具(安全优先,fail-closed)
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

        /** 高危工具清单(由 MCP 服务端强制审批)。 */
        val HIGH_RISK_TOOLS = setOf(
            "send_sms", "install_app", "file_delete", "system_setting",
            "payment", "account_logout",
            "git_push",  // force push 可覆盖历史
            "github_create_pr"
        )
    }
}
