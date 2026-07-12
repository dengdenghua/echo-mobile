package com.apk.claw.android.ui.browser

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 浏览器历史记录持久化(MMKV)—— 与 [BookmarkManager] 同一方案:单例 + 内存缓存 +
 * @Synchronized 防 read-modify-write 竞态。最多保留最近 [MAX_ENTRIES] 条,超出按访问时间截断。
 *
 * 之前的"历史"只是 BrowserScreen 里的内存 list(最多 10 条,退出即丢),无法跨设备同步;
 * 这里补一个真正的持久化存储,供 [BrowserSync] 读写与合并。
 */
object HistoryStore {

    private const val KEY_HISTORY = "BROWSER_HISTORY"
    private const val MAX_ENTRIES = 500

    private val gson = Gson()
    private var cache: MutableList<HistoryEntry>? = null

    @Synchronized
    fun getAll(): List<HistoryEntry> {
        if (cache == null) {
            cache = loadFromStorage().toMutableList()
        }
        return cache!!.toList()
    }

    @Synchronized
    fun add(title: String, url: String) {
        val list = getAll().toMutableList()
        // 同 URL 去重:更新标题、刷新访问时间、移到队首
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(title, url, System.currentTimeMillis()))
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        save(list)
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    /**
     * 合并远端历史:按 URL 去重,同 URL 保留 [HistoryEntry.visitedTs] 较大者(最新访问),
     * 合并后按访问时间降序,截断到 [MAX_ENTRIES] 条。
     */
    @Synchronized
    fun mergeFromRemote(json: String) {
        val remote: List<HistoryEntry> = try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w("HistoryStore", "mergeFromRemote parse failed", e)
            return
        }
        if (remote.isEmpty()) return
        val byUrl = LinkedHashMap<String, HistoryEntry>()
        for (item in getAll() + remote) {
            val ex = byUrl[item.url]
            if (ex == null || item.visitedTs > ex.visitedTs) byUrl[item.url] = item
        }
        val merged = byUrl.values.sortedByDescending { it.visitedTs }
        val trimmed = if (merged.size > MAX_ENTRIES) merged.take(MAX_ENTRIES) else merged
        save(trimmed)
    }

    private fun save(list: List<HistoryEntry>) {
        cache = list.toMutableList()
        KVUtils.putString(KEY_HISTORY, gson.toJson(list))
    }

    private fun loadFromStorage(): List<HistoryEntry> {
        val json = KVUtils.getString(KEY_HISTORY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w("HistoryStore", "load history failed", e)
            emptyList()
        }
    }
}

/**
 * 浏览器历史条目。[visitedTs] 用于跨设备合并时按"最近访问时间"去重与排序。
 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val visitedTs: Long = System.currentTimeMillis(),
)
