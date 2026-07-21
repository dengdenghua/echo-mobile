package com.apk.claw.android.mcp

import android.content.Context
import com.apk.claw.android.utils.XLog

/**
 * MCP Server 启动入口 —— 静态工具类,内部持有 [McpServer] 单例。
 *
 * 典型用法(在 [com.apk.claw.android.ClawApplication] 或 SettingsActivity 中):
 * ```
 * McpServerBootstrap.start(applicationContext)         // 启动 server,默认端口 9528
 * McpServerBootstrap.setProvider(myToolProvider)       // 注入工具后端
 * McpServerBootstrap.setApprovalGate(myApprovalGate)   // 注入审批闸门(可选)
 * McpServerBootstrap.stop()                            // 停止 server
 * ```
 *
 * 顺序约束:
 *  - [start] 可在 [setProvider] / [setApprovalGate] 之前或之后调用;
 *    MCP server 在 provider 未注入前可正常接受 initialize / ping,
 *    tools/list 返回空,tools/call 返回 "no provider"。
 *  - 多次调用 [start] 是幂等的:若 server 已启动,仅更新端口(若不同则需先 [stop])。
 *
 * 线程安全:所有方法均加 synchronized,可在任意线程调用。
 */
object McpServerBootstrap {

    private const val TAG = "McpServerBootstrap"

    @Volatile
    private var server: McpServer? = null

    @Volatile
    private var currentPort: Int = McpServer.DEFAULT_PORT

    @Volatile
    private var pendingProvider: McpToolRegistryProvider? = null

    @Volatile
    private var pendingGate: McpApprovalGate? = null

    /**
     * 启动 MCP server。
     *
     * @param context Android Context(仅用于日志,不持有引用)
     * @param port 监听端口,默认 [McpServer.DEFAULT_PORT]=9528
     */
    @Synchronized
    fun start(context: Context, port: Int = McpServer.DEFAULT_PORT) {
        val existing = server
        if (existing != null) {
            if (currentPort == port) {
                XLog.i(TAG, "MCP server already running on port $port, no-op")
                return
            }
            // 端口变了,先停再起
            stopInternal()
        }

        val s = McpServer(port = port)
        pendingProvider?.let { s.setProvider(it) }
        pendingGate?.let { s.setApprovalGate(it) }

        try {
            s.start(SOCKET_READ_TIMEOUT, false)
            server = s
            currentPort = port
            XLog.i(TAG, "MCP server started on port $port (path=${McpServer.MCP_PATH})")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to start MCP server on port $port: ${e.message}", e)
            server = null
        }
    }

    /** 停止 MCP server。幂等。 */
    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        server?.let { s ->
            try {
                s.stop()
                XLog.i(TAG, "MCP server stopped (port=$currentPort)")
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to stop MCP server: ${e.message}", e)
            }
        }
        server = null
    }

    /** 注入工具后端。即使 server 还没启动也会缓存,等 [start] 时生效。 */
    @Synchronized
    fun setProvider(provider: McpToolRegistryProvider) {
        pendingProvider = provider
        server?.setProvider(provider)
    }

    /** 注入审批闸门。即使 server 还没启动也会缓存,等 [start] 时生效。 */
    @Synchronized
    fun setApprovalGate(gate: McpApprovalGate) {
        pendingGate = gate
        server?.setApprovalGate(gate)
    }

    /** server 是否正在运行。 */
    fun isRunning(): Boolean = server != null

    /** 当前监听端口;未启动时返回 [McpServer.DEFAULT_PORT]。 */
    fun currentPort(): Int = currentPort

    /** NanoHTTPD 默认 socket 读超时(10s),参考 ConfigServer。 */
    private const val SOCKET_READ_TIMEOUT = 10000
}
