package com.apk.claw.android.octopus_mobile.safety

/**
 * WebView 统一 URL 策略 —— P1-10 的唯一裁决点。
 *
 * 背景：浏览器引擎（[com.apk.claw.android.octopus_mobile.browser.SystemWebViewEngine]）、
 * WebActivity、MiniAppHost 三处各自实现了 URL / scheme 判断，且 SystemWebViewEngine 的
 * `shouldOverrideUrlLoading` 此前**无条件返回 false**（把 file/content/javascript/intent 等
 * 全部交回 WebView 自行处理），`navigate()` 更是完全不做校验。
 *
 * 本对象把「能不能导航」「能不能当子资源加载」「能不能当本地页面」「jsBridge 能不能被这个文档调用」
 * 四类判断收敛成纯函数（不依赖 Android），便于在纯 JVM 单测里逐条钉住。
 *
 * 设计取舍（写成显式结论，避免后来者误读）：
 * - **导航白名单 = scheme 白名单**，不是域名白名单。浏览器必须能访问任意公网站点，把域名做成
 *   导航白名单会直接废掉浏览器；域名维度的约束落在 jsBridge / userscript 注入面上。
 * - **子资源**放宽到 `data:`/`blob:`/`about:`/`ws:`/`wss:`（内联资源、内联 iframe、长连接是常态），
 *   但 `file:`/`content:`/`javascript:`/`chrome:` 一律拦。
 * - **jsBridge 的域名白名单**由 [bridgeCallAllowed] 承担：只服务「我们确实注入过桥脚本的那个文档」，
 *   即 @match 命中的站点。未命中站点拿不到桥；跨域 iframe 因同源策略读不到顶层文档注入的凭据，
 *   同样拿不到（详见该函数注释）。
 */
object WebViewUrlPolicy {

    /** 允许留空的占位页（引擎销毁时用它清屏）。 */
    const val BLANK_PAGE = "about:blank"

    const val REASON_OK = "ok"
    const val REASON_EMPTY = "empty_url"
    const val REASON_NO_SCHEME = "missing_scheme"
    const val REASON_NO_HOST = "missing_host"
    const val REASON_BLANK_BLOCKED = "blank_not_allowed"
    const val REASON_FILE_OUTSIDE_SANDBOX = "file_outside_allowed_roots"

    /** 可导航（顶层文档）与可下载的 scheme。 */
    private val WEB_SCHEMES = setOf("http", "https")

    /** 子资源允许的 scheme。 */
    private val SUBRESOURCE_SCHEMES = WEB_SCHEMES + setOf("data", "blob", "about", "ws", "wss")

    /** 匹配 `scheme:` 前缀；未匹配返回空串（失败关闭，不猜）。 */
    private val SCHEME_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    /** authority 的终止字符。 */
    private const val AUTHORITY_DELIMITERS = "/?#"

    /** 判定结果；`reason` 只用于日志与审计，不要拿它做业务分支。 */
    data class Decision(
        val allow: Boolean,
        val reason: String,
        val url: String = "",
    )

    /**
     * 顶层导航裁决：只放行 http/https（外加显式允许的 [BLANK_PAGE]）。
     *
     * @param allowBlank 引擎内部清屏（about:blank）需要放行；用户/网页发起的导航传 false。
     */
    fun navigation(url: String?, allowBlank: Boolean = true): Decision {
        val u = url?.trim().orEmpty()
        val scheme = schemeOf(u)
        return when {
            u.isEmpty() -> Decision(false, REASON_EMPTY, u)
            isBlankPage(u) -> Decision(
                allowBlank,
                if (allowBlank) REASON_OK else REASON_BLANK_BLOCKED,
                u,
            )
            scheme.isEmpty() -> Decision(false, REASON_NO_SCHEME, u)
            scheme !in WEB_SCHEMES -> Decision(false, "scheme_not_allowed:$scheme", u)
            hostOf(u) == null -> Decision(false, REASON_NO_HOST, u)
            else -> Decision(true, REASON_OK, u)
        }
    }

    /** 子资源裁决：见类注释的白名单说明。 */
    fun subresource(url: String?): Decision {
        val u = url?.trim().orEmpty()
        val scheme = schemeOf(u)
        return when {
            u.isEmpty() -> Decision(false, REASON_EMPTY, u)
            scheme.isEmpty() -> Decision(false, REASON_NO_SCHEME, u)
            scheme !in SUBRESOURCE_SCHEMES -> Decision(false, "scheme_not_allowed:$scheme", u)
            scheme in WEB_SCHEMES && hostOf(u) == null -> Decision(false, REASON_NO_HOST, u)
            else -> Decision(true, REASON_OK, u)
        }
    }

