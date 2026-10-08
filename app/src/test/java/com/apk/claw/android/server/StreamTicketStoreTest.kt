package com.apk.claw.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTicketStoreTest {

    @Test
    fun `issued ticket is opaque and consumable once`() {
        val s = StreamTicketStore()
        val t = s.issue(0)
        assertTrue(t.length >= 32)
        assertTrue(s.consume(t, 1))
        assertFalse("reuse must fail", s.consume(t, 2))
    }

    @Test
    fun `ticket expires after ttl`() {
        val s = StreamTicketStore(ttlMs = 30_000)
        val t = s.issue(0)
        assertFalse(s.consume(t, 30_000))
        val t2 = s.issue(0)
        assertTrue(s.consume(t2, 29_999))
    }

    @Test
    fun `unknown, blank, null and oversized tickets are rejected`() {
        val s = StreamTicketStore()
        s.issue(0)
        assertFalse(s.consume("nope", 1))
        assertFalse(s.consume("", 1))
        assertFalse(s.consume(null, 1))
        assertFalse(s.consume("a".repeat(10_000), 1))
    }

    @Test
    fun `tickets are unique and independent`() {
        val s = StreamTicketStore()
        val a = s.issue(0)
        val b = s.issue(0)
        assertNotEquals(a, b)
        assertTrue(s.consume(b, 1))
        assertTrue(s.consume(a, 1))
    }

    @Test
    fun `store is capped and purges expired entries`() {
        val s = StreamTicketStore(ttlMs = 1_000, maxTickets = 3)
        val first = s.issue(0)
        repeat(3) { s.issue(10) }
        assertEquals(3, s.size())
        assertFalse("oldest evicted", s.consume(first, 20))
        s.issue(5_000) // purges the expired ones
        assertEquals(1, s.size())
    }
}
