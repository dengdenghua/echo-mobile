package com.apk.claw.android.tool.mcp

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.apk.claw.android.octopus_mobile.safety.McpToolDescriptorPolicy
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
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
 *
 * 安全(外部 server 不可信,见 [McpToolDescriptorPolicy]):
 *  - 工具名 / inputSchema 逐条准入,不过门槛的工具**不注册**(fail-closed)
 *  - 注册名跨 server 冲突时拒绝后来的注册者,不静默覆盖别人的工具
 *  - 注销按 [toolMapping] 的归属 server 精确比对,不用前缀匹配(前缀会误伤 mcp_a_ / mcp_a_b_)
 *  - 风险等级不接受 server 自报,统一走 [ToolRiskPolicy](`mcp_` 前缀 → HIGH)
 *  - 描述经隐私扫描 + 注入话术中和 + 来源围栏后才交给 LLM
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
                // 动态注册工具到 ToolRegistry(未过准入的工具被跳过,故按"实际注册数"上报)
                val registered = registerServerTools(serverId, client, tools)
                onServerStatusChange?.invoke(serverId, true, registered.size)
                Log.i(
                    TAG,
                    "[$serverId] Connected with ${tools.size} tools (registered ${registered.size})",
                )
                Result.success(registered.size)
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
     * 把 MCP server 的工具注册到 ToolRegistry(工具名 `mcp_<serverId>_<toolName>`)。
     *
     * 准入一律 fail-closed,逐条判定后再注册;被判定的工具**直接跳过**,不注册半个工具:
     *  1. 工具名非法(空/超长/字符集外)→ 跳过(见 [McpToolDescriptorPolicy.validateToolName])
     *  2. inputSchema 非法(超大/过深/循环或外部 `$ref`/保留字段)→ 跳过
     *  3. 注册名已被**别的 server** 占用 → 拒绝后来的注册者,不静默覆盖
     *
     * 第 3 条针对的是分隔符歧义:server "a" 的 `b_c` 与 server "a_b" 的 `c` 会拼出同一个
     * `mcp_a_b_c`。若放任覆盖,后来者就能顶替前者的工具实现(且前者仍未断开)。
     *
     * 风险等级不由 MCP server 自报,统一走 [ToolRiskPolicy](`mcp_` 前缀 → HIGH)。
     *
     * @return 实际注册成功的工具全名集合
     */
    internal fun registerServerTools(
        serverId: String,
        client: McpClient,
        tools: List<McpClient.McpToolInfo>,
    ): Set<String> {
        val registered = linkedSetOf<String>()
        for (tool in tools) {
            val nameReject = McpToolDescriptorPolicy.validateToolName(tool.name)
            if (nameReject != null) {
                Log.w(TAG, "[$serverId] 拒绝 MCP 工具名 '${tool.name}':$nameReject")
                continue
            }
            val schemaReject = McpToolDescriptorPolicy.validateSchema(tool.inputSchema)
            if (schemaReject != null) {
                Log.w(TAG, "[$serverId] 拒绝 MCP 工具 '${tool.name}' 的 inputSchema:$schemaReject")
                continue
            }
            val fullToolName = "$TOOL_PREFIX${serverId}_${tool.name}"
            val owner = toolMapping[fullToolName]
            if (owner != null && owner.first != serverId) {
                Log.w(TAG, "拒绝 MCP 工具名冲突 '$fullToolName':已被 server '${owner.first}' 占用")
                continue
            }
            try {
                ToolRegistry.registerPluginTool(McpToolBridge(serverId, tool.name, client))
                toolMapping[fullToolName] = serverId to tool.name
                registered += fullToolName
            } catch (e: Exception) {
                Log.w(TAG, "[$serverId] Failed to register tool ${tool.name}: ${e.message}")
            }
        }
        return registered
    }

    /**
     * 注销 server 的所有工具 —— 按 [toolMapping] 里记录的**归属 server** 精确比对,
     * **不做前缀匹配**。
     *
     * 前缀匹配会误伤:`mcp_a_` 是 `mcp_a_b_c` 的前缀,注销 server "a" 时会连带删掉
     * server "a_b" 的工具。归属比对则只删真正属于本 server 的条目。
     */
    internal fun unregisterServerTools(serverId: String) {
        toolMapping.entries.filter { it.value.first == serverId }.map { it.key }.forEach { toolName ->
            ToolRegistry.unregister(toolName)
            toolMapping.remove(toolName)
        }
    }

    // ── 测试钩子 ────────────────────────────────────────────────────────

    /** 测试用:当前由 MCP 注册到 ToolRegistry 的工具全名(传 serverId 只看该 server 的)。 */
    @VisibleForTesting
    internal fun registeredToolNames(serverId: String? = null): Set<String> {
        return toolMapping.entries
            .filter { serverId == null || it.value.first == serverId }
            .map { it.key }
            .toSet()
    }

    /** 测试用:清空单例状态(断开全部 client + 注销全部工具 + 清空映射),避免用例间串味。 */
    @VisibleForTesting
    internal fun resetForTest() {
        clients.keys.toList().forEach { disconnectServer(it) }
        toolMapping.keys.toList().forEach { ToolRegistry.unregister(it) }
        toolMapping.clear()
        clients.clear()
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

    /**
     * server 返回的原始描述经 [McpToolDescriptorPolicy] 净化后的结果。
     *
     * 用 `by lazy` 缓存:净化是纯函数、描述在工具生命周期内不变;且断连时
     * [McpClient.disconnect] 会清空 discoveredTools,缓存可保证描述不会中途"变空"。
     */
    private val descriptionVerdict by lazy {
        McpToolDescriptorPolicy.sanitizeDescription(
            client.discoveredTools[mcpToolName]?.description ?: "",
        )
    }

    /** 外部 MCP 工具的风险等级统一取策略值(`mcp_` 前缀 → HIGH),不接受 server 自报。 */
    private val riskLevel: String get() = ToolRiskPolicy.riskOf(getName())

    override fun getDescriptionEN() = McpToolDescriptorPolicy.composeDescription(
        serverId = serverId,
        toolName = mcpToolName,
        risk = riskLevel,
        verdict = descriptionVerdict,
        english = true,
    )

    override fun getDescriptionCN() = McpToolDescriptorPolicy.composeDescription(
        serverId = serverId,
        toolName = mcpToolName,
        risk = riskLevel,
        verdict = descriptionVerdict,
        english = false,
    )
}
