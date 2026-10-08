package com.apk.claw.android.server

import com.apk.claw.android.server.LocalControlAccessGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalControlAccessGateTicketTest {

    private val stream = "/api/screen/stream"
    private val store = StreamTicketStore()
    private fun gate() = LocalControlAccessGate(
        AuthFailureLimiter(maxFailures = 3, windowMs = 1_000, lockoutMs = 10_000),
        { store.consume(it, 0) },
        { it == "Bearer good" },
    )

    @Test
    fun `valid ticket is accepted once without bearer header`() {
        val g = gate()
        val t = store.issue(0)
        assertEquals(Decision.ALLOW_AUTHENTICATED, g.decideTicket(t, "ip", 0))
        assertEquals(Decision.UNAUTHORIZED, g.decideTicket(t, "ip", 0))
    }

    @Test
    fun `bearer token passed as ticket value is rejected`() {
        val g = gate()
        assertEquals(Decision.UNAUTHORIZED, g.decideTicket("good", "ip", 0))
        assertEquals(Decision.UNAUTHORIZED, g.decideTicket("Bearer good", "ip", 0))
    }

    @Test
    fun `bad tickets lock the source out`() {
        val g = gate()
        assertEquals(Decision.UNAUTHORIZED, g.decideTicket("x1", "a", 0))
        assertEquals(Decision.UNAUTHORIZED, g.decideTicket("x2", "a", 1))
        assertEquals(Decision.LOCKED_OUT, g.decideTicket("x3", "a", 2))
        assertEquals(Decision.LOCKED_OUT, g.decideTicket(store.issue(0), "a", 3))
    }

    @Test
    fun `stream without ticket still needs the bearer header`() {
        val g = gate()
        assertEquals(Decision.UNAUTHORIZED, g.decide(stream, true, null, "ip", 0))
        assertEquals(Decision.ALLOW_AUTHENTICATED, g.decide(stream, true, "Bearer good", "ip", 0))
    }
}
