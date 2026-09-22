package com.apk.claw.android.server

import com.apk.claw.android.utils.KVUtils
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 本机控制面的共享 Bearer token。
 *
 * 9527 ConfigServer 与 9528 MCP Server 共用同一个持久化 token：
 * - 首次使用时生成并安全存储（KVUtils 将 `config_server_auth_token` 视为敏感 Key）。
 * - 校验只接受 `Authorization: Bearer <token>`，不接受 query token。
 * - 轮换后立即生效，不需要重启 ConfigServer/McpServer。
 */
object LocalControlAuth {

    const val TOKEN_KEY = "config_server_auth_token"
    private const val TOKEN_BYTES = 24
    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedToken: String? = null

    /** 获取当前 token；不存在时生成并持久化。 */
    @Synchronized
    fun getOrCreateToken(): String {
        cachedToken?.takeIf { it.isNotBlank() }?.let { return it }

        val stored = KVUtils.getString(TOKEN_KEY, "").trim()
        if (stored.isNotEmpty()) {
            cachedToken = stored
            return stored
        }

        return generateToken().also {
            cachedToken = it
            KVUtils.putString(TOKEN_KEY, it)
        }
    }

    /** 轮换 token；旧 token 立即失效。 */
    @Synchronized
    fun rotateToken(): String {
        return generateToken().also {
            cachedToken = it
            KVUtils.putString(TOKEN_KEY, it)
        }
    }

    /** 只校验标准 Bearer Header，不接受 query parameter。 */
    fun isAuthorized(authorizationHeader: String?): Boolean {
        val expected = getOrCreateToken()
        val provided = authorizationHeader
            ?.trim()
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * 测试专用：清空内存缓存，配合 [KVUtils.resetForTest] 隔离单测间的状态污染。
     * 生产代码不要调用。
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        cachedToken = null
    }

    /** 生成 24 字节 URL-safe 随机 token（无 padding，共 32 字符）。 */
    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private const val BEARER_PREFIX = "Bearer "
}
