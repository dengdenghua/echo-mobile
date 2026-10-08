package com.apk.claw.android.server

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.server.routes.*

import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

/**
 * 局域网 HTTP 配置服务器
 * 提供 H5 页面用于在电脑浏览器上配置钉钉/飞书 key
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT,
    hostname: String? = null
) : NanoHTTPD(hostname, port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
        /** 生成 24 字节随机 token（base64url，共 32 字符）。 */
        fun generateAuthToken(): String = LocalControlAuth.generateToken()
    }

    private val gson = Gson()
    private val accessGate = LocalControlAccessGate()
    private val routeContext = RouteContext(context, gson)
    private val handlers: List<RouteHandler> = listOf(
        ScreenHandler(),
        ChannelRouteHandler(),
        CastRouteHandler(context),
        DeviceRouteHandler(),
        AgentRouteHandler(),
        FileRouteHandler(context),
        DebugRouteHandler(context),
        McpRouteHandler(),
        KnowledgeRouteHandler(),
    )

    /** 当前生效的鉴权 token（首次启动时持久化到 KVUtils） */
    val authToken: String get() = LocalControlAuth.getOrCreateToken()

    /**
     * 校验请求的 token（经 [accessGate] 统一决策，含失败锁定）。
     * 仅接受 HTTP 头: Authorization: Bearer <token>
     * 查询参数 ?token=<token> 已禁用，防止 token 泄漏到浏览器历史 / 代理日志 / Referer。
     * 防止同 WiFi 邻居未授权访问配网页面。
     */
    internal fun validateAuth(session: IHTTPSession): Boolean =
        LocalControlAuth.isAuthorized(session.headers["authorization"])

    private fun unauthorizedResponse(): Response = routeContext.corsResponse(
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED, MIME_JSON,
            """{"code":401,"message":"未授权,请通过 Authorization: Bearer <token> 传入访问令牌"}"""
        )
    )

    override fun serve(session: IHTTPSession): Response {
        routeContext.bindRequestOrigin(session)
        return try {
            // 隐藏 NanoHTTPD 默认 Server 头(含版本信息),统一改为通用值
            serveInternal(session).also { it.addHeader("Server", "octopus") }
        } finally {
            routeContext.clearRequestOrigin()
        }
    }

    private fun serveInternal(session: IHTTPSession): Response {
        // CORS 预检请求
        if (session.method == Method.OPTIONS) {
            return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        val method = session.method

        // 鉴权：仅放行静态空壳页面/资源白名单（不含任何 token/配置/状态）；其余一律要求 Bearer token。
        // debug.html 也需鉴权(即使 DEBUG 构建也不应无鉴权暴露)。错误 token 过多的来源 IP 会被临时锁定。
        val source = routeContext.sourceOf(session)
        val decision = accessGate.decide(
            uri = uri,
            isGet = method == Method.GET,
            authorizationHeader = session.headers["authorization"],
            clientKey = source,
            nowMs = System.currentTimeMillis(),
        )
        when (decision) {
            LocalControlAccessGate.Decision.LOCKED_OUT -> {
                XLog.w(TAG, "Auth locked out for $source uri=$uri")
                val now = System.currentTimeMillis()
                routeContext.recordRemoteAccess(session, "auth_locked_out", false, "uri=$uri", now)
                return lockedOutResponse()
            }
            LocalControlAccessGate.Decision.UNAUTHORIZED -> {
                routeContext.recordRemoteAccess(session, "auth_denied", false, "uri=$uri", System.currentTimeMillis())
                return unauthorizedResponse()
            }
            LocalControlAccessGate.Decision.ALLOW_PUBLIC,
            LocalControlAccessGate.Decision.ALLOW_AUTHENTICATED -> Unit
        }

        return try {
            // 优先分发给独立 RouteHandler
            handlers.firstOrNull { it.canHandle(uri, method) }?.let {
                return it.handle(session, routeContext)
            }

            // 剩余路由：HTML 页面
            when {
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                (uri == "/console" || uri == "/console.html") && method == Method.GET -> serveConsoleHtml()
                method == Method.GET && uri in LocalControlAccessGate.PUBLIC_STATIC_ASSETS ->
                    serveStaticAsset(LocalControlAccessGate.PUBLIC_STATIC_ASSETS.getValue(uri))
                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()
                else -> routeContext.corsResponse(
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"code":-1,"message":"接口不存在"}"""
                    )
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            routeContext.recordRemoteAccess(session, "server_error", false, "error=${e.message}", System.currentTimeMillis())
            routeContext.corsResponse(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"Internal error"}"""
                )
            )
        }
    }

    // ==================== HTML 页面 ====================

    private fun lockedOutResponse(): Response = routeContext.corsResponse(
        newFixedLengthResponse(
            Response.Status.TOO_MANY_REQUESTS, MIME_JSON,
            """{"code":429,"message":"鉴权失败次数过多，请稍后再试"}"""
        )
    )

    /** 公开静态内容统一加上不缓存、不外泄 Referer、禁止被嵌入的头。 */
    private fun publicStatic(mime: String, body: String): Response =
        routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, mime, body)).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("X-Frame-Options", "DENY")
        }

    private fun serveStaticAsset(asset: Pair<String, String>): Response {
        val body = context.assets.open(asset.first).bufferedReader().use { it.readText() }
        return publicStatic(asset.second, body)
    }

    private fun serveHtml(): Response {
        val html = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        return publicStatic(MIME_HTML, html)
    }

    /**
     * 网页遥控台:实时屏幕(MJPEG)+ 点击/滑动/键盘 -> /api/control/input。
     * 页面是不含任何密钥/状态的静态空壳(token 来自 URL fragment，不会发送到服务器)，所有 API 仍要 token。
     */
    private fun serveConsoleHtml(): Response {
        val html = context.assets.open("web/console.html").bufferedReader().use { it.readText() }
        return publicStatic(MIME_HTML, html)
    }

    /** Debug 页面（仅 DEBUG 构建） */
    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

}
