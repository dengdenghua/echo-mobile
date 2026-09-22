package com.apk.claw.android.plugin

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.apk.claw.android.account.AccountRepository
import com.apk.claw.android.octopus_mobile.safety.SsrfSafeHttp
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OctopusBridge(
    private val manifest: PluginManifest,
    private val activityRef: WeakReference<Activity>? = null,
) {
    private val TAG = "OctopusBridge"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    private var webViewRef: WeakReference<WebView>? = null

    internal fun attachWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    /** WebView 销毁时调用:取消所有后台协程,避免回调到已销毁的 WebView。 */
    fun detach() {
        ioScope.cancel()
        webViewRef?.clear()
        webViewRef = null
    }

    @JavascriptInterface
    fun pluginId(): String = manifest.id

    @JavascriptInterface
    fun pluginName(): String = manifest.name

    @JavascriptInterface
    fun pluginVersion(): String = manifest.version

    @JavascriptInterface
    fun reportAction(actionType: String, paramsJson: String?) {
        MiniAppActionBus.onReportedAction(manifest.id, actionType, paramsJson)
    }

    @JavascriptInterface
    fun callTool(name: String, argsJson: String?): String {
        if (!PermissionGate.allowTool(manifest, name)) return err("tool not permitted: $name")
        val args = parseArgs(argsJson)
        val r = ToolRegistry.withUntrustedSource { ToolRegistry.executeTool(name, args) }
        return if (r.isSuccess) ok(r.data ?: "") else err(r.error ?: "tool failed")
    }

    @JavascriptInterface
    fun callToolAsync(name: String, argsJson: String?, callbackId: String) {
        if (!PermissionGate.allowTool(manifest, name)) {
            postCallback(callbackId, err("tool not permitted: $name")); return
        }
        val args = parseArgs(argsJson)
        ioScope.launch {
            val r = ToolRegistry.withUntrustedSource { ToolRegistry.executeTool(name, args) }
            val result = if (r.isSuccess) ok(r.data ?: "") else err(r.error ?: "tool failed")
            postCallback(callbackId, result)
        }
    }

    @JavascriptInterface
    fun httpFetch(url: String, optionsJson: String?, callbackId: String) {
        val u = url.trim()
        if (u.isBlank()) { postCallback(callbackId, err("url required")); return }
        if (!PermissionGate.allowHost(manifest, u)) {
            postCallback(callbackId, err("host not permitted: ${Uri.parse(u).host}")); return
        }
        val opts = runCatching { JSONObject(optionsJson ?: "{}") }.getOrDefault(JSONObject())
        val method = opts.optString("method", "GET").uppercase()
        val headers = opts.optJSONObject("headers")
        val body = if (opts.has("body")) opts.optString("body") else null

        ioScope.launch {
            try {
                val reqBuilder = Request.Builder().url(u)
                if (headers != null) {
                    val hb = Headers.Builder()
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        hb.add(k, headers.getString(k))
                    }
                    reqBuilder.headers(hb.build())
                }
                if (body != null && method != "GET" && method != "HEAD") {
                    val ct = headers?.optString("Content-Type", "application/json") ?: "application/json"
                    reqBuilder.method(method, body.toRequestBody(ct.toMediaTypeOrNull()))
                } else {
                    reqBuilder.method(method, null)
                }
                SsrfSafeHttp.execute(
                    client = httpClient,
                    initial = reqBuilder.build(),
                    hopAllowed = { hopUrl -> PermissionGate.allowHost(manifest, hopUrl) },
                ).use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    val result = JSONObject()
                        .put("ok", true)
                        .put("data", JSONObject()
                            .put("status", resp.code)
                            .put("statusText", resp.message)
                            .put("headers", JSONObject().apply {
                                for ((k, v) in resp.headers.toMultimap()) {
                                    put(k, v.joinToString(","))
                                }
                            })
                            .put("body", respBody)
                        ).toString()
                    postCallback(callbackId, result)
                }
            } catch (e: Exception) {
                postCallback(callbackId, err("fetch failed: ${e.message}"))
            }
        }
    }

    @JavascriptInterface
    fun pay(orderJson: String?, callbackId: String) {
        if (!PermissionGate.allowPay(manifest)) {
            postCallback(callbackId, err("pay not granted")); return
        }
        val o = runCatching { JSONObject(orderJson ?: "{}") }.getOrNull()
            ?: run { postCallback(callbackId, err("invalid order JSON")); return }
        val item = o.optString("item").trim().ifBlank {
            postCallback(callbackId, err("item required")); return
        }
        val credits = o.optInt("credits", 0).takeIf { it in 1..1000 }
            ?: run { postCallback(callbackId, err("credits must be 1–1000")); return }
        val description = o.optString("description", item).take(200)
        val activity = activityRef?.get() ?: run { postCallback(callbackId, err("activity unavailable")); return }

        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                postCallback(callbackId, err("activity unavailable")); return@post
            }
            AlertDialog.Builder(activity)
                .setTitle("${manifest.name} 请求支付")
                .setMessage("「$description」\n扣除 $credits 积分")
                .setPositiveButton("确认") { _, _ ->
                    ioScope.launch {
                        val result = AccountRepository.pluginPay(manifest.id, item, credits, description)
                        val r = result.fold(
                            onSuccess = { res ->
                                if (res.success) ok(JSONObject().put("balance_after", res.data?.balanceAfter ?: 0).toString())
                                else err("支付失败:积分不足或服务错误")
                            },
                            onFailure = { err("支付失败:${it.message}") }
                        )
                        postCallback(callbackId, r)
                    }
                }
                .setNegativeButton("取消") { _, _ -> postCallback(callbackId, err("用户取消支付")) }
                .setOnCancelListener { postCallback(callbackId, err("用户取消支付")) }
                .show()
        }
    }

    /** 同步支付(遗留兼容):分段 await 以响应中断,网络调用受 [PAY_NETWORK_TIMEOUT_MS] 限制。优先使用异步 [pay]。 */
    @JavascriptInterface
    fun paySync(orderJson: String?): String {
        if (!PermissionGate.allowPay(manifest)) return err("pay not granted")
        val o = runCatching { JSONObject(orderJson ?: "{}") }.getOrNull()
            ?: return err("invalid order JSON")
        val item = o.optString("item").trim().ifBlank { return err("item required") }
        val credits = o.optInt("credits", 0).takeIf { it in 1..1000 }
            ?: return err("credits must be 1–1000")
        val description = o.optString("description", item).take(200)
        val activity = activityRef?.get() ?: return err("activity unavailable")

        val confirmed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) { latch.countDown(); return@post }
            AlertDialog.Builder(activity)
                .setTitle("${manifest.name} 请求支付")
                .setMessage("「$description」\n扣除 $credits 积分")
                .setPositiveButton("确认") { _, _ -> confirmed.set(true); latch.countDown() }
                .setNegativeButton("取消") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        val deadline = System.currentTimeMillis() + PAY_CONFIRM_TIMEOUT_MS
        var responded = false
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) return err("支付确认被中断")
            if (latch.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) { responded = true; break }
        }
        if (!responded) return err("支付确认超时")
        if (!confirmed.get()) return err("用户取消支付")

        return runBlocking {
            val result = try {
                withTimeout(PAY_NETWORK_TIMEOUT_MS) {
                    AccountRepository.pluginPay(manifest.id, item, credits, description)
                }
            } catch (e: Exception) {
                return@runBlocking err("支付失败:${e.message}")
            }
            result.fold(
                onSuccess = { r ->
                    if (r.success) ok(JSONObject().put("balance_after", r.data?.balanceAfter ?: 0).toString())
                    else err("支付失败:积分不足或服务错误")
                },
                onFailure = { err("支付失败:${it.message}") }
            )
        }
    }

    @JavascriptInterface
    fun deviceAutomate(cap: String, argsJson: String?): String {
        if (!PermissionGate.allowDevice(manifest, cap)) return err("device cap not granted: $cap")
        val args = parseArgs(argsJson)
        val r = ToolRegistry.withUntrustedSource { ToolRegistry.executeTool(cap, args) }
        return if (r.isSuccess) ok(r.data ?: "") else err(r.error ?: "device tool failed")
    }

    @JavascriptInterface
    fun showToast(message: String) {
        val activity = activityRef?.get() ?: return
        val msg = message.take(200)
        mainHandler.post { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(patternJson: String?) {
        val activity = activityRef?.get() ?: return
        val pattern = runCatching {
            val arr = JSONArray(patternJson ?: "[100]")
            LongArray(arr.length()) { arr.getLong(it) }
        }.getOrNull() ?: longArrayOf(100L)
        mainHandler.post {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // createPredefined 是 API 29+;API 26-28 退回 createOneShot,
                // 否则在 Android 8/9 上会抛 NoSuchMethodError。
                if (pattern.size == 1 && pattern[0] <= 50 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else if (pattern.size == 1) {
                    vibrator.vibrate(VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val activity = activityRef?.get() ?: return
        mainHandler.post {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("mini-app", text))
        }
    }

    @JavascriptInterface
    fun readClipboard(): String {
        val activity = activityRef?.get() ?: return ""
        return runCatching {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cm.hasPrimaryClip()) cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "" else ""
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun share(title: String?, text: String?, url: String?) {
        val activity = activityRef?.get() ?: return
        val shareText = buildString {
            if (!text.isNullOrBlank()) append(text)
            if (!url.isNullOrBlank()) { if (isNotEmpty()) append("\n"); append(url) }
        }
        mainHandler.post {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(intent, manifest.name))
        }
    }

    internal fun openExternalUrlDirect(url: String) {
        openExternalUrl(url)
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        val activity = activityRef?.get() ?: return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        mainHandler.post {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    @JavascriptInterface
    fun openMiniApp(appId: String) {
        val activity = activityRef?.get() ?: return
        mainHandler.post {
            val target = MiniAppRegistry.get(appId)
            if (target != null) {
                activity.startActivity(Intent(activity, MiniAppActivity::class.java).apply {
                    putExtra(MiniAppActivity.EXTRA_PLUGIN_ID, appId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    @JavascriptInterface
    fun closeMiniApp() {
        val activity = activityRef?.get() ?: return
        mainHandler.post { activity.finish() }
    }

    @JavascriptInterface
    fun setTitle(title: String) {
        val activity = activityRef?.get() ?: return
        mainHandler.post { activity.title = title }
    }

    @JavascriptInterface
    fun setStatusBarColor(colorStr: String, isLight: Boolean) {
        val activity = activityRef?.get() ?: return
        mainHandler.post {
            try {
                val color = Color.parseColor(colorStr)
                val window = activity.window
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = color
                WindowCompat.getInsetsController(window, window.decorView)?.let { ctrl ->
                    ctrl.isAppearanceLightStatusBars = isLight
                }
            } catch (e: Exception) { XLog.w(TAG, "setStatusBarColor failed: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun setNavigationBarColor(colorStr: String, isLight: Boolean) {
        val activity = activityRef?.get() ?: return
        mainHandler.post {
            try {
                val color = Color.parseColor(colorStr)
                val window = activity.window
                window.navigationBarColor = color
                WindowCompat.getInsetsController(window, window.decorView)?.let { ctrl ->
                    ctrl.isAppearanceLightNavigationBars = isLight
                }
            } catch (e: Exception) { XLog.w(TAG, "setNavigationBarColor failed: ${e.message}") }
        }
    }

    @JavascriptInterface
    fun storageGet(key: String, defValue: String?): String {
        val prefs = activityRef?.get()
            ?.getSharedPreferences(STORAGE_PREFIX + manifest.id, Context.MODE_PRIVATE) ?: return defValue ?: ""
        return prefs.getString(key, defValue) ?: defValue ?: ""
    }

    @JavascriptInterface
    fun storageSet(key: String, value: String?) {
        val prefs = activityRef?.get()
            ?.getSharedPreferences(STORAGE_PREFIX + manifest.id, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun storageRemove(key: String) {
        val prefs = activityRef?.get()
            ?.getSharedPreferences(STORAGE_PREFIX + manifest.id, Context.MODE_PRIVATE) ?: return
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun storageClear() {
        val prefs = activityRef?.get()
            ?.getSharedPreferences(STORAGE_PREFIX + manifest.id, Context.MODE_PRIVATE) ?: return
        prefs.edit().clear().apply()
    }

    @JavascriptInterface
    fun storageKeys(): String {
        val prefs = activityRef?.get()
            ?.getSharedPreferences(STORAGE_PREFIX + manifest.id, Context.MODE_PRIVATE)
            ?: return "[]"
        val all = prefs.all.keys
        return JSONArray(all).toString()
    }

    @JavascriptInterface
    fun installShortcut(): String {
        val activity = activityRef?.get() ?: return err("activity unavailable")
        return try {
            val launcherIntent = Intent(activity, MiniAppActivity::class.java).apply {
                putExtra(MiniAppActivity.EXTRA_PLUGIN_ID, manifest.id)
                action = Intent.ACTION_VIEW
            }
            val icon = loadIcon(activity)
            val shortcut = ShortcutInfoCompat.Builder(activity, "miniapp_${manifest.id}")
                .setShortLabel(manifest.name.take(12))
                .setLongLabel(manifest.name)
                .setIcon(icon)
                .setIntent(launcherIntent)
                .build()
            val result = ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            if (result) ok("shortcut created") else err("shortcut creation failed")
        } catch (e: Exception) {
            err("shortcut error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun navigate(page: String) {
        val wv = webViewRef?.get() ?: return
        val activity = activityRef?.get() ?: return
        val baseDir = findPluginDir(activity, manifest) ?: return
        val target = File(baseDir, page).takeIf { it.exists() && it.isFile } ?: return
        mainHandler.post { wv.loadUrl(target.toURI().toString()) }
    }

    @JavascriptInterface
    fun navigateBack(): Boolean {
        val wv = webViewRef?.get() ?: return false
        val canGo = wv.canGoBack()
        if (canGo) mainHandler.post { wv.goBack() }
        return canGo
    }

    internal fun handleScheme(uri: String) {
        val u = Uri.parse(uri)
        when (u.host) {
            "close" -> closeMiniApp()
            "share" -> share(u.getQueryParameter("title"), u.getQueryParameter("text"), u.getQueryParameter("url"))
            "toast" -> showToast(u.getQueryParameter("message") ?: "")
        }
    }

    internal fun onPageFinished() {}

    private fun parseArgs(argsJson: String?): Map<String, Any> = runCatching {
        val o = JSONObject(argsJson ?: "{}")
        val map = mutableMapOf<String, Any>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = o.get(k)
        }
        map
    }.getOrElse { emptyMap() }

    private fun postCallback(callbackId: String, resultJson: String) {
        val wv = webViewRef?.get() ?: return
        mainHandler.post {
            val quoted = JSONObject.quote(resultJson)
            val quotedCb = JSONObject.quote(callbackId)
            wv.evaluateJavascript("window.octopus._recv($quotedCb, $quoted)", null)
        }
    }

    private fun loadIcon(activity: Activity): IconCompat {
        val dirs = listOf(
            File(activity.filesDir, "plugins/${manifest.id}"),
            File(activity.filesDir, "generated_apps/${manifest.id}")
        )
        for (dir in dirs) {
            for (iconName in listOf("icon.png", "logo.png", "app.png")) {
                val f = File(dir, iconName)
                if (f.exists() && f.isFile && f.length() > 0) {
                    runCatching {
                        val bmp = FileInputStream(f).use { BitmapFactory.decodeStream(it) }
                        if (bmp != null) return IconCompat.createWithBitmap(bmp)
                    }
                }
            }
        }
        return IconCompat.createWithResource(activity, android.R.drawable.sym_def_app_icon)
    }

    private fun findPluginDir(activity: Activity, m: PluginManifest): File? {
        val candidates = linkedSetOf(m.id, m.id.substringAfterLast('/'))
        for (baseDir in listOf("plugins", "generated_apps")) {
            for (slug in candidates) {
                val pluginDir = File(activity.filesDir, "$baseDir/$slug")
                if (pluginDir.isDirectory) return pluginDir
            }
        }
        return null
    }

    private fun ok(data: String) = JSONObject().put("ok", true).put("data", data).toString()
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    companion object {
        private const val PAY_CONFIRM_TIMEOUT_MS = 15_000L
        private const val PAY_NETWORK_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val STORAGE_PREFIX = "miniapp_storage_"

        const val SHIM_JS = """
(function () {
  if (typeof octopusNative === 'undefined') return;

  var parse = function (s) { try { return JSON.parse(s); } catch (e) { return { ok: false, error: 'bad bridge response' }; } };
  var cbs = {};
  var cid = 1;

  window.octopus = {
    id: function () { return octopusNative.pluginId(); },
    name: function () { return octopusNative.pluginName(); },
    version: function () { return octopusNative.pluginVersion(); },

    callTool: function (name, args) {
      return parse(octopusNative.callTool(name, args ? JSON.stringify(args) : null));
    },
    callToolAsync: function (name, args) {
      return new Promise(function (resolve, reject) {
        var id = 'cb_' + (cid++);
        cbs[id] = { resolve: resolve, reject: reject };
        try { octopusNative.callToolAsync(name, args ? JSON.stringify(args) : null, id); }
        catch (e) { delete cbs[id]; reject(e); }
      });
    },

    fetch: function (url, options) {
      return new Promise(function (resolve, reject) {
        var id = 'cb_' + (cid++);
        cbs[id] = { resolve: resolve, reject: reject };
        try { octopusNative.httpFetch(String(url), options ? JSON.stringify(options) : null, id); }
        catch (e) { delete cbs[id]; reject(e); }
      });
    },

    pay: function (order) {
      return new Promise(function (resolve, reject) {
        var id = 'cb_' + (cid++);
        cbs[id] = { resolve: resolve, reject: reject };
        try { octopusNative.pay(order ? JSON.stringify(order) : null, id); }
        catch (e) { delete cbs[id]; reject(e); }
      });
    },
    device: function (cap, args) { return parse(octopusNative.deviceAutomate(cap, args ? JSON.stringify(args) : null)); },

    toast: function (msg) { try { octopusNative.showToast(String(msg || '')); } catch (e) {} },
    vibrate: function (pattern) {
      try {
        var p = Array.isArray(pattern) ? JSON.stringify(pattern.map(function(n){return Math.max(1, n|0)})) : '[100]';
        octopusNative.vibrate(p);
      } catch (e) {}
    },
    copy: function (text) { try { octopusNative.copyToClipboard(String(text || '')); } catch (e) {} },
    paste: function () { try { return octopusNative.readClipboard(); } catch (e) { return ''; } },
    share: function (opts) {
      try { var o = opts || {}; octopusNative.share(o.title || null, o.text || null, o.url || null); } catch (e) {}
    },
    openUrl: function (url) { try { octopusNative.openExternalUrl(String(url || '')); } catch (e) {} },
    openApp: function (id) { try { octopusNative.openMiniApp(String(id || '')); } catch (e) {} },
    close: function () { try { octopusNative.closeMiniApp(); } catch (e) {} },
    back: function () { try { return octopusNative.navigateBack(); } catch (e) { return false; } },
    setTitle: function (t) { try { octopusNative.setTitle(String(t || '')); } catch (e) {} },
    setStatusBar: function (color, light) { try { octopusNative.setStatusBarColor(String(color||'#000000'), !!light); } catch(e) {} },
    setNavBar: function (color, light) { try { octopusNative.setNavigationBarColor(String(color||'#000000'), !!light); } catch(e) {} },
    navigate: function (page) { try { octopusNative.navigate(String(page || '')); } catch (e) {} },
    installShortcut: function () { return parse(octopusNative.installShortcut()); },

    storage: {
      get: function (key, def) {
        try { var v = octopusNative.storageGet(String(key), def != null ? String(def) : null);
          try { return JSON.parse(v); } catch(e){ return v; }
        } catch (e) { return def; }
      },
      set: function (key, value) {
        try { var s = typeof value === 'string' ? value : JSON.stringify(value);
          octopusNative.storageSet(String(key), s);
        } catch (e) {}
      },
      remove: function (key) { try { octopusNative.storageRemove(String(key)); } catch(e) {} },
      clear: function () { try { octopusNative.storageClear(); } catch(e) {} },
      keys: function () { try { return JSON.parse(octopusNative.storageKeys()); } catch(e){ return []; } }
    },

    reportAction: function (actionType, params) {
      try { octopusNative.reportAction(actionType, params ? JSON.stringify(params) : null); } catch (e) {}
    },
    onAgentAction: null,
    onLifecycle: null,

    _lifecycle: function (state) {
      try { if (typeof window.octopus.onLifecycle === 'function') window.octopus.onLifecycle(state); } catch(e) {}
    },

    _recv: function (id, resultStr) {
      var cb = cbs[id]; if (!cb) return;
      delete cbs[id];
      try {
        var r = JSON.parse(resultStr);
        if (r && r.ok) { cb.resolve(r.data !== undefined ? r.data : null); }
        else { cb.reject(new Error((r && r.error) || 'unknown error')); }
      } catch (e) { cb.reject(e); }
    }
  };

  window.__octopusDispatch = function (actionType, paramsJson) {
    try {
      if (typeof window.octopus.onAgentAction !== 'function')
        return JSON.stringify({ ok: false, error: 'mini-app not registered onAgentAction' });
      var params = {}; try { params = JSON.parse(paramsJson || '{}'); } catch (e) {}
      var r = window.octopus.onAgentAction(actionType, params);
      if (r && typeof r.then === 'function') {
        r.then(function(d){ window.octopus._agentResult({ok:true,data:d==null?'':String(d)}); })
         .catch(function(e){ window.octopus._agentResult({ok:false,error:String((e&&e.message)||e)}); });
        return JSON.stringify({ ok: true, data: '__pending__' });
      }
      return JSON.stringify({ ok: true, data: (r == null ? '' : String(r)) });
    } catch (e) {
      return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
    }
  };

  window.octopus._agentResult = function(result) {
    try {
      if (window.__octopusAgentCallbackId) {
        octopusNative.reportAction('_agent_result', JSON.stringify({cbId: window.__octopusAgentCallbackId, result: result}));
      }
    } catch(e){}
  };

  if (typeof navigator !== 'undefined') {
    if (!navigator.vibrate) navigator.vibrate = function(p){ octopus.vibrate(p); return true; };
    if (!navigator.share) navigator.share = function(opts){ octopus.share(opts); return Promise.resolve(); };
    if (!navigator.clipboard) {
      navigator.clipboard = {
        writeText: function(t){ octopus.copy(t); return Promise.resolve(); },
        readText: function(){ return Promise.resolve(octopus.paste()); }
      };
    } else {
      var _wt = navigator.clipboard.writeText, _rt = navigator.clipboard.readText;
      navigator.clipboard.writeText = function(t){ try{ octopus.copy(t); }catch(e){} return _wt ? _wt.call(this,t) : Promise.resolve(); };
      navigator.clipboard.readText = function(){ try{ var t=octopus.paste(); if(t)return Promise.resolve(t);}catch(e){} return _rt ? _rt.call(this) : Promise.resolve(''); };
    }
  }
})();
"""
    }
}
