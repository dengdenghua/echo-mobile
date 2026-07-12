package com.apk.claw.android.octopus_mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 系统 WebView 引擎 —— 兜底实现.
 *
 * 优点：
 *  - 0 包大
 *  - 永远可用
 *  - 由 Google Play 自动更新
 *
 * 缺点：
 *  - 反爬免疫度：⚠️ 中（指纹是 WebView 不是 Chrome）
 *  - 不支持 CRX 扩展
 *
 * 优化措施：
 *  - UA 改为桌面 Chrome UA
 *  - 启用 Cookie / DOM Storage / IndexedDB
 *  - 启用 JS / 不阻止弹窗
 *  - 开启 WebView 调试（dev tools）
 */
class SystemWebViewEngine : BrowserEngine {

    override val name = "System WebView"

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override fun events(): Flow<EngineEvent> = _events.asSharedFlow()

    private var activeWebView: WebView? = null
    private var currentUrlValue: String = ""
    private lateinit var gmBridge: GmApiBridge
    private var idleRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(context: Context): View {
        val webView = WebView(context)

        val settings: WebSettings = webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            databaseEnabled = true

            // setAcceptCookie 已迁移到 CookieManager（WebSettings.setAcceptCookie 在 API 36 弃用）
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)

            cacheMode = WebSettings.LOAD_DEFAULT

            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // 文字大小:浏览器设置里可调(默认 100)。
            textZoom = com.apk.claw.android.utils.KVUtils.getBrowserTextZoom()

            allowFileAccess = true
            allowContentAccess = true
            // 安全:禁止 file:// 页面跨源读取本地文件 / 其它 file:// 源。
            // 开着这两项时,恶意本地页面可越权读取 app 私有文件,浏览器场景必须关。
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            mediaPlaybackRequiresUserGesture = false

            // 安全:HTTPS 页面只放行被动混合内容(图片等),拦截 HTTP 脚本/iframe,
            // 防中间人注入。ALWAYS_ALLOW 会让"安全"连接被降级,改用 COMPATIBILITY。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // UA 优先级:
        // 1. StealthManager 启用(指纹保护开)→ 用当前轮换 profile 的 UA(覆盖桌面/移动模式),
        //    并同步 acceptLanguage / database / domStorage 等匹配项。
        // 2. 桌面模式开 → 桌面版 Chrome UA;关 → 保留 WebView 默认移动 UA(浏览器设置里可切)。
        if (StealthManager.isEnabled()) {
            applyStealthProfile(webView, StealthManager.getCurrent())
        } else if (com.apk.claw.android.utils.KVUtils.getBrowserDesktopMode()) {
            webView.settings.userAgentString = DESKTOP_CHROME_UA
        }

        // 仅 DEBUG 构建开启 WebView 远程调试，避免 release 版被 adb chrome://inspect 注入已登录会话。
        WebView.setWebContentsDebuggingEnabled(com.apk.claw.android.BuildConfig.DEBUG)

        gmBridge = GmApiBridge(context)

        // 反检测 stealth 脚本:在「文档开始前」注入(早于页面脚本读取 navigator.webdriver 等)。
        // 设备 WebView 支持 DOCUMENT_START_SCRIPT 时走 addDocumentStartJavaScript(可靠);
        // 不支持则退回 onPageStarted 用 evaluateJavascript 注入(稍晚,兜底)。
        val docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val stealthJs = BrowserPluginHost.documentStartScript()
        if (docStartSupported) {
            if (stealthJs != null) {
                runCatching { WebViewCompat.addDocumentStartJavaScript(webView, stealthJs, setOf("*")) }
                    .onFailure { Log.w("SystemWebViewEngine", "addDocumentStartJavaScript failed: ${it.message}") }
            }
        }

