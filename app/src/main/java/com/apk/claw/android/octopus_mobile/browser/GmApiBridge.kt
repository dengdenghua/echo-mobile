package com.apk.claw.android.octopus_mobile.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.safety.WebViewUrlPolicy
import com.apk.claw.android.utils.OctoHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class GmApiBridge(private val context: Context) {

    companion object {
        private const val TAG = "GmApiBridge"
        private const val CHANNEL_ID = "octopus_userscript"
        private const val PREFS_NAME = "octopus_userscript_store"
        private const val INTERFACE_NAME = "octopus_gm"
        private const val RESOURCES_DIR = "userscript_resources"

        @Volatile
        private var channelCreated = false

        val JS_BRIDGE_NAME = INTERFACE_NAME
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "gm-worker-${System.nanoTime()}").apply { isDaemon = true }
    }
    private val menuCommands = ConcurrentHashMap<String, MenuCommand>()
    private var webView: java.lang.ref.WeakReference<android.webkit.WebView>? = null
    @Volatile
    private var currentUrl: String = ""

    /** 桥脚本实际注入过的文档 URL；null 表示当前文档未授权（fail-closed）。 */
    @Volatile
    private var injectedUrl: String? = null

    /** @connect 白名单(host)。为空时仅禁止访问内网/本机地址。 */
    private var connectWhitelist: Set<String> = emptySet()

    data class MenuCommand(
        val id: String,
        val name: String,
        val accessKey: String?,
        val callbackId: String,
    )

    fun attach(webView: android.webkit.WebView, url: String) {
        this.webView = java.lang.ref.WeakReference(webView)
        this.currentUrl = url
        this.injectedUrl = null
        try {
            webView.addJavascriptInterface(this, INTERFACE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "addJavascriptInterface failed: ${e.message}")
        }
    }

    fun detach() {
        webView = null
        menuCommands.clear()
        injectedUrl = null
        workerPool.shutdown()
    }

    fun updateUrl(url: String) {
        currentUrl = url
        val authorized = injectedUrl ?: return
        if (WebViewUrlPolicy.originOf(url) != WebViewUrlPolicy.originOf(authorized)) {
            injectedUrl = null
        }
    }

    /**
     * 引擎确认「已在本 document 注入用户脚本」后调用。
     *
     * 这是 jsBridge 的唯一授权入口：没有调用过本方法的文档，
     * 所有 @JavascriptInterface 方法一律拒绝，避免任意站点直接访问裸接口。
     */
    fun authorizeDocument(url: String) {
        injectedUrl = url
    }

    /** 新 document 开始时撤销上一页授权（fail-closed）。 */
    fun clearDocumentAuthorization() {
        injectedUrl = null
    }

    private fun bridgeAccessAllowed(): Boolean =
        WebViewUrlPolicy.bridgeCallAllowed(currentUrl, injectedUrl)

    private fun denyBridgeCall(method: String) {
        val origin = WebViewUrlPolicy.originOf(currentUrl) ?: currentUrl
        Log.w(TAG, "denied $method from $origin")
    }

    /** 设置脚本 manifest 中声明的 @connect host 白名单。 */
    fun setConnectWhitelist(hosts: Set<String>) {
        connectWhitelist = hosts
    }

    fun buildBridgeScript(grantedApis: Set<String>): String {
        val sb = StringBuilder()
        sb.append("(function(){\n")
        sb.append("  if (window.top !== window) return;\n")
        sb.append("  if (window.__octopus_gm_ready__) return;\n")
        sb.append("  window.__octopus_gm_ready__ = true;\n")
        sb.append("  var bridge = window.$INTERFACE_NAME;\n")
        sb.append("  if (!bridge) return;\n\n")

        if ("GM_addStyle" in grantedApis || "GM_addStyle" !in grantedApis && grantedApis.isEmpty()) {
            sb.append("""
  function GM_addStyle(css) {
    var s = document.createElement('style');
    s.type = 'text/css';
    s.textContent = css;
    (document.head || document.documentElement || document.body).appendChild(s);
    return s;
  }
  window.GM_addStyle = GM_addStyle;
""")
        }

        if ("GM_setValue" in grantedApis) {
            sb.append("""
  function GM_setValue(key, value) {
    try { bridge.setValue(String(key), JSON.stringify({v:value})); } catch(e){}
  }
  window.GM_setValue = GM_setValue;
""")
        }

        if ("GM_getValue" in grantedApis) {
            sb.append("""
  function GM_getValue(key, def) {
    try {
      var raw = bridge.getValue(String(key));
      if (raw == null) return def;
      var obj = JSON.parse(raw);
      return (obj && 'v' in obj) ? obj.v : def;
    } catch(e){ return def; }
  }
  window.GM_getValue = GM_getValue;
""")
        }

        if ("GM_deleteValue" in grantedApis) {
            sb.append("""
  function GM_deleteValue(key) {
    try { bridge.deleteValue(String(key)); } catch(e){}
  }
  window.GM_deleteValue = GM_deleteValue;
""")
        }

        if ("GM_listValues" in grantedApis) {
            sb.append("""
  function GM_listValues() {
    try { return JSON.parse(bridge.listValues() || '[]'); } catch(e){ return []; }
  }
  window.GM_listValues = GM_listValues;
""")
        }

        if ("GM_setClipboard" in grantedApis) {
            sb.append("""
  function GM_setClipboard(text, type) {
    try { bridge.setClipboard(String(text), type || 'text/plain'); } catch(e){}
  }
  window.GM_setClipboard = GM_setClipboard;
""")
        }

        if ("GM_xmlhttpRequest" in grantedApis || "GM_xmlhttpRequest" !in grantedApis && grantedApis.isEmpty()) {
            sb.append("""
  function GM_xmlhttpRequest(details) {
    if (!details) return {abort:function(){}};
    var cbId = 'gm_xhr_' + Math.random().toString(36).substr(2,9);
    var cfg = {
      url: details.url || '',
      method: (details.method || 'GET').toUpperCase(),
      headers: details.headers || {},
      data: typeof details.data === 'string' ? details.data : null,
      timeout: details.timeout || 0,
      withCredentials: !!details.withCredentials,
      responseType: details.responseType || ''
    };
    var aborted = false;
    window[cbId] = function(event, arg) {
      if (aborted) return;
      if (typeof arg === 'string') { try { arg = JSON.parse(arg); } catch(e){} }
      try {
        if (event === 'load' && details.onload) details.onload(arg);
        else if (event === 'error' && details.onerror) details.onerror(arg);
        else if (event === 'timeout' && details.ontimeout) details.ontimeout(arg);
        else if (event === 'progress' && details.onprogress) details.onprogress(arg);
        else if (event === 'abort' && details.onabort) details.onabort(arg);
        else if (event === 'readystatechange' && details.onreadystatechange) details.onreadystatechange(arg);
      } catch(e){}
      if (event === 'load' || event === 'error' || event === 'timeout' || event === 'abort') {
        delete window[cbId];
      }
    };
    bridge.xmlhttpRequest(cbId, JSON.stringify(cfg));
    return {
      abort: function() {
        aborted = true;
        bridge.abortXhr(cbId);
      }
    };
  }
  window.GM_xmlhttpRequest = GM_xmlhttpRequest;
""")
        }

        if ("GM_notification" in grantedApis) {
            sb.append("""
  function GM_notification(detail, title, image, onclick) {
    var opts = {};
    if (typeof detail === 'string') {
      opts.text = detail;
      opts.title = title || '';
      opts.image = image || '';
    } else if (detail) {
      opts.text = detail.text || detail.body || '';
      opts.title = detail.title || '';
      opts.image = detail.image || detail.icon || '';
      opts.silent = !!detail.silent;
    }
    var cbId = 'gm_notify_' + Math.random().toString(36).substr(2,9);
    if (typeof detail === 'object' && detail.onclick) {
      window[cbId] = function() { try { detail.onclick(); } catch(e){} delete window[cbId]; };
    }
    bridge.notification(cbId, JSON.stringify(opts));
  }
  window.GM_notification = GM_notification;
""")
        }

        if ("GM_openInTab" in grantedApis) {
            sb.append("""
  function GM_openInTab(url, opts) {
    var active = true;
    if (typeof opts === 'object' && opts.active === false) active = false;
    if (typeof opts === 'boolean') active = opts;
    bridge.openInTab(String(url), active);
    return { close: function(){} };
  }
  window.GM_openInTab = GM_openInTab;
""")
        }

        if ("GM_registerMenuCommand" in grantedApis) {
            sb.append("""
  var __gm_menuSeq = 0;
  function GM_registerMenuCommand(name, fn, accessKey) {
    var id = 'gm_menu_' + (++__gm_menuSeq);
    window[id] = function() { try { fn(); } catch(e){} };
    bridge.registerMenuCommand(id, String(name), accessKey || null);
    return id;
  }
  window.GM_registerMenuCommand = GM_registerMenuCommand;
""")
        }

        if ("GM_unregisterMenuCommand" in grantedApis) {
            sb.append("""
  function GM_unregisterMenuCommand(id) {
    try { bridge.unregisterMenuCommand(String(id)); delete window[id]; } catch(e){}
  }
  window.GM_unregisterMenuCommand = GM_unregisterMenuCommand;
""")
        }

        if ("GM_getResourceText" in grantedApis || "GM_getResourceURL" in grantedApis) {
            sb.append("""
  function GM_getResourceText(name) {
    try { return bridge.getResourceText(String(name)) || ''; } catch(e){ return ''; }
  }
  window.GM_getResourceText = GM_getResourceText;
  function GM_getResourceURL(name) {
    try { return bridge.getResourceUrl(String(name)) || ''; } catch(e){ return ''; }
  }
  window.GM_getResourceURL = GM_getResourceURL;
""")
        }

        if ("GM_info" in grantedApis || "GM_info" !in grantedApis && grantedApis.isEmpty()) {
            sb.append("""
  window.GM_info = {
    scriptHandler: 'Octopus',
    version: '1.0',
    script: { version: '1.0', name: document.title || '' },
    platform: { name: 'Android' }
  };
""")
        }

        sb.append("})();\n")
        return sb.toString()
    }

    private fun ensureChannel() {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "UserScript通知", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "浏览器脚本通知"
            mgr.createNotificationChannel(ch)
        }
        channelCreated = true
    }

    @JavascriptInterface
    fun setValue(key: String, valueJson: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("setValue")
            return
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("val_$currentUrl:$key", valueJson).apply()
        } catch (e: Exception) { Log.w(TAG, "setValue: ${e.message}") }
    }

    @JavascriptInterface
    fun getValue(key: String): String? {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("getValue")
            return null
        }
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("val_$currentUrl:$key", null)
        } catch (e: Exception) { null }
    }

    @JavascriptInterface
    fun deleteValue(key: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("deleteValue")
            return
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("val_$currentUrl:$key").apply()
        } catch (e: Exception) { Log.w(TAG, "deleteValue: ${e.message}") }
    }

    @JavascriptInterface
    fun listValues(): String {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("listValues")
            return "[]"
        }
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefix = "val_$currentUrl:"
            val keys = prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            JSONArray(keys).toString()
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun setClipboard(text: String, mimeType: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("setClipboard")
            return
        }
        mainHandler.post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("userscript", text))
            } catch (e: Exception) { Log.w(TAG, "setClipboard: ${e.message}") }
        }
    }

    private fun isUrlAllowed(rawUrl: String): Boolean {
        val host = try {
            java.net.URI(rawUrl).host?.lowercase()
        } catch (e: Exception) {
            return false
        }
        if (host.isNullOrBlank()) return false
        if (connectWhitelist.isNotEmpty()) {
            return connectWhitelist.any { hostMatches(host, it.lowercase()) }
        }
        return !isPrivateOrLocalhost(host)
    }

    private fun hostMatches(host: String, pattern: String): Boolean {
        if (pattern == "*") return true
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(2)
            return host == suffix || host.endsWith(".$suffix")
        }
        return host == pattern
    }

    private fun isPrivateOrLocalhost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "[::1]") return true
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            return when {
                a == 0 -> true                  // 0.0.0.0/8
                a == 10 -> true                 // 10.0.0.0/8
                a == 127 -> true                // 127.0.0.0/8
                a == 192 && b == 168 -> true     // 192.168.0.0/16
                a == 172 && b in 16..31 -> true  // 172.16.0.0/12
                a == 169 && b == 254 -> true     // 169.254.0.0/16 link-local
                else -> false
            }
        }
        return false
    }

    @JavascriptInterface
    fun xmlhttpRequest(callbackId: String, cfgJson: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("xmlhttpRequest")
            return
        }
        workerPool.execute {
            try {
                val cfg = JSONObject(cfgJson)
                val url = cfg.optString("url")
                if (!isUrlAllowed(url)) {
                    val err = JSONObject()
                        .put("error", "URL not allowed by @connect policy")
                        .put("readyState", 4)
                    postCallback(callbackId, "error", err.toString())
                    return@execute
                }
                val method = cfg.optString("method", "GET")
                val headers = cfg.optJSONObject("headers")
                val data = if (cfg.has("data") && !cfg.isNull("data")) cfg.optString("data") else null
                val timeoutMs = cfg.optInt("timeout", 30000).coerceIn(1000, 60000)
                val responseType = cfg.optString("responseType", "")

                val reqBuilder = Request.Builder().url(url)
                if (headers != null) {
                    val iter = headers.keys()
                    while (iter.hasNext()) {
                        val k = iter.next()
                        reqBuilder.addHeader(k, headers.optString(k))
                    }
                }
                reqBuilder.addHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

                if (method != "GET" || !data.isNullOrBlank()) {
                    val body = (data ?: "").toRequestBody("text/plain;charset=utf-8".toMediaType())
                    reqBuilder.method(method, body)
                }

                val http = OctoHttp.shared.newBuilder()
                    .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .build()

                http.newCall(reqBuilder.build()).execute().use { resp ->
                    val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                    val bodyText = if (responseType == "arraybuffer") {
                        Base64.encodeToString(bodyBytes, Base64.NO_WRAP)
                    } else {
                        String(bodyBytes, Charsets.UTF_8)
                    }
                    val respHeaders = JSONObject()
                    for ((k, v) in resp.headers) {
                        respHeaders.put(k, v)
                    }
                    val result = JSONObject()
                        .put("status", resp.code)
                        .put("statusText", resp.message)
                        .put("readyState", 4)
                        .put("responseHeaders", respHeaders.toString())
                        .put("responseText", bodyText)
                        .put("response", bodyText)
                        .put("finalUrl", resp.request.url.toString())
                    postCallback(callbackId, "load", result.toString())
                }
            } catch (e: Exception) {
                val err = JSONObject().put("error", e.message ?: "unknown").put("readyState", 4)
                postCallback(callbackId, "error", err.toString())
            }
        }
    }

    @JavascriptInterface
    fun abortXhr(callbackId: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("abortXhr")
            return
        }
        // Best-effort: OkHttp cancels via tag/Call; simplified here.
    }

    @JavascriptInterface
    fun notification(callbackId: String, optsJson: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("notification")
            return
        }
        mainHandler.post {
            try {
                val opts = JSONObject(optsJson)
                val text = opts.optString("text")
                val title = opts.optString("title").ifBlank { "脚本通知" }
                ensureChannel()
                val pi = PendingIntent.getActivity(
                    context, 0, Intent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(callbackId.hashCode() and 0x7fffffff, notif)
            } catch (e: Exception) { Log.w(TAG, "notification: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun openInTab(url: String, active: Boolean) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("openInTab")
            return
        }
        if (!WebViewUrlPolicy.navigation(url, allowBlank = false).allow) {
            denyBridgeCall("openInTab:bad_url")
            return
        }
        mainHandler.post {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) { Log.w(TAG, "openInTab: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun registerMenuCommand(id: String, name: String, accessKey: String?) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("registerMenuCommand")
            return
        }
        menuCommands[id] = MenuCommand(id, name, accessKey, id)
    }

    @JavascriptInterface
    fun unregisterMenuCommand(id: String) {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("unregisterMenuCommand")
            return
        }
        menuCommands.remove(id)
    }

    @JavascriptInterface
    fun getResourceText(name: String): String {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("getResourceText")
            return ""
        }
        return try {
            val file = getResourceFile(name)
            if (file?.isFile == true) file.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface
    fun getResourceUrl(name: String): String {
        if (!bridgeAccessAllowed()) {
            denyBridgeCall("getResourceUrl")
            return ""
        }
        return try {
            val file = getResourceFile(name)
            if (file?.isFile == true) {
                val bytes = file.readBytes()
                val mime = guessMime(file.name)
                "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else ""
        } catch (e: Exception) { "" }
    }

    private fun getResourceFile(name: String): File? {
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(context.filesDir, RESOURCES_DIR)
        val f = File(dir, safe)
        return if (f.isFile) f else null
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".svg", true) -> "image/svg+xml"
        name.endsWith(".css", true) -> "text/css"
        name.endsWith(".js", true) -> "application/javascript"
        name.endsWith(".json", true) -> "application/json"
        name.endsWith(".html", true) -> "text/html"
        else -> "application/octet-stream"
    }

    fun cacheResource(name: String, data: ByteArray) {
        try {
            val dir = File(context.filesDir, RESOURCES_DIR)
            dir.mkdirs()
            val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            File(dir, safe).writeBytes(data)
        } catch (e: Exception) { Log.w(TAG, "cacheResource: ${e.message}") }
    }

    fun resourceExists(name: String): Boolean = getResourceFile(name)?.isFile == true

    private fun postCallback(cbId: String, event: String, jsonArg: String) {
        mainHandler.post {
            val wv = webView?.get()
            if (wv != null) {
                val safeArg = JSONObject.quote(jsonArg)
                val safeCbId = JSONObject.quote(cbId)
                val js = "if(window[$safeCbId]){window[$safeCbId]('$event', $safeArg);}"
                wv.evaluateJavascript(js, null)
            }
        }
    }

}
