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

    /**
     * 是否需要重绑：服务在跑且当前绑定地址与期望地址不同才重绑；
     * 未运行（currentBind == null）时不由本函数负责（由 start 处理）。
     */
    fun shouldRebind(currentBind: String?, desiredBind: String): Boolean =
        currentBind != null && currentBind != desiredBind

    /** 网络抖动合并窗口：窗口内的多次事件只在最后一次事件后评估一次。 */
    const val REBIND_DEBOUNCE_MS = 1500L

    /** 启动失败后的有界退避；端口释放后无需等待下一次网络事件。 */
    fun retryDelayMs(attempt: Int): Long =
        (INITIAL_RETRY_DELAY_MS shl attempt.coerceIn(0, MAX_RETRY_ATTEMPT))
            .coerceAtMost(MAX_RETRY_DELAY_MS)

    const val MAX_RETRY_ATTEMPT = 5
    private const val INITIAL_RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRY_DELAY_MS = 30_000L
}
