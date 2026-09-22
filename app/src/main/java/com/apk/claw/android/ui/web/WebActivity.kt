package com.apk.claw.android.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.safety.WebViewUrlPolicy
import com.apk.claw.android.widget.CommonToolbar

/**
 * Web 页面 - 通用浏览器 / HTML 预览
 *
 * 两种模式:
 * 1. URL 模式 —— [start] 传 URL,加载在线网页。
 * 2. HTML 预览模式 —— [startHtml] 传 HTML 字符串,用 loadDataWithBaseURL 渲染。
 *    用于对话页 Agent 产物(preview_html / generate_app)的全屏预览,类 Claude Artifacts。
 */
class WebActivity : BaseActivity() {

    private lateinit var toolbar: CommonToolbar
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val TAG = "WebActivity"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_HTML = "extra_html"
        private const val EXTRA_TITLE = "extra_title"

        /**
         * 打开 Web 页面(URL 模式)
         * @param context 上下文
         * @param url 网页地址
         * @param title 默认标题（可选，如果不传则显示网页标题）
         */
        fun start(context: Context, url: String, title: String? = null) {
            val intent = Intent(context, WebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }

        /**
         * 打开 HTML 预览(HTML 字符串模式)
         * @param context 上下文
         * @param html 完整 HTML/CSS/JS 字符串
         * @param title 标题
         */
        fun startHtml(context: Context, html: String, title: String? = null) {
            val intent = Intent(context, WebActivity::class.java).apply {
                putExtra(EXTRA_HTML, html)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        val url = intent.getStringExtra(EXTRA_URL)
        val html = intent.getStringExtra(EXTRA_HTML)
        if (url.isNullOrEmpty() && html.isNullOrEmpty()) {
            finish()
            return
        }

        val defaultTitle = intent.getStringExtra(EXTRA_TITLE)

        initToolbar(defaultTitle)
        initProgressBar()
        if (html != null) {
            initWebViewWithHtml(html)
        } else {
            initWebView(url!!)
        }
    }

    private fun initToolbar(defaultTitle: String?) {
        toolbar = findViewById(R.id.toolbar)
        toolbar.apply {
            // 设置默认标题（如果有）
            if (!defaultTitle.isNullOrEmpty()) {
                setTitle(defaultTitle)
            }
            // 显示返回按钮
            showBackButton(true) { finish() }
        }
    }

    private fun initProgressBar() {
        progressBar = findViewById(R.id.progressBar)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String) {
        val verdict = WebViewUrlPolicy.navigation(url, allowBlank = false)
        if (!verdict.allow) {
            Log.w(TAG, "blocked WebActivity navigation: ${verdict.reason}")
            finish()
            return
        }
        webView = findViewById(R.id.webView)
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val verdict = WebViewUrlPolicy.navigation(url, allowBlank = false)
                    if (!verdict.allow) {
                        Log.w(TAG, "blocked redirect: ${verdict.reason}")
                        return true
                    }
                    url?.let { view?.loadUrl(it) }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    // 网页加载完成后，回显网页标题
                    if (!title.isNullOrEmpty()) {
                        toolbar.setTitle(title)
                    }
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progressBar.progress = newProgress
                    // 加载完成时隐藏进度条
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }

            loadUrl(url)
        }
    }

    /**
     * HTML 预览模式 —— 用 loadDataWithBaseURL 渲染 HTML 字符串。
     * baseUrl 用 about:blank,允许相对资源解析 + 防 file:// 跨域;JS 已在 [initWebView] 同款设置开启。
     * 用于对话页 Agent 产物(preview_html / generate_app)的全屏预览。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewWithHtml(html: String) {
        webView = findViewById(R.id.webView)
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                // 允许预览里的远程图片素材(<img>/CSS 背景)加载,兼容模式兜底 http 图。
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    // 预览内的链接跳转交给系统浏览器,避免在预览页里乱跳
                    val verdict = WebViewUrlPolicy.navigation(url, allowBlank = false)
                    if (!verdict.allow) {
                        Log.w(TAG, "blocked preview link: ${verdict.reason}")
                        return true
                    }
                    url?.let {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(intent)
                        } catch (_: Exception) { /* 无浏览器时静默 */ }
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    progressBar.progress = newProgress
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }
            // baseUrl 用一个合法但**永不解析**的 https 源(.invalid 保留域):
            //  - 给文档一个真实 https origin,远程绝对 URL 的图片素材才能加载
            //    (about:blank / null 源会阻断网络子资源 —— 正是预览图全坏的根因);
            //  - .invalid 域 DNS 必失败,相对/同源请求自然打不出去,不暴露 file://、无真实同源可打。
            loadDataWithBaseURL("https://preview.invalid/", html, "text/html", "utf-8", null)
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
