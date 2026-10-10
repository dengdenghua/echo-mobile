package com.apk.claw.android.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.apk.claw.android.utils.XLog

/**
 * 监听 WiFi 变化并以 [McpBindPolicy.REBIND_DEBOUNCE_MS] 去抖后回调 [onChanged]，
 * 供 MCP Server 重新评估绑定地址。
 */
internal class McpNetworkWatcher(private val onChanged: () -> Unit) {

    private var appContext: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val task = Runnable { onChanged() }

    @Synchronized
    fun register(context: Context) {
        if (callback != null) return
        appContext = context
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = schedule()
                override fun onLost(network: Network) = schedule()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = schedule()
            }
            cm.registerNetworkCallback(request, cb)
            callback = cb
        } catch (e: SecurityException) {
            XLog.e(TAG, "Failed to register MCP network callback: ${e.message}")
        }
    }

    @Synchronized
    fun unregister() {
        handler.removeCallbacks(task)
        val cb = callback ?: return
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (e: IllegalArgumentException) {
            XLog.e(TAG, "Failed to unregister MCP network callback: ${e.message}")
        }
        callback = null
    }

    /** 每次事件都重置计时，只在安静期结束后评估一次。 */
    fun schedule(delayMs: Long = McpBindPolicy.REBIND_DEBOUNCE_MS) {
        handler.removeCallbacks(task)
        handler.postDelayed(task, delayMs)
    }

    fun scheduleRetry(attempt: Int): Int {
        schedule(McpBindPolicy.retryDelayMs(attempt))
        return (attempt + 1).coerceAtMost(McpBindPolicy.MAX_RETRY_ATTEMPT)
    }

    private companion object {
        const val TAG = "McpNetworkWatcher"
    }
}
