package com.apk.claw.android.ui.browser

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 书签管理器 —— MMKV 持久化。
 *
 * 单例([object]):所有调用方共享同一份内存缓存,避免每个 `BookmarkManager()` 实例各自
 * 维护 cache 导致增删后其它实例仍读旧值(与 [CommonSiteStore] 同一方案)。
 * 读写方法加 @Synchronized 防 read-modify-write 竞态。
 */
object BookmarkManager {

    private const val KEY_BOOKMARKS = "BROWSER_BOOKMARKS"

    private val gson = Gson()
    private var cache: MutableList<BookmarkItem>? = null

    @Synchronized
    fun getAll(): List<BookmarkItem> {
        if (cache == null) {
            cache = loadFromStorage().toMutableList()
        }
        return cache!!.toList()
    }

    @Synchronized
    fun add(url: String, title: String) {
        val list = getAll().toMutableList()
        if (list.any { it.url == url }) return
        list.add(BookmarkItem(url, title, System.currentTimeMillis()))
        save(list)
    }

    @Synchronized
    fun remove(url: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.url == url }
        save(list)
    }

    /**
     * 合并远端书签:按 URL 去重 union,同 URL 保留 [BookmarkItem.addedTs] 较大者。
     */
    @Synchronized
    fun mergeFromRemote(json: String) {
        val remote: List<BookmarkItem> = try {
            val type = object : TypeToken<List<BookmarkItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w("BookmarkManager", "mergeFromRemote parse failed", e)
            return
        }
        if (remote.isEmpty()) return
        val byUrl = LinkedHashMap<String, BookmarkItem>()
        for (item in getAll() + remote) {
            val ex = byUrl[item.url]
            if (ex == null || item.addedTs > ex.addedTs) byUrl[item.url] = item
        }
        save(byUrl.values.toList())
    }

    fun findByUrl(url: String): BookmarkItem? {
        return getAll().find { it.url == url }
    }

    fun isBookmarked(url: String): Boolean {
        return findByUrl(url) != null
    }

    private fun save(list: List<BookmarkItem>) {
        cache = list.toMutableList()
        KVUtils.putString(KEY_BOOKMARKS, gson.toJson(list))
    }

    private fun loadFromStorage(): List<BookmarkItem> {
        val json = KVUtils.getString(KEY_BOOKMARKS, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<BookmarkItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            XLog.w("BookmarkManager", "load bookmarks failed", e)
            emptyList()
        }
    }
}

data class BookmarkItem(
    val url: String,
    val title: String,
    val addedTs: Long = System.currentTimeMillis()
)
