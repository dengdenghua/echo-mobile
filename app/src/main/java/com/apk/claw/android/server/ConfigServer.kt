package com.apk.claw.android.server

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.server.routes.*
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

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
        private const val AUTH_TOKEN_KEY = "config_server_auth_token"
        private const val AUTH_TOKEN_BYTES = 24
        private val SECURE_RANDOM = SecureRandom()

        /** 生成 24 字节随机 token（base64url，~32 字符） */
        fun generateAuthToken(): String {
            val bytes = ByteArray(AUTH_TOKEN_BYTES)
            SECURE_RANDOM.nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private val gson = Gson()
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
    val authToken: String by lazy {
        KVUtils.getString(AUTH_TOKEN_KEY).takeIf { it.isNotEmpty() }
            ?: ConfigServer.generateAuthToken().also { KVUtils.putString(AUTH_TOKEN_KEY, it) }
    }

    /**
     * 校验请求的 token。
     * 仅接受 HTTP 头: Authorization: Bearer <token>
     * 查询参数 ?token=<token> 已禁用，防止 token 泄漏到浏览器历史 / 代理日志 / Referer。
     * 防止同 WiFi 邻居未授权访问配网页面。
     */
    private fun validateAuth(session: IHTTPSession): Boolean {
        val provided = session.headers["authorization"]
            ?.removePrefix("Bearer ")?.trim()
            ?: return false
        // 恒定时间比较，避免 token 时序泄露
        return constantTimeEquals(provided, authToken)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
    }

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

        // 鉴权：放行 H5 页面、console 页面；debug.html 也需鉴权(即使 DEBUG 构建也不应无鉴权暴露)
        val isPublic = uri == "/" || uri == "/index.html" ||
            uri == "/console" || uri == "/console.html"
        if (!isPublic && !validateAuth(session)) {
            routeContext.recordRemoteAccess(session, "auth_denied", false, "uri=$uri", System.currentTimeMillis())
            return unauthorizedResponse()
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

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    /** 网页遥控台:实时屏幕(MJPEG)+ 点击/滑动/键盘 -> /api/control/input。页面公开,API 仍要 token。 */
    private fun serveConsoleHtml(): Response {
        val html = context.assets.open("web/console.html").bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    /** Debug 页面（仅 DEBUG 构建） */
    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

}
