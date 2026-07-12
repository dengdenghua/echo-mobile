package com.apk.claw.android.ui.browser

import android.content.Context
import android.util.Log
import com.apk.claw.android.octopus_mobile.browser.BrowserPluginHost
import com.apk.claw.android.octopus_mobile.browser.UserscriptParser
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 油猴脚本商店 —— 已安装脚本的持久化与管理。
 *
 * 编排路线：
 *  - [install]   安装时解析 ==UserScript== 头部元数据（复用 [UserscriptParser]），落库到 MMKV。
 *  - [uninstall] / [toggle] 修改后立刻 [syncToPluginHost] 推送给 [BrowserPluginHost]，
 *                使新脚本在下次页面加载时生效（按 url + runAt 过滤）。
 *  - [getEnabledForUrl] 供 UI 展示「当前页面命中了哪些脚本」。
 *
 * 持久化在 MMKV（与 [BookmarkManager] 同范式），脚本 ID 统一加 `userscript_` 前缀，
 * 与 PluginManager 管理的 `plugin_` / `assets_` 来源区分，避免重复加载。
 */
object UserscriptStore {

    private const val TAG = "UserscriptStore"
    private const val KEY = "BROWSER_USERSCRIPTS"
    private const val ID_PREFIX = "userscript_"

    private val gson = Gson()
    private var cache: MutableList<UserscriptEntry>? = null

    // ── 数据模型 ──────────────────────────────────

    /**
     * 已安装脚本元数据。code 保留原始 ==UserScript== 源码，便于重新解析/同步到 BrowserPluginHost。
     */
    data class UserscriptEntry(
        val id: String,
        val name: String,
        val description: String,
        val author: String,
        val version: String,
        val matchPatterns: List<String>,
        val code: String,
        val enabled: Boolean,
        val source: String,   // "store" / "url" / "manual"
    )

    // ── 读 ──────────────────────────────────────

    @Synchronized
    fun listInstalled(): List<UserscriptEntry> {
        if (cache == null) {
            cache = loadFromStorage().toMutableList()
        }
        return cache!!.toList()
    }

    fun findById(id: String): UserscriptEntry? = listInstalled().find { it.id == id }

    /** 返回当前 URL 命中的已启用脚本（供 UI 展示）。 */
    fun getEnabledForUrl(url: String): List<UserscriptEntry> {
        if (url.isBlank()) return emptyList()
        val parsed = listInstalled().filter { it.enabled }
        return parsed.filter { entry ->
            if (entry.matchPatterns.isEmpty()) return@filter false
            entry.matchPatterns.any { pattern ->
                val regex = UserscriptParser.matchPatternToRegex(pattern)
                regex == null || regex.containsMatchIn(url)
            }
        }
    }

    // ── 写 ──────────────────────────────────────

    /**
     * 安装一个脚本。code 必须包含 ==UserScript== 头部，否则用裸代码兜底（matchPatterns 为空 = 全站生效）。
     * 同 id 覆盖旧版本。
     */
    @Synchronized
    fun install(code: String, source: String = "manual"): UserscriptEntry? {
        val parsed = UserscriptParser.parse(code)
        val entry = if (parsed != null) {
            UserscriptEntry(
                id = ID_PREFIX + parsed.id.ifBlank { slugify(parsed.name) },
                name = parsed.name,
                description = parsed.description,
                author = parsed.author,
                version = parsed.version,
                matchPatterns = (parsed.match + parsed.include).filter { it.isNotBlank() },
                code = code,
                enabled = true,
                source = source,
            )
        } else {
            // 无头部：当作全站裸脚本，给予最小元数据
            UserscriptEntry(
                id = ID_PREFIX + slugify("script-" + System.currentTimeMillis().toString(36)),
                name = "未命名脚本",
                description = "",
                author = "",
                version = "1.0.0",
                matchPatterns = emptyList(),
                code = code,
                enabled = true,
                source = source,
            )
        }
        val list = listInstalled().toMutableList()
        list.removeAll { it.id == entry.id }
        list.add(0, entry)
        save(list)
        Log.i(TAG, "installed: ${entry.id} (${entry.name})")
        return entry
    }

    /** 用预构造的元数据直接安装（推荐脚本列表用，避免重新解析）。 */
    @Synchronized
    fun installEntry(entry: UserscriptEntry) {
        val list = listInstalled().toMutableList()
        list.removeAll { it.id == entry.id }
        list.add(0, entry)
        save(list)
        Log.i(TAG, "installed entry: ${entry.id} (${entry.name})")
    }

    @Synchronized
    fun uninstall(id: String) {
        val list = listInstalled().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) {
            save(list)
            Log.i(TAG, "uninstalled: $id")
        }
    }

    @Synchronized
    fun toggle(id: String): Boolean {
        val list = listInstalled().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val cur = list[idx]
        list[idx] = cur.copy(enabled = !cur.enabled)
        save(list)
        Log.i(TAG, "toggled $id -> ${list[idx].enabled}")
        return list[idx].enabled
    }

    // ── 同步到 BrowserPluginHost ──────────────────

    /**
     * 把已安装脚本构建为 [BrowserPluginHost.UserScriptEntry] 并合并进 BrowserPluginHost 的活动脚本表。
     * 调用时机：[install] / [uninstall] / [toggle] 后，以及浏览器初始化时。
     *
     * 合并策略：保留 PluginManager 注入的 `plugin_` / `assets_` 来源脚本，替换所有 `userscript_` 前缀的脚本。
     */
    fun syncToPluginHost(context: Context? = null) {
        val installed = listInstalled()
        val storeEntries = installed.mapNotNull { entry ->
            runCatching {
                val parsed = UserscriptParser.parse(entry.code) ?: return@runCatching null
                BrowserPluginHost.buildEntryFromUserscript(
                    script = parsed.copy(),
                    context = context,
                    idPrefix = "",
                )?.copy(enabled = entry.enabled, id = entry.id)
            }.getOrElse {
                XLog.w(TAG, "build entry failed for ${entry.id}: ${it.message}")
                null
            }
        }
        val kept = BrowserPluginHost.getScripts().filter { !it.id.startsWith(ID_PREFIX) }
        BrowserPluginHost.setScripts(kept + storeEntries)
        Log.i(TAG, "synced ${storeEntries.size} store scripts to BrowserPluginHost")
    }

    // ── 持久化 ──────────────────────────────────

    private fun save(list: List<UserscriptEntry>) {
        cache = list.toMutableList()
        KVUtils.putString(KEY, gson.toJson(list))
    }

    private fun loadFromStorage(): List<UserscriptEntry> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<UserscriptEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w(TAG, "load installed scripts failed", e)
            emptyList()
        }
    }

    private fun slugify(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "script" }
}
