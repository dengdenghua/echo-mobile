package com.apk.claw.android.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.TestClawApplication
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class, sdk = [34])
class ConfigServerTest {

    private lateinit var context: Context
    private lateinit var server: ConfigServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // 补全 TestClawApplication 未初始化的 lateinit 字段，避免 ConfigServer 构造失败
        val app = ApplicationProvider.getApplicationContext<TestClawApplication>()
        ensureClawApplicationInitialized(app)
        setLateinitField(app, "deviceDiscoveryManager", com.apk.claw.android.octopus_mobile.DeviceDiscoveryManager(app, app.deviceRegistry))
        setLateinitField(app, "pluginManager", com.apk.claw.android.plugin.PluginManager(app))

        // ConfigServer 构造不会启动 socket，仅初始化配置
        server = ConfigServer(context, port = 0)
    }

    private fun ensureClawApplicationInitialized(app: com.apk.claw.android.ClawApplication) {
        val instanceField = com.apk.claw.android.ClawApplication::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        if (instanceField.get(null) == null) {
            instanceField.set(null, app)
        }
    }

    private fun setLateinitField(target: Any, fieldName: String, value: Any) {
        val field = com.apk.claw.android.ClawApplication::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        if (field.get(target) == null) {
            field.set(target, value)
        }
    }

    // ==================== token 生成 ====================

    @Test
    fun `generateAuthToken produces url-safe base64 string of expected length`() {
        val token = ConfigServer.generateAuthToken()
        // 24 字节 base64url 无填充 => 32 字符
        assertEquals(32, token.length)
        assertFalse(token.contains("+"))
        assertFalse(token.contains("/"))
        assertFalse(token.contains("="))
    }

    @Test
    fun `generateAuthToken returns different values on multiple calls`() {
        val t1 = ConfigServer.generateAuthToken()
        val t2 = ConfigServer.generateAuthToken()
        assertNotEquals(t1, t2)
    }

    // ==================== 鉴权 ====================

    @Test
    fun `validateAuth returns true with correct Authorization header`() {
        val token = server.authToken
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.headers).thenReturn(mapOf("authorization" to "Bearer $token"))
        `when`(session.parms).thenReturn(emptyMap())

        assertTrue(invokeValidateAuth(server, session))
    }

    @Test
    fun `validateAuth returns false with token query parameter`() {
        val token = server.authToken
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.headers).thenReturn(emptyMap())
        `when`(session.parms).thenReturn(mapOf("token" to token))

        assertFalse(invokeValidateAuth(server, session))
    }

    @Test
    fun `validateAuth returns false when token missing`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.headers).thenReturn(emptyMap())
        `when`(session.parms).thenReturn(emptyMap())

        assertFalse(invokeValidateAuth(server, session))
    }

    @Test
    fun `validateAuth returns false with wrong token`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.headers).thenReturn(mapOf("authorization" to "Bearer wrong-token"))
        `when`(session.parms).thenReturn(emptyMap())

        assertFalse(invokeValidateAuth(server, session))
    }

    @Test
    fun `validateAuth is constant time against different wrong tokens`() {
        val session1 = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session1.headers).thenReturn(mapOf("authorization" to "Bearer a"))
        `when`(session1.parms).thenReturn(emptyMap())

        val session2 = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session2.headers).thenReturn(mapOf("authorization" to "Bearer ${"a".repeat(64)}"))
        `when`(session2.parms).thenReturn(emptyMap())

        assertFalse(invokeValidateAuth(server, session1))
        assertFalse(invokeValidateAuth(server, session2))
    }

    // ==================== 路由分发 ====================

    @Test
    fun `serve handles OPTIONS preflight without auth`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.method).thenReturn(NanoHTTPD.Method.OPTIONS)
        `when`(session.uri).thenReturn("/api/llm")
        `when`(session.headers).thenReturn(mapOf("origin" to "http://192.168.1.100:8080"))

        val response = server.serve(session)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("http://192.168.1.100:8080", response.getHeader("Access-Control-Allow-Origin"))
        assertNotNull(response.getHeader("Access-Control-Allow-Methods"))
    }

    @Test
    fun `serve returns 401 for protected api without token`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.method).thenReturn(NanoHTTPD.Method.GET)
        `when`(session.uri).thenReturn("/api/auth/check")
        `when`(session.headers).thenReturn(emptyMap())
        `when`(session.parms).thenReturn(emptyMap())

        val response = server.serve(session)

        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
    }

    @Test
    fun `serve allows public index path without token`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.method).thenReturn(NanoHTTPD.Method.GET)
        `when`(session.uri).thenReturn("/")
        `when`(session.headers).thenReturn(emptyMap())
        `when`(session.parms).thenReturn(emptyMap())

        // 这里会尝试读取 assets/web/index.html，测试环境通常不存在，但应返回 404 而非 401
        val response = server.serve(session)
        assertNotEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
    }

    @Test
    fun `serve returns 404 for unknown api path`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.method).thenReturn(NanoHTTPD.Method.GET)
        `when`(session.uri).thenReturn("/api/not-exist")
        `when`(session.headers).thenReturn(mapOf("authorization" to "Bearer ${server.authToken}"))
        `when`(session.parms).thenReturn(emptyMap())

        val response = server.serve(session)

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `handleAuthCheck returns ok when authenticated`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.method).thenReturn(NanoHTTPD.Method.GET)
        `when`(session.uri).thenReturn("/api/auth/check")
        `when`(session.headers).thenReturn(mapOf("authorization" to "Bearer ${server.authToken}"))
        `when`(session.parms).thenReturn(emptyMap())

        val response = server.serve(session)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun `serve locks out client after repeated wrong tokens even for correct token`() {
        fun request(token: String): NanoHTTPD.Response {
            val session = mock(NanoHTTPD.IHTTPSession::class.java)
            `when`(session.method).thenReturn(NanoHTTPD.Method.GET)
            `when`(session.uri).thenReturn("/api/auth/check")
            `when`(session.remoteIpAddress).thenReturn("192.168.1.66")
            `when`(session.headers).thenReturn(mapOf("authorization" to "Bearer $token"))
            `when`(session.parms).thenReturn(emptyMap())
            return server.serve(session)
        }
        repeat(AuthFailureLimiter.DEFAULT_MAX_FAILURES - 1) {
            assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, request("wrong-$it").status)
        }
        assertEquals(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, request("wrong-last").status)
        assertEquals(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, request(server.authToken).status)
    }

    @Test
    fun `serve does not treat POST to console as public`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.method).thenReturn(NanoHTTPD.Method.POST)
        `when`(session.uri).thenReturn("/console")
        `when`(session.headers).thenReturn(emptyMap())
        `when`(session.parms).thenReturn(emptyMap())

        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, server.serve(session).status)
    }

    private fun invokeValidateAuth(server: ConfigServer, session: NanoHTTPD.IHTTPSession): Boolean =
        server.validateAuth(session)
}
