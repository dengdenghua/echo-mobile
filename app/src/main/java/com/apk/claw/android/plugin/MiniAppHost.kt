package com.apk.claw.android.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import com.apk.claw.android.octopus_mobile.safety.WebViewUrlPolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference

object MiniAppHost {

    private const val TAG = "MiniAppHost"
    private val REMOTE_IMAGE_SCHEMES = setOf("http", "https", "data")
    private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".bmp", ".ico", ".avif")
    private val FONT_EXTS = listOf(".woff2", ".woff", ".ttf", ".otf", ".eot")
    private val STYLE_EXTS = listOf(".css")
    private val SCRIPT_EXTS = listOf(".js", ".mjs")

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private const val REQUEST_FILE_CHOOSER = 0x5f01

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun createWebView(activity: Activity, manifest: PluginManifest): WebView? {
        val pageUrl = resolvePageUrl(activity, manifest) ?: return null
        val localPrefixes = allowedLocalPrefixes(activity, manifest)
        val wv = WebView(activity)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            allowContentAccess = true
            setGeolocationEnabled(false)
        }

        if ((activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.isVerticalScrollBarEnabled = true
        wv.isHorizontalScrollBarEnabled = false
        wv.isScrollbarFadingEnabled = true
        wv.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isHorizontalScrollBarEnabled = false

        val bridge = OctopusBridge(manifest, WeakReference(activity))
        bridge.attachWebView(wv)
        wv.tag = bridge
        wv.addJavascriptInterface(bridge, "octopusNative")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(OctopusBridge.SHIM_JS, null)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                bridge.onPageFinished()
                injectSafeAreaCss(view)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url?.toString() ?: return true
                if (u.startsWith("file:")) {
                    val verdict = WebViewUrlPolicy.fileNavigation(u, localPrefixes)
                    if (!verdict.allow) {
                        Log.w(TAG, "blocked local navigation: ${verdict.reason} $u")
                    }
                    return !verdict.allow
                }
                if (u.startsWith("octopus://")) {
                    bridge.handleScheme(u)
                    return true
                }
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    val host = request.url?.host ?: return true
                    if (PermissionGate.allowHost(manifest, u)) return false
                    bridge.openExternalUrlDirect(u)
                    return true
                }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url?.toString().orEmpty()
                return when {
                    u.startsWith("file:") ->
                        if (WebViewUrlPolicy.fileNavigation(u, localPrefixes).allow) null
                        else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    isAllowedRemoteResource(request, manifest) -> null
                    else -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                    else -> Log.DEBUG
                }
                Log.println(level, "MiniApp[${manifest.id}]",
                    "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val pb = progressBarRef?.get()
                if (pb != null) {
                    pb.progress = newProgress
                    pb.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    val acceptTypes = fileChooserParams?.acceptTypes
                    if (!acceptTypes.isNullOrEmpty()) {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                    }
                }
                val chooserIntent = Intent.createChooser(intent, manifest.name)
                try {
                    activity.startActivityForResult(chooserIntent, REQUEST_FILE_CHOOSER)
                } catch (e: Exception) {
                    fileChooserCallback = null
                    return false
                }
                return true
            }
        }

        wv.loadUrl(pageUrl)
        return wv
    }

    private var progressBarRef: WeakReference<ProgressBar>? = null

    fun bindProgress(wv: WebView?, progressBar: ProgressBar) {
        progressBarRef = WeakReference(progressBar)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_FILE_CHOOSER) return false
        val cb = fileChooserCallback ?: return false
        fileChooserCallback = null
        val results: Array<Uri>? = when {
            resultCode != Activity.RESULT_OK -> null
            data?.data != null -> arrayOf(data.data!!)
            data?.clipData != null -> {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            }
            else -> null
        }
        cb.onReceiveValue(results)
        return true
    }

    fun destroyWebView(webView: WebView?) {
        webView?.let { wv ->
            runCatching { (wv.tag as? OctopusBridge)?.detach() }
            runCatching { wv.stopLoading() }
            runCatching { wv.removeJavascriptInterface("octopusNative") }
            runCatching { wv.webChromeClient = null }
            runCatching { wv.webViewClient = WebViewClient() }
            runCatching { wv.destroy() }
        }
        progressBarRef?.clear()
        progressBarRef = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
    }

    private fun isAllowedRemoteResource(request: WebResourceRequest, manifest: PluginManifest): Boolean {
        val scheme = request.url?.scheme?.lowercase()
        if (scheme !in REMOTE_IMAGE_SCHEMES) return false
        val url = request.url?.toString()?.lowercase().orEmpty()
        val accept = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value?.lowercase().orEmpty()
        val path = request.url?.path?.lowercase().orEmpty()

        if (url.startsWith("data:")) {
            if (url.startsWith("data:image/")) return true
            return false
        }

        val isImage = accept.contains("image/") || IMAGE_EXTS.any { path.endsWith(it) }
        val isFont = accept.contains("font/") || FONT_EXTS.any { path.endsWith(it) }
        val isStyle = accept.contains("text/css") || STYLE_EXTS.any { path.endsWith(it) }
        val isScript = accept.contains("javascript") || accept.contains("text/javascript") || SCRIPT_EXTS.any { path.endsWith(it) }

        if (!isImage && !isFont && !isStyle && !isScript) return false

        val u = request.url?.toString().orEmpty()
        if (PermissionGate.allowHost(manifest, u)) return true

        return false
    }

    private fun injectSafeAreaCss(view: WebView) {
        val js = """
        (function(){
          if (document.getElementById('__octopus_safe_area')) return;
          var style = document.createElement('style');
          style.id = '__octopus_safe_area';
          style.textContent = ':root{--sat:env(safe-area-inset-top,0px);--sar:env(safe-area-inset-right,0px);--sab:env(safe-area-inset-bottom,0px);--sal:env(safe-area-inset-left,0px);}';
          document.head.appendChild(style);
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun allowedLocalPrefixes(context: Context, manifest: PluginManifest): List<String> {
        val candidates = linkedSetOf(manifest.id, manifest.id.substringAfterLast('/'))
        val prefixes = linkedSetOf<String>()
        for (baseDir in listOf("plugins", "generated_apps")) {
            for (slug in candidates) {
                val dir = File(context.filesDir, "$baseDir/$slug")
                if (!dir.isDirectory) continue
                prefixes += Uri.fromFile(dir).toString().trimEnd('/') + "/"
                prefixes += dir.toURI().toString().trimEnd('/') + "/"
            }
        }
        val safeId = manifest.id.trim('/').replace("..", "_")
        prefixes += "file:///android_asset/plugins/$safeId/"
        return prefixes.toList()
    }

    fun resolvePageUrl(context: Context, manifest: PluginManifest): String? {
        val candidates = linkedSetOf(manifest.id, manifest.id.substringAfterLast('/'))
        for (baseDir in listOf("plugins", "generated_apps")) {
            for (slug in candidates) {
                try {
                    val pluginDir = File(context.filesDir, "$baseDir/$slug")
                    if (!pluginDir.isDirectory) continue
                    val page = File(pluginDir, manifest.page)
                    if (!page.isFile) continue
                    val pageCanon = page.canonicalPath
                    val dirCanon = pluginDir.canonicalPath
                    if (!pageCanon.startsWith(dirCanon + File.separator) && pageCanon != dirCanon) continue
                    return page.toURI().toString()
                } catch (e: Exception) {
                    Log.w(TAG, "resolvePageUrl $baseDir error for $slug: ${e.message}")
                }
            }
        }
        val assetRel = "plugins/${manifest.id}/${manifest.page}"
        val existsInAssets = runCatching { context.assets.open(assetRel).use { true } }.getOrDefault(false)
        if (existsInAssets) return "file:///android_asset/$assetRel"
        return null
    }
}
