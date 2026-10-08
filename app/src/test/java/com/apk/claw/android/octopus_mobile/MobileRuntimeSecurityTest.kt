package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileRuntimeSecurityTest {

    @Test
    fun `wss runtime is allowed and production ready`() {
        val decision = MobileRuntimeSecurity.assess("wss://runtime.example/ws")
        assertTrue(decision.allowed)
        assertFalse(decision.localDevelopment)
        assertTrue(MobileRuntimeSecurity.isProductionReadyTransport("wss://runtime.example/ws"))
    }

    @Test
    fun `local loopback cleartext is allowed for development only`() {
        val decision = MobileRuntimeSecurity.assess("ws://10.0.2.2:8765")
        assertTrue(decision.allowed)
        assertTrue(decision.localDevelopment)
        assertFalse(MobileRuntimeSecurity.isProductionReadyTransport("ws://10.0.2.2:8765"))
    }

    @Test
    fun `localhost cleartext is allowed for development only`() {
        val decision = MobileRuntimeSecurity.assess("ws://localhost:8765")
        assertTrue(decision.allowed)
        assertTrue(decision.localDevelopment)
        assertFalse(MobileRuntimeSecurity.isProductionReadyTransport("ws://localhost:8765"))
    }

    @Test
    fun `lan cleartext is allowed for development only`() {
        // RFC 1918 私有网络用于 LAN 多设备控制/投屏,允许明文 ws://,但不算生产就绪。
        for (host in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.2", "192.168.0.254")) {
            val url = "ws://$host:8765"
            val decision = MobileRuntimeSecurity.assess(url)
            assertTrue("LAN host $host should be allowed", decision.allowed)
            assertTrue("LAN host $host should be local development", decision.localDevelopment)
            assertFalse("LAN host $host should not be production ready",
                MobileRuntimeSecurity.isProductionReadyTransport(url))
        }
    }

    @Test
    fun `public network cleartext is always blocked regardless of override`() {
        // 203.0.113.0/24 是 RFC 5737 文档示例段(非私有、非环回),用作"公网"替身。
        // allowInsecureRuntime 是 no-op,任何值都不能放公网明文 ws:// 过关。
        val withoutOverride = MobileRuntimeSecurity.assess("ws://203.0.113.42:8765")
        assertFalse(withoutOverride.allowed)
        assertFalse(withoutOverride.localDevelopment)

        val withOverride = MobileRuntimeSecurity.assess(
            "ws://203.0.113.42:8765",
            allowInsecureRuntime = true,
        )
        assertFalse(withOverride.allowed)
        assertFalse(MobileRuntimeSecurity.isProductionReadyTransport("ws://203.0.113.42:8765"))
    }

    @Test
    fun `non ws schemes are rejected`() {
        val httpDecision = MobileRuntimeSecurity.assess("http://example.com/ws")
        assertFalse(httpDecision.allowed)
        val tcpDecision = MobileRuntimeSecurity.assess("tcp://example.com:8765")
        assertFalse(tcpDecision.allowed)
    }

    @Test
    fun `blank or invalid url is rejected`() {
        assertFalse(MobileRuntimeSecurity.assess("").allowed)
        assertFalse(MobileRuntimeSecurity.assess("   ").allowed)
        assertFalse(MobileRuntimeSecurity.assess("not-a-url").allowed)
    }

    @Test
    fun `lan boundary addresses are handled correctly`() {
        // 172.15.x.x 和 172.32.x.x 不在 172.16.0.0/12 内,应被当作公网拒绝。
        assertFalse(MobileRuntimeSecurity.assess("ws://172.15.0.1:8765").allowed)
        assertFalse(MobileRuntimeSecurity.assess("ws://172.32.0.1:8765").allowed)
        // 172.16.x.x 和 172.31.x.x 在范围内,应被允许(本地开发)。
        assertTrue(MobileRuntimeSecurity.assess("ws://172.16.0.1:8765").allowed)
        assertTrue(MobileRuntimeSecurity.assess("ws://172.31.255.255:8765").allowed)
    }

    @Test
    fun `invalid octets are not treated as lan`() {
        // 非法 octet(>255 或非数字)不应被误判为 LAN。
        assertFalse(MobileRuntimeSecurity.assess("ws://999.999.999.999:8765").allowed)
        assertFalse(MobileRuntimeSecurity.assess("ws://10.1.2.3.4:8765").allowed)
        assertFalse(MobileRuntimeSecurity.assess("ws://not.an.ip.addr:8765").allowed)
    }

    @Test
    fun `cleartext auth host classifier accepts only local and private hosts`() {
        val allowed = listOf(
            "localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2",
            "10.1.2.3", "172.16.0.1", "192.168.1.20", "169.254.10.20",
            "fe80::1", "[fe80::abcd]", "nas.local", "Printer.LOCAL",
        )
        for (host in allowed) {
            assertTrue("$host should allow cleartext auth", MobileRuntimeSecurity.isCleartextAuthHost(host))
        }
        val rejected = listOf(
            "", "203.0.113.42", "8.8.8.8", "172.32.0.1", "169.255.0.1",
            "example.com", "local", ".local", "evil.local.example.com",
            "2001:db8::1", "fec0::1",
        )
        for (host in rejected) {
            assertFalse("$host must not allow cleartext auth", MobileRuntimeSecurity.isCleartextAuthHost(host))
        }
    }

    @Test
    fun `bearer token is refused over public ws but allowed over wss and lan ws`() {
        assertTrue(MobileRuntimeSecurity.allowsBearerToken("wss://runtime.example/ws"))
        assertTrue(MobileRuntimeSecurity.allowsBearerToken("ws://192.168.1.2:8765"))
        assertTrue(MobileRuntimeSecurity.allowsBearerToken("ws://10.0.2.2:8765"))
        assertTrue(MobileRuntimeSecurity.allowsBearerToken("ws://hub.local:8765"))
        assertTrue(MobileRuntimeSecurity.allowsBearerToken("ws://[fe80::1]:8765"))
        assertFalse(MobileRuntimeSecurity.allowsBearerToken("ws://runtime.example/ws"))
        assertFalse(MobileRuntimeSecurity.allowsBearerToken("ws://203.0.113.42:8765"))
        assertFalse(MobileRuntimeSecurity.allowsBearerToken("http://192.168.1.2:8765"))
        assertFalse(MobileRuntimeSecurity.allowsBearerToken("not a url"))
    }

    @Test
    fun `assess reason mentions production guidance for public ws`() {
        val decision = MobileRuntimeSecurity.assess("ws://203.0.113.42:8765")
        assertEquals(false, decision.allowed)
        // 失败原因应指引用户升级到 wss://。
        assertTrue(decision.reason.contains("wss://", ignoreCase = true))
    }
}
