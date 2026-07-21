package com.apk.claw.android.mcp

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 把 ToolRegistry 桥接为 MCP 工具源 —— 让 MCP 客户端(Claude Desktop / Cursor / 母本 Runtime)
 * 可直接调用 mobile 的 40+ Android 工具(tap/swipe/screenshot/run_shell 等)。
 *
 * - [listTools] 枚举 ToolRegistry 全部工具,转 MCP 协议的 McpToolInfo(name + description + inputSchema)
 * - [executeTool] 调 ToolRegistry.executeTool,返回 McpToolResult
 *
 * 所有调用必经 ToolRegistry 的 7 道闸门 + ApprovalGate 第 8 道闸门,确保 INV-T1。
 *
 * 由 ClawApplication.onCreate 注入到 McpServerBootstrap。
 */
class ToolRegistryMcpProvider(
    private val registry: ToolRegistry = ToolRegistry
) : McpToolRegistryProvider {

    /** 枚举 ToolRegistry 全部工具,转为 MCP 协议的 McpToolInfo。 */
    override fun listTools(): List<McpToolInfo> {
        return registry.getAllTools().map { tool -> tool.toMcpInfo() }
    }

    /** 执行 MCP tools/call 调用,经 ToolRegistry 全套闸门。 */
    override fun executeTool(name: String, args: Map<String, Any>): McpToolResult {
        val tool = registry.getTool(name)
            ?: return McpToolResult(false, "", "tool not found: $name")
        return try {
            val result: ToolResult = registry.executeTool(name, args)
            if (result.isSuccess) {
                McpToolResult(true, result.data ?: "", null)
            } else {
                McpToolResult(false, result.data ?: "", result.error ?: "execution failed")
            }
        } catch (e: Exception) {
            McpToolResult(false, "", "execution error: ${e.message}")
        }
    }

    /** 把 BaseTool 的参数 schema 转为 MCP inputSchema(JsonObject)。 */
    private fun BaseTool.toMcpInfo(): McpToolInfo {
        val inputSchema = JsonObject().apply {
            addProperty("type", "object")
            // 简化:用工具的参数列表构造 properties;详细 schema 由 LLM 调用方校验
            val properties = JsonObject()
            for (param in getParameters()) {
                val propObj = JsonObject().apply {
                    addProperty("type", mapParamType(param.type))
                    addProperty("description", param.description)
                    if (!param.isRequired) addProperty("optional", true)
                }
                properties.add(param.name, propObj)
            }
            add("properties", properties)
        }
        return McpToolInfo(
            name = getName(),
            description = getDescriptionEN(),
            inputSchema = inputSchema
        )
    }

    private fun mapParamType(t: String): String = when (t.lowercase()) {
        "string" -> "string"
        "int", "integer", "long" -> "integer"
        "float", "double" -> "number"
        "bool", "boolean" -> "boolean"
        "array", "list" -> "array"
        "object", "map" -> "object"
        else -> "string"
    }
}
