package com.apk.claw.android.mcp

/**
 * MCP Server 的监听地址策略（纯逻辑，便于单测）。
 *
 * 与 9527 ConfigServer 保持一致：局域网模式开启且有 WiFi IP 时只绑定该 WiFi 接口地址，
 * 否则只绑定回环地址；绝不绑定 0.0.0.0。
 */
object McpBindPolicy {
    const val LOOPBACK = "127.0.0.1"

    fun resolve(lanModeEnabled: Boolean, wifiIp: String?): String =
        if (lanModeEnabled && !wifiIp.isNullOrBlank() && wifiIp != "0.0.0.0") wifiIp else LOOPBACK
}
