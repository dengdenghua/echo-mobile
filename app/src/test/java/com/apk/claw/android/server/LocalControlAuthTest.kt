package com.apk.claw.android.server

import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本机控制面共享 Bearer token 单测（纯 JVM，无 Robolectric）。
 *
 * 安全要点：
 *  - 只接受 `Authorization: Bearer <token>`，绝不接受 query token（防泄漏到 URL/代理日志）。
 *  - Bearer 前缀大小写不敏感（RFC 7235），但 token 本体必须完全一致。
 *  - 9527(ConfigServer) 与 9528(McpServer) 共用同一 token，轮换后旧的立即失效。
 */
class LocalControlAuthTest {

    @Before
    fun setUp() {
        KVUtils.resetForTest()
        LocalControlAuth.resetForTest()
    }

    @After
    fun tearDown() {
        KVUtils.resetForTest()
        LocalControlAuth.resetForTest()
    }

    @Test
    fun `generated token is url-safe base64 without padding`() {
        val token = LocalControlAuth.generateToken()
        assertEquals(32, token.length)
        assertFalse(token.contains("+"))
        assertFalse(token.contains("/"))
        assertFalse(token.contains("="))
    }

    @Test
    fun `getOrCreateToken is stable until rotated`() {
        val t1 = LocalControlAuth.getOrCreateToken()
        val t2 = LocalControlAuth.getOrCreateToken()
        assertEquals(t1, t2)
        assertTrue(t1.isNotBlank())
    }

    @Test
    fun `authorizes matching bearer header`() {
        val token = LocalControlAuth.getOrCreateToken()
        assertTrue(LocalControlAuth.isAuthorized("Bearer $token"))
    }

    @Test
    fun `bearer prefix is case-insensitive and trimmed`() {
        val token = LocalControlAuth.getOrCreateToken()
        assertTrue(LocalControlAuth.isAuthorized("bearer $token"))
        assertTrue(LocalControlAuth.isAuthorized("BEARER   $token  "))
    }

    @Test
    fun `rejects missing header`() {
        LocalControlAuth.getOrCreateToken()
        assertFalse(LocalControlAuth.isAuthorized(null))
        assertFalse(LocalControlAuth.isAuthorized(""))
        assertFalse(LocalControlAuth.isAuthorized("   "))
    }

    @Test
    fun `rejects wrong token and non-bearer schemes`() {
        LocalControlAuth.getOrCreateToken()
        assertFalse(LocalControlAuth.isAuthorized("Bearer wrong-token"))
        // 裸 token（缺 Bearer 前缀）不算通过，避免格式漂移
        assertFalse(LocalControlAuth.isAuthorized(LocalControlAuth.getOrCreateToken()))
    }

    @Test
    fun `rejects query-style credential`() {
        val token = LocalControlAuth.getOrCreateToken()
        // 只认 Header：把 token 放进 URL/查询参数不应被接受
        assertFalse(LocalControlAuth.isAuthorized("?token=$token"))
        assertFalse(LocalControlAuth.isAuthorized("token=$token"))
    }

    @Test
    fun `rotate invalidates the old token immediately`() {
        val old = LocalControlAuth.getOrCreateToken()
        val new = LocalControlAuth.rotateToken()
        assertNotEquals(old, new)
        assertFalse("old token must be rejected after rotation",
            LocalControlAuth.isAuthorized("Bearer $old"))
        assertTrue("new token must work right after rotation",
            LocalControlAuth.isAuthorized("Bearer $new"))
    }

    @Test
    fun `9527 and 9528 share the same token`() {
        // ConfigServer.authToken 与 McpServerBootstrap.authToken 都走 LocalControlAuth，
        // 任一入口轮换后另一入口立即同步。
        val token = LocalControlAuth.getOrCreateToken()
        assertTrue(LocalControlAuth.isAuthorized("Bearer $token"))
        val rotated = LocalControlAuth.rotateToken()
        assertTrue(LocalControlAuth.isAuthorized("Bearer $rotated"))
        assertFalse(LocalControlAuth.isAuthorized("Bearer $token"))
    }
}
