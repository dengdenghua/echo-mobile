package com.apk.claw.android.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 2.0 分发器 —— MCP 协议核心逻辑。
 *
 * 纯逻辑组件:不依赖 Android / HTTP / 网络,接收 [JsonRpcRequest] 返回 [JsonRpcResponse]。
 * 让传输层(MCP server / stdio / SSE / WebSocket)与协议层彻底解耦,便于 JVM 单测。
 *
 * 实现的 MCP 原语:
 *  - [initialize]                  握手;返回协议版本 + 服务器能力 + serverInfo
 *  - [notifications/initialized]  握手确认通知(id=null,响应也为 null)
 *  - [tools/list]                  枚举 provider 中的工具
 *  - [tools/call]                  执行工具(高危工具走 ApprovalGate 审批)
 *  - [ping]                        心跳,返回空结果
 *  - 其他方法                       返回 -32601 METHOD_NOT_FOUND
 *
 * 审批超时:[CompletableFuture.orTimeout] 包装 [McpApprovalGate.requestApproval],
 * 60 秒未决定则视为拒绝(防止 UI 卡死导致 MCP 客户端永远挂起)。
 *
 * @param provider 工具后端适配器
 * @param approvalGate 高危工具审批闸门
 * @param serverName 服务器名(initialize 响应的 serverInfo.name)
 * @param serverVersion 服务器版本(initialize 响应的 serverInfo.version)
 */
class JsonRpcDispatcher(
    private val provider: McpToolRegistryProvider,
    private val approvalGate: McpApprovalGate,
    private val serverName: String = "octopus-mobile-mcp",
    private val serverVersion: String = "0.1.0",
) {

    companion object {
        /** 高危工具白名单:这些工具被调用前必须经过 ApprovalGate 审批。 */
        val HIGH_RISK_TOOLS: Set<String> = setOf(
            "send_sms",            // 发短信(消耗话费 / 诈骗风险)
            "install_app",         // 安装应用(任意 APK 可执行)
            "file_delete",         // 删除文件(不可逆)
            "system_setting",      // 改系统设置(可能锁死设备)
            "payment",             // 支付(直接财务风险)
            "account_logout",      // 账号登出(中断会话)
        )

        /** 审批超时时间(秒)。超过则视为拒绝。 */
        const val APPROVAL_TIMEOUT_SECONDS = 60L
    }

    /**
     * 分发一条 JSON-RPC 请求,返回响应。
     *
     * 注意:
     *  - 通知(id=null):返回的 [JsonRpcResponse.id] 也为 null,传输层应丢弃响应不发回客户端。
     *  - 异常:任何未预期错误都被捕获并转换为 -32603 INTERNAL_ERROR。
     */
    fun dispatch(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            when (request.method) {
                "initialize" -> handleInitialize(request)
                "notifications/initialized" -> JsonRpcResponse(id = null) // 通知无响应
                "ping" -> JsonRpcResponse(id = request.id, result = JsonObject())
                "tools/list" -> handleToolsList(request)
                "tools/call" -> handleToolsCall(request)
                else -> JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(
                        JsonRpcErrors.METHOD_NOT_FOUND,
                        "Method not found: ${request.method}",
                    ),
                )
            }
        } catch (e: Exception) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    JsonRpcErrors.INTERNAL_ERROR,
                    "Internal error: ${e.message}",
                ),
            )
        }
    }

    // ── initialize ──────────────────────────────────────────

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = JsonObject().apply {
            addProperty("protocolVersion", MCP_PROTOCOL_VERSION)
            add("capabilities", JsonObject().apply {
                // 仅声明 tools capability,目前不暴露 resources / prompts / logging
                add("tools", JsonObject())
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", serverName)
                addProperty("version", serverVersion)
            })
        }
        return JsonRpcResponse(id = request.id, result = result)
    }

    // ── tools/list ──────────────────────────────────────────

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val toolsArray = JsonArray().apply {
            provider.listTools().forEach { tool ->
                add(JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty("description", tool.description)
                    add("inputSchema", tool.inputSchema)
                })
            }
        }
        val result = JsonObject().apply { add("tools", toolsArray) }
        return JsonRpcResponse(id = request.id, result = result)
    }

    // ── tools/call ──────────────────────────────────────────

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params ?: JsonObject()
        val name = params.get("name")?.let { if (it.isJsonPrimitive) it.asString else null }
            ?: return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(JsonRpcErrors.INVALID_PARAMS, "Missing 'name' parameter"),
            )

        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        val argMap = jsonObjectToMap(args)

        // 高危工具 → 审批闸门
        if (name in HIGH_RISK_TOOLS) {
            val approval = try {
                CompletableFuture
                    .supplyAsync { approvalGate.requestApproval(name, argMap) }
                    .orTimeout(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get()
            } catch (e: Exception) {
                ApprovalResult(false, "approval timeout or error: ${e.message}")
            }
            if (!approval.approved) {
                val content = JsonObject().apply {
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty(
                                "text",
                                "Tool '$name' was denied by approval gate" +
                                    approval.reason?.let { ": $it" }.orEmpty(),
                            )
                        })
                    })
                    addProperty("isError", true)
                }
                return JsonRpcResponse(id = request.id, result = content)
            }
        }

        // 执行工具
        val toolResult = try {
            provider.executeTool(name, argMap)
        } catch (e: Exception) {
            McpToolResult(false, "", "exception: ${e.message}")
        }

        val text = if (toolResult.success) {
            toolResult.output.ifEmpty { "OK" }
        } else {
            "Error: ${toolResult.error ?: "tool '$name' failed"}"
        }

        val content = JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
            })
            addProperty("isError", !toolResult.success)
        }
        return JsonRpcResponse(id = request.id, result = content)
    }

    // ── 辅助 ────────────────────────────────────────────────

    /**
     * 把 JsonObject 转成 Kotlin Map<String, Any>。
     * 仅展开一层,不递归嵌套 object/array —— 工具后端自行解析嵌套结构。
     */
    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any> {
        val map = LinkedHashMap<String, Any>(obj.size())
        for ((key, value) in obj.entrySet()) {
            val mapped: Any? = when {
                value == null || value.isJsonNull -> null
                value.isJsonPrimitive -> {
                    val p = value.asJsonPrimitive
                    when {
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asNumber
                        p.isString -> p.asString
                        else -> p.asString
                    }
                }
                value.isJsonObject -> value.asJsonObject
                value.isJsonArray -> value.asJsonArray
                else -> value.toString()
            }
            if (mapped != null) {
                map[key] = mapped
            }
        }
        return map
    }
}
