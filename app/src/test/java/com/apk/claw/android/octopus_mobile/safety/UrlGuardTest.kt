package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * UrlGuard 测试 —— SSRF 防护.
 */
class UrlGuardTest {

    @Test
    fun `allows safe url`() {
        // resolveDns=false：单元测试环境可能无网络，DNS 失败会 fail-closed 拒绝。
        // 这里只验证协议/主机名/IP 规则层放行公网域名。
        assertTrue(UrlGuard.check("https://example.com/path", resolveDns = false).allow)
        assertTrue(UrlGuard.check("http://api.example.com/v1/data", resolveDns = false).allow)
    }

    @Test
    fun `blocks private ip 10x`() {
        assertFalse(UrlGuard.isSafeUrl("http://10.0.0.1/secret"))
        assertFalse(UrlGuard.isSafeUrl("http://10.255.255.255/"))
    }

    @Test
    fun `blocks private ip 172x`() {
        assertFalse(UrlGuard.isSafeUrl("http://172.16.0.1/"))
        assertFalse(UrlGuard.isSafeUrl("http://172.31.255.255/"))
        assertTrue(UrlGuard.isSafeUrl("http://172.15.0.1/"))  // 172.15 不是私有
        assertTrue(UrlGuard.isSafeUrl("http://172.32.0.1/"))  // 172.32 不是私有
    }

    @Test
    fun `blocks private ip 192168x`() {
        assertFalse(UrlGuard.isSafeUrl("http://192.168.1.1/"))
    }

    @Test
    fun `blocks loopback`() {
        assertFalse(UrlGuard.isSafeUrl("http://127.0.0.1/"))
        assertFalse(UrlGuard.isSafeUrl("http://localhost/"))
    }

