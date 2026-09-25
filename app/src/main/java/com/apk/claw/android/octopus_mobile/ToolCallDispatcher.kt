package com.apk.claw.android.octopus_mobile

import android.util.Log
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 方案 F · 工具调用调度器.
 *
 * 接收 octopus-agent Runtime 下发的 tool/execute 请求，
 * 路由到本地 ToolRegistry 执行，并将结果回传给 Runtime.
 *
 * 调用链：
 *   Runtime → WebSocket → OctopusMobileClient.onMessage
 *                            ↓
 *                       handleIncomingMessage 解析 tool/execute
 *                            ↓
 *                       ToolCallDispatcher.dispatch(call)
 *                            ↓
 *                       ToolRegistry.executeTool(shortName, args)
 *                            ↓
 *                       OctopusMobileClient.sendToolResult(callId, ...)
 *
 * 设计要点：
 *  - 异步执行：不阻塞 WebSocket 读循环
 *  - 前缀剥离：Runtime 下发的是 "android.tap"，ToolRegistry 用 "tap"
 *  - 错误映射：本地 error → RPC ErrorCode（对齐 octopus-agent ErrorCodes）
 *  - 计时上报：durationMs 让母体知道工具执行耗时
 */
class ToolCallDispatcher(
    private val client: OctopusMobileClient,
    private val toolRegistry: ToolRegistry = ToolRegistry.getInstance()
) {
    private val tag = "ToolCallDispatcher"
    private var scope = newScope()

    /**
     * 启动监听 —— 把 OctopusMobileClient.onToolExecute 接到本调度器.
     */
    fun start() {
        if (!scope.isActive) scope = newScope()
        client.onToolExecute = { call -> dispatch(call) }
        Log.i(tag, "ToolCallDispatcher started")
    }

    /**
     * 停止监听.
     */
    fun stop() {
        client.onToolExecute = null
        scope.cancel()
        Log.i(tag, "ToolCallDispatcher stopped")
    }

    /**
     * 调度一个工具调用.
     *
     * 立即返回（异步执行），结果通过 WebSocket 异步回传.
     */
    fun dispatch(call: ToolCall) {
        if (!scope.isActive) {
            Log.w(tag, "dispatch rejected after dispatcher stopped: ${call.id}")
            client.sendToolResult(
                callId = call.id,
                success = false,
                data = null,
                error = "Tool dispatcher is stopped",
                errorCode = ErrorCodes.DEVICE_OFFLINE,
            )
            return
        }
        scope.launch {
            val startTs = System.currentTimeMillis()
            val shortName = stripAndroidPrefix(call.name)
            val result = executeLocal(shortName, call.args)
            val durationMs = (System.currentTimeMillis() - startTs).toInt()

            try {
                if (result.isSuccess) {
                    // 截断超大返回（避免 WebSocket 帧超限）
                    val data = if (shortName == "mirror_frame") {
                        require((result.data?.length ?: 0) <= MAX_MIRROR_CHARS) {
                            "Mirror frame exceeds transport limit"
                        }
                        result.data
                    } else truncateResultData(result.data)
                    client.sendToolResult(
                        callId = call.id,
                        success = true,
                        data = data,
                        error = null,
                    )
                } else {
                    val code = mapErrorToCode(shortName, result.error)
                    client.sendToolResult(
                        callId = call.id,
                        success = false,
                        data = null,
                        error = result.error,
                        errorCode = code,
                    )
                }
                Log.d(
                    tag,
                    "dispatched ${call.name} → ${shortName} in ${durationMs}ms (success=${result.isSuccess})",
                )
            } catch (e: Exception) {
                Log.w(tag, "sendToolResult failed for ${call.id}: ${e.message}")
            }
        }
    }

    /**
     * 同步执行本地工具（便于测试和复用）.
     * 接受全名（"android.finish"）或短名（"finish"），自动剥前缀。
     */
    fun executeLocal(name: String, args: Map<String, Any?>): ToolResult {
        val shortName = stripAndroidPrefix(name)
        // 转换 Any? 到 Any（ToolRegistry.executeTool 需要非空 Map）
        val cleanArgs = args.filterValues { it != null }.mapValues { it.value!! }
        // 远程母体下发的 tool/execute 标记为"不可信来源"：高危工具默认被来源闸门拦截(R2/R3)。
        return ToolRegistry.withUntrustedSource {
            toolRegistry.executeTool(shortName, cleanArgs)
        }
    }

    /**
     * 工具名剥前缀 —— "android.tap" → "tap"，无前缀原样返回.
     */
    private fun stripAndroidPrefix(name: String): String {
        return if (name.startsWith("android.")) name.removePrefix("android.") else name
    }

    /**
     * 本地错误信息 → 远程 ErrorCode 映射.
     *
     * 对齐 octopus-agent/runtime/tentacle/apks/error_codes.py.
     */
    private fun mapErrorToCode(toolName: String, error: String?): Int {
        val msg = (error ?: "").lowercase()
        return when {
            msg.contains("not found") || msg.contains("unknown tool") -> ErrorCodes.TOOL_NOT_FOUND
            msg.contains("out of") && msg.contains("bound") -> ErrorCodes.COORDINATE_OUT_OF_BOUNDS
            msg.contains("timeout") || msg.contains("timed out") -> ErrorCodes.TOOL_TIMEOUT
            msg.contains("permission") || msg.contains("denied") -> ErrorCodes.PERMISSION_DENIED
            msg.contains("locked") -> ErrorCodes.DEVICE_LOCKED
            msg.contains("offline") -> ErrorCodes.DEVICE_OFFLINE
            msg.contains("app not found") || msg.contains("not installed") -> ErrorCodes.APP_NOT_FOUND
            else -> -32603  // Internal error (JSON-RPC 标准)
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun truncateResultData(data: String?): String? =
        data?.let { if (it.length > MAX_RESULT_CHARS) it.substring(0, MAX_RESULT_CHARS) + TRUNCATED_SUFFIX else it }

    companion object {
        private const val MAX_MIRROR_CHARS = 500_000
        internal const val MAX_RESULT_CHARS = 32_000
        internal const val TRUNCATED_SUFFIX = "...(truncated)"
    }
}
