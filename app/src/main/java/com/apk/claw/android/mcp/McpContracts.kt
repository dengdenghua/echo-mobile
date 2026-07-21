package com.apk.claw.android.mcp

import com.google.gson.JsonObject

/**
 * MCP / JSON-RPC 2.0 协议数据契约。
 *
 * 本文件只持有纯数据类与常量,无 Android / HTTP 依赖,可在 JVM 单测中直接使用。
 * 与 [com.apk.claw.android.server.routes.McpServerCore] 中那一套类是不同实现:
 * - McpServerCore 走 Map<String, Any> 风格,集成进 ConfigServer 的 /mcp 路由;
 * - 本文件走 JsonObject + 类型化 data class 风格,服务独立 McpServer(端口 9528)。
 */

data class JsonRpcRequest(
    val jsonrpc: String,
    val id: Any?,          // 可能是 String 或 Int 或 null(notification)
    val method: String,
    val params: JsonObject?,
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any?,
    val result: JsonObject? = null,
    val error: JsonRpcError? = null,
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonObject? = null,
)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

// JSON-RPC 错误码
object JsonRpcErrors {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // MCP 自定义错误码
    const val INITIALIZE_REQUIRED = -32002
}

// MCP 协议版本
const val MCP_PROTOCOL_VERSION = "2024-11-05"
