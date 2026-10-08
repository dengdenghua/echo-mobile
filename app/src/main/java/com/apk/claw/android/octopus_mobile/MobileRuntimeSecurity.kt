package com.apk.claw.android.octopus_mobile

import java.net.URI

/**
 * Runtime transport policy for the mother-brain WebSocket.
 *
 * Production deployments must use wss://. Cleartext ws:// is allowed only for
 * loopback, emulator, and private network (LAN) development. Non-private
 * ws:// is always blocked regardless of user override — auth tokens must
 * never traverse public networks in plaintext.
 */
object MobileRuntimeSecurity {

    private val LOCAL_CLEAR_TEXT_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "::1",
        "[::1]",
        "10.0.2.2",
    )

    data class Decision(
        val allowed: Boolean,
        val localDevelopment: Boolean,
        val reason: String,
    )

    @Suppress("ReturnCount")
    fun assess(runtimeUrl: String, @Suppress("UNUSED_PARAMETER") allowInsecureRuntime: Boolean = false): Decision {
        val trimmed = runtimeUrl.trim()
        if (trimmed.isBlank()) {
            return Decision(allowed = false, localDevelopment = false, reason = "Runtime URL is empty")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Decision(allowed = false, localDevelopment = false, reason = "Runtime URL is invalid")
        val scheme = uri.scheme?.lowercase()
        val host = (uri.host ?: "").lowercase()
        return when {
            scheme == "wss" -> Decision(allowed = true, localDevelopment = false, reason = "secure websocket")
            scheme != "ws" -> Decision(allowed = false, localDevelopment = false, reason = "Runtime URL must use wss:// or ws://")
            isLocalCleartextHost(host) -> Decision(allowed = true, localDevelopment = true, reason = "local development websocket")
            isPrivateNetworkHost(host) -> Decision(
                allowed = true, localDevelopment = true,
                reason = "private network (LAN) websocket",
            )
            else -> Decision(
                allowed = false,
                localDevelopment = false,
                reason = "Cleartext ws:// over public network is blocked — " +
                    "use wss:// for production",
            )
        }
    }

    fun isProductionReadyTransport(runtimeUrl: String): Boolean {
        val decision = assess(runtimeUrl, allowInsecureRuntime = false)
        return decision.allowed && !decision.localDevelopment
    }

    /**
     * Whether a bearer token may be sent to [runtimeUrl]. Always true for wss://;
     * for cleartext ws:// only loopback, the emulator host 10.0.2.2, RFC 1918,
     * link-local and mDNS `.local` hosts qualify.
     */
    fun allowsBearerToken(runtimeUrl: String): Boolean {
        val uri = runCatching { URI(runtimeUrl.trim()) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "wss" -> true
            "ws" -> isCleartextAuthHost(uri.host ?: "")
            else -> false
        }
    }

    /** Host classifier for cleartext credentials: loopback / emulator / private LAN only. */
    fun isCleartextAuthHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase().removeSurrounding("[", "]")
        if (host.isEmpty()) return false
        return isLocalCleartextHost(host) || isPrivateNetworkHost(host)
    }

    private fun isLocalCleartextHost(host: String): Boolean =
        host in LOCAL_CLEAR_TEXT_HOSTS

    /**
     * 判断是否为私有网络地址（LAN）。
     * RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16；
     * 链路本地: 169.254.0.0/16、fe80::/10；mDNS: *.local
     */
    private fun isPrivateNetworkHost(rawHost: String): Boolean {
        val host = rawHost.removeSurrounding("[", "]")
        return when {
            host.endsWith(".local") && host.length > ".local".length -> true
            host.contains(':') -> isIpv6LinkLocal(host)
            else -> isPrivateIpv4(host)
        }
    }

    @Suppress("ReturnCount", "MagicNumber")
    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = runCatching { parts.map { it.toInt() } }.getOrNull() ?: return false
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true                          // 10.0.0.0/8
            octets[0] == 172 && octets[1] in 16..31 -> true  // 172.16.0.0/12
            octets[0] == 192 && octets[1] == 168 -> true     // 192.168.0.0/16
            octets[0] == 169 && octets[1] == 254 -> true     // 169.254.0.0/16 link-local
            else -> false
        }
    }

    /** fe80::/10 —— 首个 hextet 落在 fe80..febf。 */
    @Suppress("MagicNumber")
    private fun isIpv6LinkLocal(host: String): Boolean {
        val first = host.substringBefore('%').substringBefore(':')
        val value = first.toIntOrNull(16) ?: return false
        return first.length == 4 && value in 0xfe80..0xfebf
    }
}
