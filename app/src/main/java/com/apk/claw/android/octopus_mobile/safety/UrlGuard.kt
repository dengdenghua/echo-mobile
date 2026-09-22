package com.apk.claw.android.octopus_mobile.safety

import android.util.Log
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * URL 安全守卫 —— 从母体 runtime/safety/immunity/url_guard.py 移植.
 *
 * 防止 SSRF（Server-Side Request Forgery）：
 *  - 只允许 http/https 协议
 *  - 阻止内网 IP（10.x / 172.16-31.x / 192.168.x / 127.x）
 *  - 阻止云元数据端点（169.254.169.254 / metadata.google.internal）
 *  - 阻止本地域名（localhost / .local / .internal）
 *
 * 用法：
 * ```kotlin
 * val verdict = UrlGuard.check("https://example.com")
 * if (!verdict.allow) { /* 阻止访问 */ }
 *
 * val verdict2 = UrlGuard.check("http://169.254.169.254/latest/meta-data/")
 * // verdict2.allow = false, reason = "private_ip"
 * ```
 */
object UrlGuard {

    private const val TAG = "UrlGuard"

    private val SAFE_SCHEMES = setOf("http", "https")

    private val BLOCKED_HOSTS = setOf(
        "localhost",
        "metadata.google.internal",
        "metadata.azure.internal",
        "instance-data",
        "instance-data.ec2.internal",
    )

    private val BLOCKED_SUFFIXES = listOf(
        ".localhost",
        ".local",
        ".internal",
        ".svc.cluster.local",
        ".lan",
    )

    // ── 结果 ──────────────────────────────────────────

    data class URLVerdict(
        val allow: Boolean,
        val url: String,
        val reason: String = "",
        val resolvedIp: String? = null,
    )

    // ── 检查 ──────────────────────────────────────────

    fun check(
        url: String,
        allowPrivate: Boolean = false,
        resolveDns: Boolean = true,
    ): URLVerdict {
        if (url.isBlank()) {
            return URLVerdict(false, url, "empty_url")
        }

        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            return URLVerdict(false, url, "unparseable: ${e.message}")
        }

        // 协议检查
        val scheme = (parsed.scheme ?: "").lowercase()
        if (scheme !in SAFE_SCHEMES) {
            return URLVerdict(false, url, "disallowed_scheme: $scheme")
        }

        // 主机名检查
        val host = parsed.host
        if (host.isNullOrBlank()) {
            return URLVerdict(false, url, "missing_host")
        }

        val hostLc = host.lowercase()

        if (!allowPrivate) {
            // 阻止已知危险主机名
            if (hostLc in BLOCKED_HOSTS) {
                return URLVerdict(false, url, "blocked_host: $hostLc")
            }
            // 阻止危险后缀
            for (suffix in BLOCKED_SUFFIXES) {
                if (hostLc.endsWith(suffix)) {
                    return URLVerdict(false, url, "blocked_suffix: $suffix")
                }
            }
        }

        // IP 地址检查
        val ip = parseIp(host)
        if (ip != null) {
            if (!allowPrivate && isPrivateIp(ip)) {
                return URLVerdict(false, url, "private_ip: $ip", ip)
            }
            return URLVerdict(true, url, resolvedIp = ip)
        }

        // DNS 解析检查
        if (!resolveDns) {
            return URLVerdict(true, url)
        }

        val resolved = resolveHost(host)
        if (resolved == null) {
            return URLVerdict(false, url, "dns_resolution_failed")
        }

        if (!allowPrivate && isPrivateIp(resolved)) {
            return URLVerdict(
                false, url,
                "dns_resolves_to_private: $host → $resolved",
                resolved,
            )
        }

