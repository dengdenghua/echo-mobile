package com.apk.claw.android.server

/**
 * 9527 ConfigServer 的纯逻辑鉴权决策（不依赖 NanoHTTPD / Android，便于单测）。
 *
 * - 公开路径只有固定白名单中的静态页面/静态资源（GET），它们不含任何密钥或设备状态：
 *   页面只是一个“空壳”，token 由用户在 URL fragment（#token=...）中提供，fragment 不会发往服务器，
 *   所有 API 调用仍需 `Authorization: Bearer <token>`。
 * - 其余路径必须携带正确 token。
 * - 对“携带了错误 token”的客户端按来源 IP 计数，窗口内失败过多即锁定一段时间（429），
 *   锁定期间即使 token 正确也拒绝，防止局域网内暴力枚举。
 */
class LocalControlAccessGate(
    private val limiter: AuthFailureLimiter = AuthFailureLimiter(),
    private val isTokenValid: (String?) -> Boolean = LocalControlAuth::isAuthorized,
) {

    enum class Decision { ALLOW_PUBLIC, ALLOW_AUTHENTICATED, UNAUTHORIZED, LOCKED_OUT }

    fun decide(
        uri: String,
        isGet: Boolean,
        authorizationHeader: String?,
        clientKey: String,
        nowMs: Long,
    ): Decision {
        return when {
            isGet && isPublicPath(uri) -> Decision.ALLOW_PUBLIC
            limiter.isLockedOut(clientKey, nowMs) -> Decision.LOCKED_OUT
            // 未携带 token 不计入失败次数（浏览器首次探测等），只有“猜错 token”才计数
            authorizationHeader.isNullOrBlank() -> Decision.UNAUTHORIZED
            isTokenValid(authorizationHeader) -> {
                limiter.recordSuccess(clientKey)
                Decision.ALLOW_AUTHENTICATED
            }
            limiter.recordFailure(clientKey, nowMs) -> Decision.LOCKED_OUT
            else -> Decision.UNAUTHORIZED
        }
    }

    companion object {
        /** 无需 token 的 HTML 页面（静态空壳，不嵌入任何 token / 配置 / 设备状态）。 */
        val PUBLIC_PAGES: Set<String> = setOf("/", "/index.html", "/console", "/console.html")

        /** 页面引用的静态资源：URI -> assets 相对路径 + MIME。仅白名单，杜绝路径穿越。 */
        val PUBLIC_STATIC_ASSETS: Map<String, Pair<String, String>> = mapOf(
            "/tokens.css" to ("web/tokens.css" to "text/css"),
            "/style.css" to ("web/style.css" to "text/css"),
            "/app.js" to ("web/app.js" to "application/javascript"),
            "/console-style.css" to ("web/console-style.css" to "text/css"),
            "/console-app.js" to ("web/console-app.js" to "application/javascript"),
        )

        fun isPublicPath(uri: String): Boolean = uri in PUBLIC_PAGES || uri in PUBLIC_STATIC_ASSETS
    }
}

/**
 * 简单的按客户端失败计数 + 锁定。线程安全；跟踪的客户端数量有上限，避免内存被撑爆。
 */
class AuthFailureLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val lockoutMs: Long = DEFAULT_LOCKOUT_MS,
    private val maxTrackedClients: Int = DEFAULT_MAX_TRACKED_CLIENTS,
) {
    private class State(var windowStartMs: Long, var failures: Int, var lockedUntilMs: Long)

    private val states = LinkedHashMap<String, State>()

    @Synchronized
    fun isLockedOut(clientKey: String, nowMs: Long): Boolean {
        val state = states[clientKey]
        val locked = state != null && state.lockedUntilMs > nowMs
        if (state != null && !locked && state.lockedUntilMs != 0L) {
            // 锁定已过期：清零重新计数
            states.remove(clientKey)
        }
        return locked
    }

    /** 记录一次失败；返回 true 表示该客户端因此进入（或仍处于）锁定状态。 */
    @Synchronized
    fun recordFailure(clientKey: String, nowMs: Long): Boolean {
        val state = states[clientKey]
            ?.takeIf { nowMs - it.windowStartMs < windowMs || it.lockedUntilMs > nowMs }
            ?: State(nowMs, 0, 0L).also { put(clientKey, it) }
        state.failures++
        if (state.failures >= maxFailures) {
            state.lockedUntilMs = nowMs + lockoutMs
            return true
        }
        return false
    }

    @Synchronized
    fun recordSuccess(clientKey: String) {
        states.remove(clientKey)
    }

    private fun put(clientKey: String, state: State) {
        states.remove(clientKey)
        if (states.size >= maxTrackedClients) {
            // 优先淘汰未锁定的最旧条目，保留锁定记录
            val victim = states.entries.firstOrNull { it.value.lockedUntilMs == 0L }?.key
                ?: states.keys.first()
            states.remove(victim)
        }
        states[clientKey] = state
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 10
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_LOCKOUT_MS = 5 * 60_000L
        const val DEFAULT_MAX_TRACKED_CLIENTS = 256
    }
}
