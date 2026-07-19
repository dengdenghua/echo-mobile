package com.apk.claw.android.tool.mcp

import android.util.Log
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Server 管理器 —— 管理多个 MCP server 连接,动态注册/注销其工具到 ToolRegistry。
 *
 * 使用流程:
 *  1. [addServer] 添加 MCP server 配置(stdio 或 SSE)
 *  2. [connectServer] 连接 → 握手 → 发现工具 → 动态注册到 ToolRegistry(工具名前缀 `mcp_<serverId>_`)
 *  3. 工具被调用时 → [McpToolBridge.execute] → 转发到对应 MCP server → 返回结果
 *  4. [disconnectServer] → 断开 + 注销工具
 *
 * 工具命名:
 *  - MCP server "github" 的工具 "create_issue" → 注册为 `mcp_github_create_issue`
 *  - 前缀 `mcp_` 避免与内置工具冲突,serverId 隔离不同 server 的同名工具
 *  - Agent 在工具列表中看到的是带前缀的全名,描述里标注来源 server
 *
 * 配置持久化:由调用方(KVUtils)存储 server 列表,启动时 [restoreServers] 恢复。
 */
object McpManager {

    private const val TAG = "McpManager"
    internal const val TOOL_PREFIX = "mcp_"

    /** serverId → McpClient。 */
    private val clients = ConcurrentHashMap<String, McpClient>()

    /** 注册到 ToolRegistry 的工具名 → (serverId, mcpToolName)。 */
    private val toolMapping = ConcurrentHashMap<String, Pair<String, String>>()

    /** 连接状态监听器(供 UI 显示在线/离线)。 */
    @Volatile
    var onServerStatusChange: ((serverId: String, connected: Boolean, toolCount: Int) -> Unit)? = null

    /** 已配置的 server 列表(供 UI 显示)。 */
    fun listServers(): List<ServerInfo> = clients.keys.map { id ->
        val client = clients[id]!!
        ServerInfo(id, client.isConnected, client.discoveredTools.size)
    }

    data class ServerInfo(val id: String, val connected: Boolean, val toolCount: Int)

    /**
     * 添加并连接 MCP server。
     * @param serverId 唯一标识(仅 [a-zA-Z0-9_-],用作工具名前缀)
     * @param transport 传输配置
     * @return 连接结果(成功返回发现的工具数)
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun connectServer(
        serverId: String,
        transport: McpClient.Transport,
    ): Result<Int> {
        if (!serverId.matches(Regex("[a-zA-Z0-9_-]+"))) {
            return Result.failure(IllegalArgumentException("serverId 仅允许 [a-zA-Z0-9_-]"))
        }
        if (clients.containsKey(serverId)) {
            return Result.failure(IllegalArgumentException("MCP server [$serverId] 已存在,请先断开"))
        }

        val client = McpClient(serverId, transport)
        client.onStatusChange = { connected ->
            if (!connected) {
                // 连接断开 → 注销工具
                unregisterServerTools(serverId)
                onServerStatusChange?.invoke(serverId, false, 0)
            }
        }

        val result = client.connect()
        return result.fold(
            onSuccess = { tools ->
                clients[serverId] = client
                // 动态注册工具到 ToolRegistry
                registerServerTools(serverId, client, tools)
                onServerStatusChange?.invoke(serverId, true, tools.size)
                Log.i(TAG, "[$serverId] Connected with ${tools.size} tools")
                Result.success(tools.size)
            },
            onFailure = { e ->
                client.disconnect()
                Result.failure(e)
            },
        )
    }

    /** 断开并移除 MCP server。 */
    fun disconnectServer(serverId: String) {
        clients.remove(serverId)?.let { client ->
            unregisterServerTools(serverId)
            client.disconnect()
            Log.i(TAG, "[$serverId] Disconnected")
        }
    }

    /** 断开所有 server(App 退出时调用)。 */
    fun disconnectAll() {
        clients.keys.toList().forEach { disconnectServer(it) }
    }

    // ── 工具动态注册 ────────────────────────────────────────────────────

    /**
     * 把 MCP server 的工具注册到 ToolRegistry。
     * 工具名格式:`mcp_<serverId>_<toolName>`,描述标注来源。
     */
    private fun registerServerTools(
        serverId: String,
        client: McpClient,
        tools: List<McpClient.McpToolInfo>,
    ) {
        for (tool in tools) {
            val fullToolName = "$TOOL_PREFIX${serverId}_${tool.name}"
            try {
                val bridge = McpToolBridge(serverId, tool.name, client)
                ToolRegistry.registerPluginTool(bridge)
                toolMapping[fullToolName] = serverId to tool.name
            } catch (e: Exception) {
                Log.w(TAG, "[$serverId] Failed to register tool ${tool.name}: ${e.message}")
            }
        }
    }

    /** 注销 server 的所有工具。 */
    private fun unregisterServerTools(serverId: String) {
        val prefix = "$TOOL_PREFIX${serverId}_"
        toolMapping.keys.filter { it.startsWith(prefix) }.forEach { toolName ->
            ToolRegistry.unregister(toolName)
            toolMapping.remove(toolName)
        }
    }
}

/**
 * MCP 工具桥接 —— 把 ToolRegistry 的工具调用转发到 MCP server。
 * 每个 MCP server 的每个工具注册为一个 McpToolBridge 实例。
 */
class McpToolBridge(
    private val serverId: String,
    private val mcpToolName: String,
    private val client: McpClient,
) : BaseTool() {

    override fun getName() = "${McpManager.TOOL_PREFIX}${serverId}_${mcpToolName}"

    override fun getDisplayName() = "[MCP/$serverId] $mcpToolName"

    override fun getParameters(): List<ToolParameter> {
        // MCP 工具的参数 schema 是 JSON Schema,这里简化为接受任意 key-value
        // 完整实现应解析 inputSchema 并映射到 ToolParameter 列表
        return listOf(
            ToolParameter(
                "arguments",
                "object",
                "Tool arguments as JSON object. Refer to the MCP tool's inputSchema for required fields.",
                false,
            ),
        )
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "UNCHECKED_CAST")
    override fun execute(params: Map<String, Any>): ToolResult {
        val arguments = params["arguments"]
        val argMap: Map<String, Any> = when (arguments) {
            is Map<*, *> -> arguments.entries.associate { it.key.toString() to (it.value ?: "") }
            is String -> try {
                com.google.gson.Gson().fromJson(arguments, Map::class.java) as Map<String, Any>
            } catch (_: Exception) {
                return ToolResult.error("arguments 不是合法 JSON", ToolErr.INVALID_PARAM)
            }
            null -> emptyMap()
            else -> mapOf("value" to arguments.toString())
        }

        return try {
            client.callTool(mcpToolName, argMap)
        } catch (e: Exception) {
            ToolResult.error(
                "MCP 工具执行异常 [$serverId/$mcpToolName]: ${e.message}",
                ToolErr.INTERNAL,
            )
        }
    }

    override fun getDescriptionEN() = "MCP tool '$mcpToolName' from server '$serverId'. " +
        client.discoveredTools[mcpToolName]?.description?.let { "Description: $it" }
        ?: "No description available."

    override fun getDescriptionCN() = "MCP 工具 '$mcpToolName'(来自 server '$serverId')。" +
        client.discoveredTools[mcpToolName]?.description?.let { " 说明: $it" }
        ?: " 无描述。"
}