        return URLVerdict(true, url, resolvedIp = resolved)
    }

    fun isSafeUrl(url: String, allowPrivate: Boolean = false): Boolean {
        return check(url, allowPrivate = allowPrivate).allow
    }

    /**
     * 校验「用户显式配置的外部端点」（如 MCP SSE server URL）。
     *
     * 与 [check] 的差异：**允许** loopback / 私网 / `.local`（本机与局域网场景需要），但**始终**阻止
     * 云元数据端点与 link-local 地址（169.254.0.0/16、fe80::/10）—— 这两类是 SSRF 里价值最高、
     * 且不存在合法 MCP server 用法的目标。
     */
    fun checkConfiguredEndpoint(url: String, resolveDns: Boolean = true): URLVerdict {
        val base = check(url, allowPrivate = true, resolveDns = false)
        if (!base.allow) return base

        val host = try { URI(url).host } catch (e: Exception) { null }
        if (host.isNullOrBlank()) return URLVerdict(false, url, "missing_host")
        val hostLc = host.lowercase().trimEnd('.')
        if (hostLc in METADATA_HOSTS) {
            return URLVerdict(false, url, "blocked_metadata_host: $hostLc")
        }

        val ip = parseIp(host)
        if (ip != null) {
            return if (isLinkLocalLiteral(ip)) {
                URLVerdict(false, url, "blocked_link_local: $ip", ip)
            } else {
                URLVerdict(true, url, resolvedIp = ip)
            }
        }

        if (!resolveDns) return URLVerdict(true, url)

        val resolved = resolveHost(host)
            ?: return URLVerdict(false, url, "dns_resolution_failed")
        return if (isLinkLocalLiteral(resolved)) {
            URLVerdict(false, url, "dns_resolves_to_link_local: $host → $resolved", resolved)
        } else {
            URLVerdict(true, url, resolvedIp = resolved)
        }
    }

    /**
     * 已解析地址是否属于应阻断的目标(loopback/link-local/site-local/any-local/multicast +
     * 169.254/16 + IPv4-mapped/compat 内嵌 IPv4 + fc00::/7)。供 [SsrfSafeDns] 在**连接期**
     * 对 OkHttp 实际使用的解析结果逐个校验,消除 check-then-connect 的 DNS rebinding 窗口。
     */
    fun isDisallowedAddress(addr: InetAddress): Boolean = isPrivateAddress(addr)

    // ── 内部 ──────────────────────────────────────────

    /** 云元数据服务主机名：任何场景都不允许作为出站目标。 */
    private val METADATA_HOSTS = setOf(
        "metadata.google.internal",
        "metadata.azure.internal",
        "instance-data",
        "instance-data.ec2.internal",
        "metadata",
    )

    /** 该 IP 字面量是否属于 link-local（169.254.0.0/16 / fe80::/10，含 IPv4-mapped 变体）。 */
    private fun isLinkLocalLiteral(ip: String): Boolean {
        val addr = try {
            InetAddress.getByName(ip.trim('[', ']'))
        } catch (e: Exception) {
            return true  // 解析失败 → 保守拒绝(fail-closed)
        }
        if (addr.isLinkLocalAddress) return true
        val bytes = addr.address
        if (bytes.size == 16) {
            val v4 = extractEmbeddedIpv4(bytes) ?: return false
            val inner = try { InetAddress.getByAddress(v4) } catch (e: Exception) { null } ?: return true
            return inner.isLinkLocalAddress
        }
        return false
    }

    private fun parseIp(host: String): String? {
        val stripped = host.trim('[', ']')
        // 只把"长得像 IP 字面量"的主机当 IP 处理。
        // 不能直接用 InetAddress.getByName 判断：它对域名会做 DNS 解析并成功返回，
        // 导致域名跳过下方 dns_resolves_to_private 检查（SSRF 绕过）。
        val looksLikeIpv4 = stripped.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val looksLikeIpv6 = stripped.contains(":")
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return try {
            InetAddress.getByName(stripped)  // 纯字面量不触发 DNS
            stripped
        } catch (e: Exception) {
            null
        }
    }

    private fun isPrivateIp(ip: String): Boolean {
        // 规范化后按字节分类:纯字符串前缀判断会漏掉 IPv4-mapped IPv6
        // (::ffff:169.254.169.254 / ::ffff:127.0.0.1)、IPv4-compatible(::a.b.c.d)、
        // 压缩写法和大小写变体 → SSRF 绕过。统一交给 InetAddress 解析后按内建
        // isLoopback/isLinkLocal/isSiteLocal/isAnyLocal + 显式规则判定。
        val stripped = ip.trim('[', ']')
        val addr = try {
            // 入参恒为 IP 字面量(parseIp 返回的字面量或 resolveHost 返回的 hostAddress),
            // getByName 对纯数字字面量不触发 DNS,安全。
            InetAddress.getByName(stripped)
        } catch (e: Exception) {
            // 解析失败 → 保守拒绝(fail-closed)
            return true
        }
        return isPrivateAddress(addr)
    }

    /** 对已解析的 InetAddress 分类;IPv4-mapped/compat IPv6 先拆出内嵌 IPv4 再按 IPv4 规则判定。 */
    private fun isPrivateAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 16) {
            val mappedV4 = extractEmbeddedIpv4(bytes)
            if (mappedV4 != null) {
                val v4 = try { InetAddress.getByAddress(mappedV4) } catch (e: Exception) { null }
                if (v4 != null) return classifyAddress(v4)
            }
        }
        return classifyAddress(addr)
    }

    /** ::ffff:a.b.c.d(mapped)与 ::a.b.c.d(compat)的内嵌 IPv4 字节,否则 null。 */
    private fun extractEmbeddedIpv4(b: ByteArray): ByteArray? {
        if (b.size != 16) return null
        val tail = byteArrayOf(b[12], b[13], b[14], b[15])
        val leading10Zero = (0..9).all { b[it].toInt() == 0 }
        val isMapped = leading10Zero && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
        // IPv4-compatible(::a.b.c.d):前 12 字节 0 且首个内嵌八位组非 0 —— 借此排除 ::(any-local)
        // 与 ::1(loopback),它们不是内嵌 IPv4,应交回内建 isLoopback/isAnyLocal 判定。
        val leading12Zero = (0..11).all { b[it].toInt() == 0 }
        val isCompat = leading12Zero && (b[12].toInt() and 0xff) != 0
        return if (isMapped || isCompat) tail else null
    }

    private fun classifyAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true    // 127.0.0.0/8, ::1
        if (addr.isLinkLocalAddress) return true   // 169.254.0.0/16, fe80::/10
        if (addr.isSiteLocalAddress) return true   // 10/8, 172.16/12, 192.168/16
        if (addr.isAnyLocalAddress) return true    // 0.0.0.0, ::
        if (addr.isMulticastAddress) return true   // 组播不作为请求目标
        val h = addr.hostAddress?.lowercase() ?: return true
        // 显式兜底(部分 JVM 对以下不置 flag)
        if (h.startsWith("169.254.")) return true
        if ((h.startsWith("fc") || h.startsWith("fd")) && h.length > 2 && h[2] == ':') return true  // fc00::/7 ULA
        return false
    }

    private fun resolveHost(host: String): String? {
        return try {
            val addr = InetAddress.getByName(host)
            addr.hostAddress
        } catch (e: UnknownHostException) {
            Log.d(TAG, "DNS resolution failed: $host")
            null
        }
    }
}