        gmBridge.attach(webView, "")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                currentUrlValue = url
                gmBridge.updateUrl(url)
                // 取消上一页的 idle 定时任务
                idleRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                // doc-start 不支持时的 stealth 兜底注入(尽早,但可能晚于部分 head 脚本)
                if (!docStartSupported) {
                    BrowserPluginHost.documentStartScript()?.let { js -> view.evaluateJavascript(js, null) }
                }
                // @run-at document-start userscripts:onPageStarted 注入(早于 onPageFinished,
                // 但不如 addDocumentStartJavaScript 早;在支持 DOCUMENT_START_SCRIPT 时也做一次以
                // 覆盖URL匹配逻辑——addDocumentStartJavaScript 是全局注册的,这里按URL过滤)
                BrowserPluginHost.scriptsForUrl(url, Userscript.RunAt.DOCUMENT_START).forEach { js ->
                    view.evaluateJavascript(js, null)
                }
                _events.tryEmit(EngineEvent.PageStarted)
            }
            override fun onPageFinished(view: WebView, url: String) {
                currentUrlValue = url
                gmBridge.updateUrl(url)
                // @run-at document-end:DOM 就绪后立即注入
                BrowserPluginHost.scriptsForUrl(url, Userscript.RunAt.DOCUMENT_END).forEach { js ->
                    view.evaluateJavascript(js, null)
                }
                // @run-at document-idle:onPageFinished 后延迟 200ms 注入,模拟 DOMContentLoaded+idle 时机
                val idleScripts = BrowserPluginHost.scriptsForUrl(url, Userscript.RunAt.DOCUMENT_IDLE)
                if (idleScripts.isNotEmpty()) {
                    val r = Runnable {
                        idleScripts.forEach { js -> view.evaluateJavascript(js, null) }
                    }
                    idleRunnable = r
                    Handler(Looper.getMainLooper()).postDelayed(r, 200)
                }
                _events.tryEmit(EngineEvent.PageFinished(url, view.title ?: ""))
            }
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean = false
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return if (BrowserPluginHost.shouldBlock(request.url?.toString())) {
                    android.webkit.WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                _events.tryEmit(EngineEvent.Error(
                    errorCode = error.errorCode,
                    description = error.description.toString()
                ))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _events.tryEmit(EngineEvent.ProgressChanged(newProgress))
            }
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                if (msg != null) {
                    _events.tryEmit(EngineEvent.ConsoleMessage(
                        level = msg.messageLevel().toString(),
                        message = msg.message()
                    ))
                }
                return true
            }
        }

        // 下载监听:WebView 自身不处理下载,把 url/mime/size 透出给 UI 层走系统 DownloadManager。
        // 此前 DownloadStart 事件已声明但 setDownloadListener 从未调用 —— 此处补齐接通。
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val filename = android.webkit.URLUtil.guessFileName(
                url, contentDisposition, mimetype
            )
            _events.tryEmit(
                EngineEvent.DownloadStart(
                    url = url,
                    suggestedFilename = filename,
                    mimeType = mimetype ?: "",
                    contentLength = contentLength,
                    userAgent = userAgent ?: "",
                )
            )
        }

        activeWebView = webView
        return webView
    }

    override fun navigate(url: String) {
        // 先记录导航意图（即使视图未就绪，currentUrl() 也反映最近一次 navigate）
        currentUrlValue = url
        val wv = activeWebView ?: return
        wv.loadUrl(url)
    }

    override fun currentUrl(): String = currentUrlValue

    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        val wv = activeWebView ?: run {
            callback?.invoke(null)
            return
        }
        wv.evaluateJavascript(script) { result ->
            callback?.invoke(result)
        }
    }

    override fun screenshot(): String? {
        val wv = activeWebView ?: return null
        val width = wv.width
        val height = wv.height
        if (width <= 0 || height <= 0) return null
        return fallbackCanvasScreenshot(wv)
    }

    private fun fallbackCanvasScreenshot(wv: WebView): String? {
        val width = wv.width
        val height = wv.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wv.draw(canvas)
        return bitmapToBase64(bitmap).also { bitmap.recycle() }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    override fun destroy() {
        val wv = activeWebView
        activeWebView = null
        idleRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        idleRunnable = null
        gmBridge.detach()
        if (wv != null) {
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    wv.stopLoading()
                    wv.webChromeClient = null
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (e: Exception) {
                    Log.w("SystemWebViewEngine", "Error destroying WebView: ${e.message}")
                }
            }
        }
    }

    override fun onPause() {
        // 暂停 WebView 的 JS 定时器 / 网络 / 视频 / 音频 / GPU 合成,
        // 防止 Activity 后台时仍占 CPU/电量(桌面模式常驻场景尤其重要)。
        activeWebView?.onPause()
    }

    override fun onResume() {
        // 恢复 WebView,与 onPause 配对。
        activeWebView?.onResume()
    }

    override fun isAvailable(): Boolean = true

    // 注入 stealth 反检测脚本后,"是否 WebView/headless"类检测基本被打穿,反爬 50→70。
    // 天花板仍是 TLS/JA3 指纹(JS 够不到),严防站走服务端匿名抓取兜底。
    override val antiBotScore: Int = 70

    override val supportsExtensions: Boolean = false

    override fun describe(): EngineInfo {
        val chromiumVersion = try {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        return EngineInfo(
            name = name,
            version = "WebView $chromiumVersion (Android ${Build.VERSION.RELEASE})",
            userAgent = DESKTOP_CHROME_UA,
            supportsExtensions = false,
            antiBotScore = antiBotScore,
            notes = "0 包大。已注入 stealth 反检测(webdriver/UA/WebGL/plugins/permissions),反爬中上;" +
                "TLS/JA3 指纹层走服务端兜底。扩展能力由自建注入式插件生态承载。"
        )
    }

    @SuppressLint("NewApi")
    private fun setAcceptThirdPartyCookies(webView: WebView, accept: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, accept)
        }
    }

    /**
     * 应用一个 [StealthProfile] 到 WebView：覆盖 UA / Accept-Language / 数据库 / DOM 存储 等。
     *
     * 与 [BrowserPluginHost.documentStartScript] 配合：JS 层 hook navigator.* 做运行时伪装，
     * 这里同步把 WebView 原生层的 UA / Accept-Language 也改成 profile 值（HTTP 请求头与
     * WebSettings.getUserAgentString() 走这里，JS 够不到）。
     */
    private fun applyStealthProfile(webView: WebView, profile: StealthProfile) {
        webView.settings.apply {
            userAgentString = profile.userAgent
            // Accept-Language 透传到 HTTP 请求头,与 navigator.languages 对齐。
            // WebSettings 没有直接 setter,走 WebSettings.setAcceptLanguage 是 API 14+ 的隐藏路径,
            // 这里用反射兼容;失败则仅靠 JS 层 hook navigator.languages 兜底。
            runCatching {
                val m = WebSettings::class.java.getMethod("setAcceptLanguage", String::class.java)
                m.invoke(this, profile.acceptLanguage)
            }.onFailure { Log.w("SystemWebViewEngine", "setAcceptLanguage failed: ${it.message}") }
            // 与真实浏览器行为对齐:启用 DOM 存储 / 数据库 / IndexedDB(已在 createView 开启,这里幂等确认)。
            domStorageEnabled = true
            databaseEnabled = true
            // 桌面 profile 用桌面视口策略;Android profile 用移动视口。
            useWideViewPort = profile.platform != "Linux armv8l" && profile.platform != "Linux aarch64"
            loadWithOverviewMode = true
        }
    }

    companion object {
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 清除浏览数据:Cookie + 网站存储(localStorage/IndexedDB)+ 缓存 + 表单。供浏览器设置调用。 */
        fun clearBrowsingData(context: Context) {
            runCatching {
                android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
                android.webkit.WebStorage.getInstance().deleteAllData()
                val wv = WebView(context)
                wv.clearCache(true)
                wv.clearFormData()
                wv.clearHistory()
                wv.destroy()
            }
        }
    }
}