    @Test
    fun `blocks AWS metadata`() {
        assertFalse(UrlGuard.isSafeUrl("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `blocks metadata domain`() {
        assertFalse(UrlGuard.isSafeUrl("http://metadata.google.internal/"))
    }

    @Test
    fun `blocks local suffix`() {
        assertFalse(UrlGuard.isSafeUrl("http://myapp.local/"))
        assertFalse(UrlGuard.isSafeUrl("http://service.internal/"))
    }

    @Test
    fun `blocks file protocol`() {
        assertFalse(UrlGuard.isSafeUrl("file:///etc/passwd"))
    }

    @Test
    fun `blocks ftp protocol`() {
        assertFalse(UrlGuard.isSafeUrl("ftp://example.com/file"))
    }

    @Test
    fun `verdict includes reason`() {
        val v = UrlGuard.check("http://127.0.0.1/")
        assertFalse(v.allow)
        assertEquals("private_ip: 127.0.0.1", v.reason)
    }

    @Test
    fun `allowPrivate bypasses private check`() {
        assertTrue(UrlGuard.isSafeUrl("http://192.168.1.1/", allowPrivate = true))
    }

    @Test
    fun `empty url blocked`() {
        assertFalse(UrlGuard.isSafeUrl(""))
    }

    @Test
    fun `malformed url blocked`() {
        assertFalse(UrlGuard.isSafeUrl("not a url"))
    }

    // ── SSRF 回归:IPv4-mapped / compat IPv6 字面量不得绕过私网判定 ──

    @Test
    fun `blocks ipv4-mapped ipv6 loopback`() {
        assertFalse(UrlGuard.isSafeUrl("http://[::ffff:127.0.0.1]/"))
    }

    @Test
    fun `blocks ipv4-mapped ipv6 metadata`() {
        // AWS 元数据端点经 IPv4-mapped IPv6 包装
        assertFalse(UrlGuard.isSafeUrl("http://[::ffff:169.254.169.254]/latest/meta-data/"))
    }

    @Test
    fun `blocks ipv4-mapped ipv6 private`() {
        assertFalse(UrlGuard.isSafeUrl("http://[::ffff:192.168.1.1]/"))
    }

    @Test
    fun `blocks ipv6 loopback literal`() {
        assertFalse(UrlGuard.isSafeUrl("http://[::1]/"))
    }

    // ── DNS rebinding 防护:连接期地址分类(SsrfSafeDns 复用 isDisallowedAddress) ──

    @Test
    fun `isDisallowedAddress flags private and loopback and metadata`() {
        assertTrue(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("127.0.0.1")))
        assertTrue(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("10.0.0.1")))
        assertTrue(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("192.168.1.1")))
        assertTrue(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("169.254.169.254")))
        assertTrue(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("::1")))
        assertTrue(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("::ffff:127.0.0.1")))
    }

    @Test
    fun `isDisallowedAddress allows public addresses`() {
        assertFalse(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("8.8.8.8")))
        assertFalse(UrlGuard.isDisallowedAddress(java.net.InetAddress.getByName("1.1.1.1")))
    }

    // ── checkConfiguredEndpoint:用户显式配置的外部端点(MCP SSE) ──
    // 与 check() 的差异:允许 loopback/私网/.local(本机与局域网 MCP server),但始终禁云元数据与 link-local。

    @Test
    fun `configured endpoint allows loopback and lan`() {
        assertTrue(UrlGuard.checkConfiguredEndpoint("http://127.0.0.1:3001/sse", resolveDns = false).allow)
        assertTrue(UrlGuard.checkConfiguredEndpoint("http://localhost:3001/sse", resolveDns = false).allow)
        assertTrue(UrlGuard.checkConfiguredEndpoint("http://192.168.1.10:3001/sse", resolveDns = false).allow)
        assertTrue(UrlGuard.checkConfiguredEndpoint("http://10.0.0.5:8080/sse", resolveDns = false).allow)
    }

    @Test
    fun `configured endpoint allows public domain and mDNS name`() {
        assertTrue(UrlGuard.checkConfiguredEndpoint("https://mcp.example.com/sse", resolveDns = false).allow)
        // .local 是局域网 mDNS 常见写法,配置端点场景放行(与 check() 不同)
        assertTrue(UrlGuard.checkConfiguredEndpoint("http://myapp.local:3001/sse", resolveDns = false).allow)
    }

    @Test
    fun `configured endpoint always blocks cloud metadata`() {
        val aws = UrlGuard.checkConfiguredEndpoint("http://169.254.169.254/latest/meta-data/", resolveDns = false)
        assertFalse(aws.allow)
        assertEquals("blocked_link_local: 169.254.169.254", aws.reason)

        val gcp = UrlGuard.checkConfiguredEndpoint(
            "http://metadata.google.internal/computeMetadata/v1/",
            resolveDns = false,
        )
        assertFalse(gcp.allow)
        assertTrue(gcp.reason.startsWith("blocked_metadata_host"))

        val azure = UrlGuard.checkConfiguredEndpoint("http://instance-data/latest/", resolveDns = false)
        assertFalse(azure.allow)
    }

    @Test
    fun `configured endpoint blocks mapped ipv6 and link local v6`() {
        assertFalse(UrlGuard.checkConfiguredEndpoint("http://[::ffff:169.254.169.254]/", resolveDns = false).allow)
        assertFalse(UrlGuard.checkConfiguredEndpoint("http://[fe80::1]/", resolveDns = false).allow)
    }

    @Test
    fun `configured endpoint blocks non http schemes and malformed urls`() {
        assertFalse(UrlGuard.checkConfiguredEndpoint("file:///etc/passwd", resolveDns = false).allow)
        assertFalse(UrlGuard.checkConfiguredEndpoint("ws://example.com/socket", resolveDns = false).allow)
        assertFalse(UrlGuard.checkConfiguredEndpoint("not a url", resolveDns = false).allow)
        assertFalse(UrlGuard.checkConfiguredEndpoint("", resolveDns = false).allow)
    }
}
