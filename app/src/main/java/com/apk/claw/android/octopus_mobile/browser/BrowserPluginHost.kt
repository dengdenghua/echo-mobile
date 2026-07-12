package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.util.Log
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import okhttp3.Request
import java.io.File

object BrowserPluginHost {

    private const val TAG = "BrowserPluginHost"

    data class UserScriptEntry(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val matchRegexes: List<Regex>,
        val excludeRegexes: List<Regex>,
        val runAt: Userscript.RunAt,
        val grants: Set<String>,
        val js: String,
        val css: String,
        val enabled: Boolean = true,
        val blockRules: List<Regex> = emptyList(),
    ) {
        fun matches(url: String, host: String): Boolean {
            if (!enabled) return false
            for (ex in excludeRegexes) {
                if (ex.containsMatchIn(url)) return false
            }
            if (matchRegexes.isEmpty()) return true
            for (r in matchRegexes) {
                if (r.containsMatchIn(url)) return true
            }
            return false
        }
    }

    @Volatile
    private var scripts: List<UserScriptEntry> = emptyList()

    @Volatile
    private var globalBlockRules: List<Regex> = emptyList()

    @Volatile
    var stealthEnabled: Boolean = true

    @Volatile
    private var scriptCache: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>?): Boolean {
                return size > 32
            }
        }
    )

    fun setScripts(list: List<UserScriptEntry>) {
        scripts = list.filter { it.enabled }
        scriptCache.clear()
        Log.i(TAG, "scripts set: ${scripts.size} active")
    }

    fun setBlockRules(patterns: List<String>) {
        globalBlockRules = patterns.mapNotNull { p ->
            runCatching { Regex(p, RegexOption.IGNORE_CASE) }
                .onFailure { Log.w(TAG, "bad block rule: $p") }
                .getOrNull()
        }
        Log.i(TAG, "block rules set: ${globalBlockRules.size}")
    }

    fun clear() {
        scripts = emptyList()
        globalBlockRules = emptyList()
        scriptCache.clear()
    }

    fun getScripts(): List<UserScriptEntry> = scripts

    // ── 本地清单加载 ──────────────────────────────

    fun manifestFile(context: Context): File =
        File(File(context.filesDir, "registry").apply { mkdirs() }, "browser_plugins.json")

    private data class PluginFile(
        val stealthEnabled: Boolean = true,
        val plugins: List<PluginDef> = emptyList(),
        val blockRules: List<String> = emptyList(),
    )

    private data class PluginDef(
        val id: String = "",
        val name: String = "",
        val hostPattern: String = "*",
        val js: String = "",
        val enabled: Boolean = true,
    )

    private val gson = Gson()

    fun loadFromFile(context: Context) {
        val f = manifestFile(context)
        if (!f.isFile) return
        val parsed = runCatching { gson.fromJson(f.readText(), PluginFile::class.java) }
            .onFailure { Log.w(TAG, "browser_plugins.json parse failed: ${it.message}") }
            .getOrNull() ?: return
        stealthEnabled = parsed.stealthEnabled
        val legacyScripts = parsed.plugins.map { def ->
            val hostRegex = when {
                def.hostPattern.isBlank() || def.hostPattern == "*" -> null
                else -> runCatching { Regex(def.hostPattern, RegexOption.IGNORE_CASE) }.getOrNull()
            }
            UserScriptEntry(
                id = def.id,
                name = def.name,
                version = "1.0.0",
                description = "",
                author = "",
                matchRegexes = listOfNotNull(hostRegex),
                excludeRegexes = emptyList(),
                runAt = Userscript.RunAt.DOCUMENT_IDLE,
                grants = emptySet(),
                js = def.js,
                css = "",
                enabled = def.enabled,
            )
        }
        val existing = scripts.filter { it.id.startsWith("plugin_") || it.id.startsWith("assets_") }
        setScripts(legacyScripts + existing)
        setBlockRules(parsed.blockRules)
    }

    // ── 从 PluginManifest 构建 UserScriptEntry ──

    fun buildEntryFromManifest(
        manifest: com.apk.claw.android.plugin.PluginManifest,
        dir: File?,
        context: Context? = null,
        idPrefix: String = ""
    ): UserScriptEntry? {
        var js: String
        val name = manifest.name
        val id = (idPrefix + manifest.id).ifBlank { name.lowercase().replace(Regex("[^a-z0-9]+"), "-") }

        if (manifest.script.isNotBlank() && dir != null) {
            val scriptFile = File(dir, manifest.script)
            if (scriptFile.isFile) {
                val source = scriptFile.readText()
                val parsed = UserscriptParser.parse(source, fallbackId = manifest.id)
                if (parsed != null) {
                    return buildEntryFromUserscript(parsed, context, idPrefix)
                }
                js = source
            } else {
                js = manifest.js
            }
        } else {
            js = manifest.js
        }

        if (js.isBlank()) return null

        val matchRegexes = when {
            manifest.match.isNotEmpty() -> manifest.match.mapNotNull { UserscriptParser.matchPatternToRegex(it) }
            manifest.hostPattern.isNotBlank() && manifest.hostPattern != "*" -> {
                runCatching { Regex(manifest.hostPattern, RegexOption.IGNORE_CASE) }
                    .onFailure { Log.w(TAG, "bad hostPattern for ${manifest.id}: ${manifest.hostPattern}") }
                    .getOrNull()?.let { listOf(it) } ?: emptyList()
            }
            else -> emptyList()
        }

        val excludeRegexes = manifest.exclude.mapNotNull { UserscriptParser.matchPatternToRegex(it) }
        val blockRegexes = manifest.blockRules.mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

        val requiresJs = if (manifest.requires.isNotEmpty() && context != null) {
            downloadRequires(manifest.requires, context)
        } else ""

        val grants = manifest.grants.toSet()
        val bridgeScript = buildBridgeScript(grants)

        val css = manifest.css
        val cssWrapper = if (css.isNotBlank()) {
            "(function(){var s=document.createElement('style');s.textContent=${gson.toJson(css)};document.head.appendChild(s);})();\n"
        } else ""

        val wrapped = "(function(){\n${bridgeScript}\n${requiresJs}\n${js}\n})();\n"

        return UserScriptEntry(
            id = id,
            name = name,
            version = manifest.version,
            description = manifest.description,
            author = manifest.author,
            matchRegexes = matchRegexes,
            excludeRegexes = excludeRegexes,
            runAt = Userscript.RunAt.parse(manifest.runAt),
            grants = grants,
            js = cssWrapper + wrapped,
            css = "",
            enabled = true,
            blockRules = blockRegexes,
        )
    }

    fun buildEntryFromUserscript(
        script: Userscript,
        context: Context? = null,
        idPrefix: String = ""
    ): UserScriptEntry? {
        if (script.js.isBlank()) return null

        val matchRegexes = (script.match + script.include).mapNotNull { UserscriptParser.matchPatternToRegex(it) }
        val excludeRegexes = script.exclude.mapNotNull { UserscriptParser.matchPatternToRegex(it) }
        val grants = script.grants.toSet()

        val requiresJs = if (script.requires.isNotEmpty() && context != null) {
            downloadRequires(script.requires, context)
        } else ""

        val bridgeScript = buildBridgeScript(grants)
        val css = script.css
        val cssWrapper = if (css.isNotBlank()) {
            "(function(){var s=document.createElement('style');s.type='text/css';s.textContent=${gson.toJson(css)};(document.head||document.documentElement).appendChild(s);})();\n"
        } else ""

        if (script.resources.isNotEmpty() && context != null) {
            cacheResources(script.resources, context)
        }

        val wrapped = "(function(){\n${bridgeScript}\n${requiresJs}\n${script.js}\n})();\n"

        return UserScriptEntry(
            id = idPrefix + script.id,
            name = script.name,
            version = script.version,
            description = script.description,
            author = script.author,
            matchRegexes = matchRegexes,
            excludeRegexes = excludeRegexes,
            runAt = script.runAt,
            grants = grants,
            js = cssWrapper + wrapped,
            css = "",
            enabled = true,
        )
    }

    fun buildEntryFromInlineJs(
        id: String,
        name: String,
        hostPattern: String,
        js: String,
    ): UserScriptEntry {
        val hostRegex = when {
            hostPattern.isBlank() || hostPattern == "*" -> null
            else -> runCatching { Regex(hostPattern, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        val bridgeScript = buildBridgeScript(setOf("GM_addStyle", "GM_setValue", "GM_getValue",
            "GM_xmlhttpRequest", "GM_notification", "GM_setClipboard", "GM_info"))
        val wrapped = "(function(){\n${bridgeScript}\n${js}\n})();\n"
        return UserScriptEntry(
            id = id, name = name, version = "1.0.0", description = "", author = "",
            matchRegexes = listOfNotNull(hostRegex), excludeRegexes = emptyList(),
            runAt = Userscript.RunAt.DOCUMENT_IDLE,
            grants = setOf("GM_addStyle", "GM_setValue", "GM_getValue",
                "GM_xmlhttpRequest", "GM_notification", "GM_setClipboard"),
            js = wrapped, css = "", enabled = true,
        )
    }

    private fun buildBridgeScript(grants: Set<String>): String {
        val sb = StringBuilder()
        val safeGrants = grants.toMutableSet()
        if (safeGrants.isEmpty()) {
            safeGrants.addAll(setOf("GM_addStyle", "GM_setValue", "GM_getValue",
                "GM_xmlhttpRequest", "GM_notification", "GM_setClipboard", "GM_info"))
        }
        sb.append("  var __gm_bridge = window['").append(GmApiBridge.JS_BRIDGE_NAME).append("'];\n")
        if ("GM_addStyle" in safeGrants) {
            sb.append("  function GM_addStyle(c){var s=document.createElement('style');s.type='text/css';s.textContent=c;(document.head||document.documentElement).appendChild(s);return s;}\n")
        }
        if ("GM_setValue" in safeGrants) {
            sb.append("  function GM_setValue(k,v){try{__gm_bridge&&__gm_bridge.setValue(String(k),JSON.stringify({v:v}));}catch(e){}}\n")
        }
        if ("GM_getValue" in safeGrants) {
            sb.append("  function GM_getValue(k,d){try{var r=__gm_bridge&&__gm_bridge.getValue(String(k));if(r==null)return d;return JSON.parse(r).v;}catch(e){return d;}}\n")
        }
        if ("GM_deleteValue" in safeGrants) {
            sb.append("  function GM_deleteValue(k){try{__gm_bridge&&__gm_bridge.deleteValue(String(k));}catch(e){}}\n")
        }
        if ("GM_listValues" in safeGrants) {
            sb.append("  function GM_listValues(){try{return JSON.parse(__gm_bridge.listValues()||'[]');}catch(e){return[];}}\n")
        }
        if ("GM_setClipboard" in safeGrants) {
            sb.append("  function GM_setClipboard(t){try{__gm_bridge&&__gm_bridge.setClipboard(String(t),'text/plain');}catch(e){}}\n")
        }
        if ("GM_notification" in safeGrants) {
            sb.append("  function GM_notification(o,t,i,c){var x=typeof o==='string'?{text:o,title:t||''}:o||{};var cid='gn_'+Math.random().toString(36).substr(2,9);if(typeof o==='object'&&o.onclick){window[cid]=function(){try{o.onclick();}catch(e){}};}__gm_bridge&&__gm_bridge.notification(cid,JSON.stringify({text:x.text||'',title:x.title||'',image:x.image||x.icon||''}));}\n")
        }
        if ("GM_openInTab" in safeGrants) {
            sb.append("  function GM_openInTab(u,a){__gm_bridge&&__gm_bridge.openInTab(String(u),a!==false);return{close:function(){}};}\n")
        }
        if ("GM_registerMenuCommand" in safeGrants) {
            sb.append("  var __gm_ms=0;function GM_registerMenuCommand(n,f,k){var i='gm_m_'+(++__gm_ms);window[i]=function(){try{f();}catch(e){}};__gm_bridge&&__gm_bridge.registerMenuCommand(i,String(n),k||null);return i;}\n")
        }
        if ("GM_unregisterMenuCommand" in safeGrants) {
            sb.append("  function GM_unregisterMenuCommand(i){try{__gm_bridge&&__gm_bridge.unregisterMenuCommand(String(i));delete window[i];}catch(e){}}\n")
        }
        if ("GM_xmlhttpRequest" in safeGrants) {
            sb.append("  function GM_xmlhttpRequest(d){if(!d)return{abort:function(){}};var cid='gx_'+Math.random().toString(36).substr(2,9);var ab=false;window[cid]=function(ev,a){if(ab)return;try{if(ev==='load'&&d.onload)d.onload(a);else if(ev==='error'&&d.onerror)d.onerror(a);else if(ev==='timeout'&&d.ontimeout)d.ontimeout(a);else if(ev==='abort'&&d.onabort)d.onabort(a);}catch(e){}if(ev==='load'||ev==='error'||ev==='timeout'||ev==='abort')delete window[cid];};__gm_bridge&&__gm_bridge.xmlhttpRequest(cid,JSON.stringify({url:d.url||'',method:(d.method||'GET').toUpperCase(),headers:d.headers||{},data:typeof d.data==='string'?d.data:null,timeout:d.timeout||0,withCredentials:!!d.withCredentials,responseType:d.responseType||''}));return{abort:function(){ab=true;__gm_bridge&&__gm_bridge.abortXhr(cid);}};}\n")
        }
        if ("GM_getResourceText" in safeGrants) {
            sb.append("  function GM_getResourceText(n){try{return __gm_bridge?__gm_bridge.getResourceText(String(n)):'';}catch(e){return'';}}\n")
        }
        if ("GM_getResourceURL" in safeGrants) {
            sb.append("  function GM_getResourceURL(n){try{return __gm_bridge?__gm_bridge.getResourceUrl(String(n)):'';}catch(e){return'';}}\n")
        }
        if ("GM_info" in safeGrants) {
            sb.append("  window.GM_info={scriptHandler:'Octopus',version:'1.0',platform:{name:'Android'}};\n")
        }
        return sb.toString()
    }

    private fun downloadRequires(urls: List<String>, context: Context): String {
        val sb = StringBuilder()
        val http = OctoHttp.shared
        val uncached = mutableListOf<String>()
        for (url in urls) {
            val cached = scriptCache[url]
            if (cached != null) {
                sb.append("\n").append(cached).append("\n")
            } else {
                uncached.add(url)
            }
        }
        // 未缓存的 @require 脚本放到后台线程下载,避免主线程同步 HTTP 导致 ANR。
        // 下载完成后写入缓存,下次构建脚本时即可命中缓存直接拼入。
        if (uncached.isNotEmpty()) {
            Thread {
                for (url in uncached) {
                    if (scriptCache.containsKey(url)) continue
                    runCatching {
                        val req = Request.Builder().url(url).build()
                        http.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val content = resp.body?.string().orEmpty()
                                if (content.isNotBlank()) {
                                    scriptCache[url] = content
                                }
                            }
                        }
                    }.onFailure { Log.w(TAG, "@require download failed: $url (${it.message})") }
                }
            }.apply { isDaemon = true }.start()
        }
        return sb.toString()
    }

    private fun cacheResources(resources: Map<String, String>, context: Context) {
        val http = OctoHttp.shared
        // @resource 下载放到后台线程,避免主线程同步 HTTP 导致 ANR。
        Thread {
            for ((name, url) in resources) {
                runCatching {
                    val req = Request.Builder().url(url).build()
                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes() ?: return@use
                            val gmBridge = GmApiBridge(context)
                            gmBridge.cacheResource(name, bytes)
                        }
                    }
                }.onFailure { Log.w(TAG, "@resource download failed: $name ($url): ${it.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    // ── SystemWebViewEngine 调用的读出口 ────────────

    /**
     * 文档开始时注入的 stealth JS。
     *
     * 优先级：
     * 1. [StealthManager] 启用且本对象 stealthEnabled → 用 [buildStealthJs] 动态生成（UA/Canvas/WebGL 全套伪装）。
     * 2. 仅本对象 stealthEnabled（StealthManager 关）→ 用兜底 [STEALTH_JS]（基础反检测，不改 UA）。
     * 3. 两者都关 → null（不注入）。
     *
     * 这样 UI 层的"指纹保护"开关（控制 StealthManager.isEnabled）与 browser_plugins.json 里的 stealthEnabled
     * 互不冲突：用户开指纹保护走完整 profile；未开但配置文件要求 stealth 走兜底。
     */
    fun documentStartScript(): String? {
        if (!stealthEnabled) return null
        return if (StealthManager.isEnabled()) {
            buildStealthJs(StealthManager.getCurrent())
        } else {
            STEALTH_JS
        }
    }

    fun scriptsForUrl(url: String?, runAt: Userscript.RunAt): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        val host = hostOf(url) ?: return emptyList()
        val current = scripts
        if (current.isEmpty()) return emptyList()
        return current.filter { it.runAt == runAt && it.matches(url, host) }.map { it.js }
    }

    fun allBlockRules(): List<Regex> {
        val scriptRules = scripts.flatMap { it.blockRules }
        return globalBlockRules + scriptRules
    }

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val rules = allBlockRules()
        if (rules.isEmpty()) return false
        return rules.any { it.containsMatchIn(url) }
    }

    private fun hostOf(url: String): String? = runCatching {
        android.net.Uri.parse(url).host
    }.getOrNull()

    const val STEALTH_JS = """
(function () {
  'use strict';
  var asNative = function (fn, name) {
    try {
      Object.defineProperty(fn, 'toString', {
        value: function () { return 'function ' + (name || fn.name || '') + '() { [native code] }'; },
        configurable: true
      });
    } catch (e) {}
    return fn;
  };
  try {
    Object.defineProperty(Object.getPrototypeOf(navigator), 'webdriver', { get: function () { return false; }, configurable: true });
  } catch (e) {
    try { Object.defineProperty(navigator, 'webdriver', { get: function () { return false; }, configurable: true }); } catch (e2) {}
  }
  try {
    if (!window.chrome) { window.chrome = { runtime: {}, app: {}, loadTimes: function () {}, csi: function () {} }; }
  } catch (e) {}
  try {
    var mk = function (n, d, f) { return { name: n, description: d, filename: f, length: 1 }; };
    var fake = [
      mk('Chrome PDF Plugin', 'Portable Document Format', 'internal-pdf-viewer'),
      mk('Chrome PDF Viewer', '', 'mhjfbmdgcfjbbpaeojofohoefgiehjai'),
      mk('Native Client', '', 'internal-nacl-plugin')
    ];
    Object.defineProperty(navigator, 'plugins', { get: function () { return fake; }, configurable: true });
  } catch (e) {}
  try {
    Object.defineProperty(navigator, 'languages', { get: function () { return ['zh-CN', 'zh', 'en-US', 'en']; }, configurable: true });
  } catch (e) {}
  try { Object.defineProperty(navigator, 'hardwareConcurrency', { get: function () { return 8; }, configurable: true }); } catch (e) {}
  try { Object.defineProperty(navigator, 'deviceMemory', { get: function () { return 8; }, configurable: true }); } catch (e) {}
  try {
    var pq = navigator.permissions && navigator.permissions.query;
    if (pq) {
      navigator.permissions.query = asNative(function (p) {
        if (p && p.name === 'notifications') { return Promise.resolve({ state: Notification.permission, onchange: null }); }
        return pq.call(navigator.permissions, p);
      }, 'query');
    }
  } catch (e) {}
  try {
    var patchGL = function (proto) {
      if (!proto || !proto.getParameter) return;
      var og = proto.getParameter;
      proto.getParameter = asNative(function (p) {
        if (p === 37445) return 'Intel Inc.';
        if (p === 37446) return 'Intel Iris OpenGL Engine';
        return og.call(this, p);
      }, 'getParameter');
    };
    if (window.WebGLRenderingContext) patchGL(WebGLRenderingContext.prototype);
    if (window.WebGL2RenderingContext) patchGL(WebGL2RenderingContext.prototype);
  } catch (e) {}
})();
"""
}
