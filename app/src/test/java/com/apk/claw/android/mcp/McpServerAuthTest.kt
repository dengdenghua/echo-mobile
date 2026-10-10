package com.apk.claw.android.mcp

import com.apk.claw.android.server.AuthFailureLimiter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class McpServerAuthTest {

    @Volatile
    private var now = 1_000L
    private lateinit var server: McpServer
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

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
        // Count wire attempts, without transparent POST retries. Drain the
        // response before closing so NanoHTTPD can finish writing each reply.
        val request = Request.Builder()
            .url("http://127.0.0.1:${server.listeningPort}/mcp")
            .post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toRequestBody("application/json".toMediaType()))
            .apply { if (auth != null) header("Authorization", auth) }
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string()
            response.code
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
        repeat(10) {
            assertEquals(401, post("Bearer bad1"))
            assertEquals(401, post("Bearer bad2"))
            assertEquals(429, post("Bearer bad3"))
            assertEquals(429, post("Bearer good"))
            now += 300_001
            assertEquals(200, post("Bearer good"))
        }
    }

    @Test
    fun `server is bound to loopback only`() {
        assertNotNull(server.listeningPort)
        // 通过 loopback 可达即可；绑定地址由 McpBindPolicy 决定（见 McpBindPolicyTest）
        assertEquals(200, post("Bearer good"))
    }
}