    /**
     * 本地页面（file://）裁决 —— 只放行落在调用方给定沙箱根内的路径。
     *
     * MiniAppHost 需要加载 `file:///android_asset/plugins/<id>/...`，而第三方小程序不得借导航去读
     * `/data/data/<pkg>/shared_prefs 下的 *.xml` 这类应用私有文件，因此 file 必须按根白名单收窄。
     */
    fun fileNavigation(url: String?, allowedPrefixes: Collection<String>): Decision {
        val u = url?.trim().orEmpty()
        val inside = u.isNotEmpty() && allowedPrefixes.any { it.isNotEmpty() && u.startsWith(it) }
        return if (inside) {
            Decision(true, REASON_OK, u)
        } else {
            Decision(false, REASON_FILE_OUTSIDE_SANDBOX, u)
        }
    }

    /**
     * jsBridge 调用裁决 —— 只有「当前文档」与「注入桥脚本时的文档」同源时才服务。
     *
     * 为什么这样够用：桥脚本经 `evaluateJavascript` 注入，只落在**顶层文档**的 JS 世界；跨域 iframe
     * 受同源策略约束，读不到顶层文档作用域里的桥凭据，因此无法借 iframe 侧信道拿到桥能力。
     */
    fun bridgeCallAllowed(currentUrl: String?, injectedUrl: String?): Boolean {
        val current = originOf(currentUrl)
        val injected = originOf(injectedUrl)
        return current != null && current == injected && navigation(currentUrl, allowBlank = false).allow
    }

    /** 取 scheme（小写）；缺失/非法返回空串。 */
    fun schemeOf(raw: String?): String {
        val u = raw?.trim().orEmpty()
        return SCHEME_PATTERN.find(u)?.groupValues?.get(1)?.lowercase().orEmpty()
    }

    /**
     * 宽松取 host（小写；剥掉用户信息、端口与 IPv6 方括号；结尾点一并去掉）。
     *
     * 故意不用 `java.net.URI` 做这件事：现实 URL 常含未编码空格与中文，URI 会直接抛异常，
     * 拿它当「能否导航」的依据会把正常网址误杀。这里只做防御性裁剪，异常输入一律返回 null。
     */
    fun hostOf(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        val scheme = schemeOf(u)
        val authority = authorityOf(u, scheme)
        val hostPort = authority.substringAfterLast('@').trim()
        return when {
            u.isEmpty() || isBlankPage(u) -> null
            scheme.isEmpty() -> null
            hostPort.isEmpty() -> null
            hostPort.startsWith("[") -> hostPort.substringAfter('[').substringBefore(']').trimEnd('.')
            else -> hostPort.substringBefore(':').trim().trimEnd('.').lowercase()
        }?.takeIf { it.isNotEmpty() }
    }

    /** 取 `scheme://host[:port]` 形式的 origin；无可信 host 时返回 null。 */
    fun originOf(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        val scheme = schemeOf(u)
        val host = hostOf(u)
        val port = if (scheme.isEmpty() || host == null) "" else portOf(u)
        return when {
            scheme.isEmpty() || host == null -> null
            port.isEmpty() -> "$scheme://$host"
            else -> "$scheme://$host:$port"
        }
    }

    private fun isBlankPage(u: String): Boolean = u.equals(BLANK_PAGE, ignoreCase = true)

    /** 取 authority 段（`scheme://` 与首个 `/?#` 之间）；无 authority 返回空串。 */
    private fun authorityOf(u: String, scheme: String): String {
        val afterScheme = if (scheme.isEmpty()) "" else u.substring(scheme.length + 1)
        val body = if (afterScheme.startsWith("//")) afterScheme.removePrefix("//") else ""
        return body.takeWhile { it !in AUTHORITY_DELIMITERS }
    }

    /** 取显式端口；未写端口或非数字端口返回空串。 */
    private fun portOf(u: String): String {
        val hostPort = authorityOf(u, schemeOf(u)).substringAfterLast('@')
        return when {
            hostPort.startsWith("[") -> hostPort.substringAfter(']', "").removePrefix(":").takeWhile { it.isDigit() }
            !hostPort.contains(':') -> ""
            else -> hostPort.substringAfter(':').takeWhile { it.isDigit() }
        }
    }
}
