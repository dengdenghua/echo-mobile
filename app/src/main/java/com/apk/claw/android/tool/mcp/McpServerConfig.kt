package com.apk.claw.android.tool.mcp

import android.util.Log
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * MCP Server 配置 —— 持久化用户配置的 MCP server 列表,启动时由 ClawApplication 恢复。
 *
 * 与 [McpManager] 的关系:McpManager 是运行时管理器(连接/断开/工具注册),本类是配置存储
 * (JSON 序列化到 KVUtils)。两者分离让 McpManager 保持纯 JVM 可单测,UI 操作走 Store 持久化
 * 后再调用 McpManager 应用。
 *
 * 存储范式照搬 [com.apk.claw.android.octopus_mobile.skill.PromptSkillStore]:JSON List<data class>。
 */
data class McpServerConfig(
    /** 唯一标识,仅 [a-zA-Z0-9_-],用作工具名前缀 `mcp_<id>_<tool>`。编辑时不可改。 */
    val id: String,
    /** 显示名(可中文),仅供 UI 展示。 */
    val name: String,
    /** 传输方式:STDIO 走子进程,SSE 走 HTTP Server-Sent Events。 */
    val transportType: TransportType,
    /** STDIO 模式的命令(argv 数组,如 `["npx", "@modelcontextprotocol/server-filesystem", "/sdcard"]`)。SSE 模式留空。 */
    val command: List<String> = emptyList(),
    /** STDIO 模式的环境变量。SSE 模式留空。 */
    val env: Map<String, String> = emptyMap(),
    /** SSE 模式的端点 URL(http(s)://)。STDIO 模式留空。 */
    val url: String = "",
    /** SSE 模式的请求头(如 Authorization)。STDIO 模式留空。 */
    val headers: Map<String, String> = emptyMap(),
    /** 启动时是否自动连接。默认 true。 */
    val autoConnect: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class TransportType { STDIO, SSE }

    /** 转为 McpClient.Transport 供 McpManager.connectServer 使用。 */
    fun toTransport(): McpClient.Transport = when (transportType) {
        TransportType.STDIO -> McpClient.Transport.Stdio(command, env)
        TransportType.SSE -> McpClient.Transport.Sse(url, headers)
    }
}

/**
 * MCP Server 配置存储 —— JSON List 持久化到 KVUtils。
 *
 * 用法:
 *  - UI 添加/编辑/删除:[add] / [update] / [delete]
 *  - App 启动恢复:[restoreAll] 连接所有 autoConnect=true 的 server
 *  - UI 列表展示:[all]
 */
@Suppress("TooGenericExceptionCaught")
object McpServerConfigStore {
    private const val TAG = "McpServerConfigStore"
    private const val KEY = "mcp_servers"
    private const val MAX_SERVERS = 20
    private val GSON = Gson()
    private val SERVER_ID_REGEX = Regex("[a-zA-Z0-9_-]+")

    fun all(): List<McpServerConfig> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<McpServerConfig>>() {}.type
            GSON.fromJson<List<McpServerConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse MCP server configs", e)
            emptyList()
        }
    }

    fun get(id: String): McpServerConfig? = all().firstOrNull { it.id == id }

    /** 校验 serverId 合法性(与 McpManager.connectServer 一致)。 */
    fun isValidId(id: String): Boolean = id.matches(SERVER_ID_REGEX) && id.isNotEmpty()

    /** 添加:同 id 覆盖。返回 null 表示成功,非 null 表示错误信息。 */
    fun add(cfg: McpServerConfig): String? {
        if (!isValidId(cfg.id)) return "serverId 仅允许 [a-zA-Z0-9_-]"
        val list = all().toMutableList()
        // 同 id 覆盖:先断开旧连接
        if (list.any { it.id == cfg.id }) {
            McpManager.disconnectServer(cfg.id)
            list.removeAll { it.id == cfg.id }
        }
        if (list.size >= MAX_SERVERS) return "已达上限 $MAX_SERVERS 个 server"
        list.add(cfg)
        save(list)
        return null
    }

    /** 更新:按 id 找到并替换。 */
    fun update(cfg: McpServerConfig) {
        val list = all().map { if (it.id == cfg.id) cfg else it }
        save(list)
    }

    /** 删除:同时断开运行时连接。 */
    fun delete(id: String) {
        McpManager.disconnectServer(id)
        save(all().filterNot { it.id == id })
    }

    /**
     * App 启动时由 [com.apk.claw.android.ClawApplication] 调用,恢复所有 autoConnect=true 的 server。
     * 失败的 server 不会阻塞其他 server 的恢复。
     */
    fun restoreAll() {
        all().filter { it.autoConnect }.forEach { cfg ->
            runCatching {
                McpManager.connectServer(cfg.id, cfg.toTransport())
            }.onFailure { e ->
                Log.w(TAG, "Restore MCP server [${cfg.id}] failed: ${e.message}")
            }
        }
    }

    private fun save(list: List<McpServerConfig>) {
        KVUtils.putString(KEY, GSON.toJson(list.takeLast(MAX_SERVERS)))
    }
}
