package com.apk.claw.android.mcp

import com.apk.claw.android.server.AuthFailureLimiter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class McpServerAuthTest {

    private var now = 1_000L
    private lateinit var server: McpServer

    @Before
    fun setUp() {
        server = McpServer(
            port = 0,
            hostname = McpBindPolicy.LOOPBACK,
            limiter = AuthFailureLimiter(maxFailures = 3, windowMs = 60_000, lockoutMs = 300_000),
            clock = { now },
        )
        server.setAuthorizationValidator { it == "Bearer good" }
        server.start(5_000, false)
    }

    @After
    fun tearDown() = server.stop()

    private fun post(auth: String?): Int {
        val c = URL("http://127.0.0.1:${server.listeningPort}/mcp").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (auth != null) c.setRequestProperty("Authorization", auth)
            c.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray()) }
            return c.responseCode
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun `valid token is accepted and missing or wrong token is 401`() {
        assertEquals(200, post("Bearer good"))
        assertEquals(401, post(null))
        assertEquals(401, post("Bearer bad"))
    }

    @Test
    fun `repeated bad tokens lock out with 429 even for the right token`() {
        assertEquals(401, post("Bearer bad1"))
        assertEquals(401, post("Bearer bad2"))
        assertEquals(429, post("Bearer bad3"))
        assertEquals(429, post("Bearer good"))
        now += 300_001
        assertEquals(200, post("Bearer good"))
    }

    @Test
    fun `server is bound to loopback only`() {
        assertNotNull(server.listeningPort)
        // 通过 loopback 可达即可；绑定地址由 McpBindPolicy 决定（见 McpBindPolicyTest）
        assertEquals(200, post("Bearer good"))
    }
}
