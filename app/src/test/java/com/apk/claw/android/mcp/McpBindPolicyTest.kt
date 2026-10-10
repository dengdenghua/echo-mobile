package com.apk.claw.android.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpBindPolicyTest {
    @Test
    fun `startup retry backs off without overflowing or becoming unbounded`() {
        assertEquals(1_000L, McpBindPolicy.retryDelayMs(-1))
        assertEquals(1_000L, McpBindPolicy.retryDelayMs(0))
        assertEquals(2_000L, McpBindPolicy.retryDelayMs(1))
        assertEquals(30_000L, McpBindPolicy.retryDelayMs(5))
        assertEquals(30_000L, McpBindPolicy.retryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `lan mode with wifi ip binds to that interface only`() {
        assertEquals("192.168.1.5", McpBindPolicy.resolve(true, "192.168.1.5"))
    }

    @Test
    fun `lan mode off or no usable wifi ip binds to loopback never wildcard`() {
        assertEquals("127.0.0.1", McpBindPolicy.resolve(false, "192.168.1.5"))
        assertEquals("127.0.0.1", McpBindPolicy.resolve(true, null))
        assertEquals("127.0.0.1", McpBindPolicy.resolve(true, ""))
        assertEquals("127.0.0.1", McpBindPolicy.resolve(true, "0.0.0.0"))
    }

    @Test
    fun `rebind only when running and bind differs`() {
        assertEquals(false, McpBindPolicy.shouldRebind(null, "127.0.0.1"))
        assertEquals(false, McpBindPolicy.shouldRebind("127.0.0.1", "127.0.0.1"))
        assertEquals(false, McpBindPolicy.shouldRebind("192.168.1.5", "192.168.1.5"))
        assertEquals(true, McpBindPolicy.shouldRebind("127.0.0.1", "192.168.1.5"))
        assertEquals(true, McpBindPolicy.shouldRebind("192.168.1.5", "192.168.1.9"))
        assertEquals(true, McpBindPolicy.shouldRebind("192.168.1.5", "127.0.0.1"))
    }
}
