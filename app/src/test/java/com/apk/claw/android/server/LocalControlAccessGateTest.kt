package com.apk.claw.android.server

import com.apk.claw.android.server.LocalControlAccessGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalControlAccessGateTest {

    private val validHeader = "Bearer good"
    private fun gate() = LocalControlAccessGate(
        AuthFailureLimiter(maxFailures = 3, windowMs = 1_000, lockoutMs = 10_000),
    ) { it == validHeader }

    @Test
    fun `static shell pages and whitelisted assets are public for GET only`() {
        val g = gate()
        for (uri in listOf("/", "/index.html", "/console", "/console.html", "/console-app.js", "/tokens.css")) {
            assertEquals(uri, Decision.ALLOW_PUBLIC, g.decide(uri, true, null, "ip", 0))
            assertEquals(uri, Decision.UNAUTHORIZED, g.decide(uri, false, null, "ip", 0))
        }
    }

    @Test
    fun `api, debug page and non whitelisted assets require token`() {
        val g = gate()
        for (uri in listOf("/api/auth/check", "/api/control/input", "/debug.html", "/debug-app.js", "/../web/app.js")) {
            assertEquals(uri, Decision.UNAUTHORIZED, g.decide(uri, true, null, "ip", 0))
            assertEquals(uri, Decision.ALLOW_AUTHENTICATED, g.decide(uri, true, validHeader, "ip", 0))
        }
    }

    @Test
    fun `wrong token triggers lockout which also blocks the correct token`() {
        val g = gate()
        assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, "Bearer bad", "a", 0))
        assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, "Bearer bad", "a", 10))
        assertEquals(Decision.LOCKED_OUT, g.decide("/api/x", true, "Bearer bad", "a", 20))
        assertEquals(Decision.LOCKED_OUT, g.decide("/api/x", true, validHeader, "a", 5_000))
        // 其他客户端不受影响；公开页面仍可访问
        assertEquals(Decision.ALLOW_AUTHENTICATED, g.decide("/api/x", true, validHeader, "b", 5_000))
        assertEquals(Decision.ALLOW_PUBLIC, g.decide("/console", true, null, "a", 5_000))
        // 锁定到期后恢复
        assertEquals(Decision.ALLOW_AUTHENTICATED, g.decide("/api/x", true, validHeader, "a", 10_021))
    }

    @Test
    fun `missing token does not count toward lockout`() {
        val g = gate()
        repeat(10) { assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, null, "a", it.toLong())) }
        assertEquals(Decision.ALLOW_AUTHENTICATED, g.decide("/api/x", true, validHeader, "a", 20))
    }

    @Test
    fun `failures outside window and successful auth reset the counter`() {
        val g = gate()
        g.decide("/api/x", true, "Bearer bad", "a", 0)
        g.decide("/api/x", true, "Bearer bad", "a", 10)
        // 窗口过期，重新计数
        assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, "Bearer bad", "a", 2_000))
        assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, "Bearer bad", "a", 2_010))
        // 成功清零
        assertEquals(Decision.ALLOW_AUTHENTICATED, g.decide("/api/x", true, validHeader, "a", 2_020))
        assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, "Bearer bad", "a", 2_030))
        assertEquals(Decision.UNAUTHORIZED, g.decide("/api/x", true, "Bearer bad", "a", 2_040))
    }

    @Test
    fun `limiter bounds tracked clients and keeps locked entries`() {
        val limiter = AuthFailureLimiter(maxFailures = 1, windowMs = 1_000, lockoutMs = 10_000, maxTrackedClients = 2)
        assertTrue(limiter.recordFailure("locked", 0))
        val soft = AuthFailureLimiter(maxFailures = 5, windowMs = 1_000, lockoutMs = 10_000, maxTrackedClients = 2)
        soft.recordFailure("x", 0)
        repeat(5) { soft.recordFailure("locked", 0) }
        soft.recordFailure("y", 0)
        soft.recordFailure("z", 0)
        assertTrue(soft.isLockedOut("locked", 1))
        assertFalse(soft.isLockedOut("x", 1))
        assertTrue(limiter.isLockedOut("locked", 1))
    }
}
