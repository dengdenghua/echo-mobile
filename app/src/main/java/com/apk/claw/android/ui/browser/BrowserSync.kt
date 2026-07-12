package com.apk.claw.android.ui.browser

import android.content.Context
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.server.RemoteConsoleGateway
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 浏览器跨设备云同步数据条目(对应服务端 browser_sync 一行的客户端投影)。
 */
data class SyncPayload(
    val dataType: String,
    val payload: String,
    val deviceId: String,
    val updatedAt: Long,
)

/** 单个 data_type 的同步状态(最后同步时间 + 参与设备)。 */
data class SyncTypeInfo(
    val lastSync: Long,
    val devices: List<String>,
)

/** 三类同步数据的状态汇总。 */
data class SyncStatus(
    val bookmark: SyncTypeInfo?,
    val history: SyncTypeInfo?,
    val tab: SyncTypeInfo?,
)

/** 同步数据类型枚举 —— [apiName] 对应服务端 browser_sync.data_type 列。 */
enum class SyncDataType(val apiName: String) {
    BOOKMARK("bookmark"),
    HISTORY("history"),
    TAB("tab"),
}

/**
 * 浏览器跨设备云同步 —— 通过 Octopus 服务端中转书签/历史/标签页。
 *
 * 鉴权走 `Authorization: Bearer ${AccountStore.token}`(与 AccountGateway 同一套 JWT)。
 * device_id 复用 [RemoteConsoleGateway.deviceId](已配对设备的稳定 id);未配对时本地生成
 * 一个 `d_<hex>` 并缓存,保证未配对设备也能同步。
 *
 * 合并语义:
 *  - 书签:按 URL union,同 URL 取 addedTs 较新者(见 [BookmarkManager.mergeFromRemote])
 *  - 历史:按 URL 去重,同 URL 取 visitedTs 较新者(见 [HistoryStore.mergeFromRemote])
 *  - 标签页:按 URL 去重,本地未打开的 URL 补为新标签(见 [BrowserTabsStore.mergeFromRemote])
 */
object BrowserSync {

    private const val TAG = "BrowserSync"
    private const val KEY_DEVICE_ID = "BROWSER_SYNC_DEVICE_ID"
    private const val KEY_LAST_PULL_TS = "BROWSER_SYNC_LAST_PULL_TS"
    private const val KEY_LAST_PUSH_TS_PREFIX = "BROWSER_SYNC_LAST_PUSH_TS_"
    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 本机稳定 device_id:优先复用已配对设备的 id,否则生成并缓存一个。 */
    val deviceId: String
        get() {
            KVUtils.getString(KEY_DEVICE_ID, "").takeIf { it.isNotEmpty() }?.let { return it }
            val paired = RemoteConsoleGateway.deviceId
            if (paired.isNotEmpty()) {
                KVUtils.putString(KEY_DEVICE_ID, paired)
                return paired
            }
            val chars = "0123456789abcdef"
            val sb = StringBuilder("d_")
            val now = System.currentTimeMillis()
            // 用时间戳低 16 位作前 4 个 hex 字符,再补 12 个随机 hex,共 16 位
            repeat(4) { sb.append(chars[((now shr (it * 4)).toInt() and 0xF)]) }
            repeat(12) { sb.append(chars[(0..15).random()]) }
            val id = sb.toString()
            KVUtils.putString(KEY_DEVICE_ID, id)
            return id
        }

    private fun base() = AccountConfig.baseUrl.trimEnd('/')

    /** 是否具备同步前提:已登录且配置了服务端。 */
    fun isReady(): Boolean = AccountStore.isLoggedIn && AccountConfig.baseUrl.isNotBlank()

    /** 上传本机某类数据。成功返回 true。 */
    suspend fun push(context: Context, dataType: SyncDataType): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext false
        try {
            val payload = when (dataType) {
                SyncDataType.BOOKMARK -> gson.toJson(BookmarkManager.getAll())
                SyncDataType.HISTORY -> gson.toJson(HistoryStore.getAll())
                SyncDataType.TAB -> gson.toJson(
                    BrowserTabsStore.list().map { mapOf("id" to it.id, "url" to it.url, "title" to it.title) }
                )
            }
            val body = gson.toJson(mapOf(
                "deviceId" to deviceId,
                "dataType" to dataType.apiName,
                "payload" to payload,
            ))
            val req = Request.Builder()
                .url(base() + "/api/browser/sync/push")
                .header("Authorization", "Bearer ${AccountStore.token}")
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    XLog.w(TAG, "push ${dataType.apiName} failed: HTTP ${resp.code}")
                    return@withContext false
                }
                KVUtils.putString(KEY_LAST_PUSH_TS_PREFIX + dataType.apiName, System.currentTimeMillis().toString())
                true
            }
        } catch (e: Exception) {
            XLog.w(TAG, "push ${dataType.apiName} error", e)
            false
        }
    }

    /** 拉取其他设备的更新(排除自己的 device_id)。 */
    suspend fun pull(context: Context): List<SyncPayload> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext emptyList()
        try {
            val since = KVUtils.getString(KEY_LAST_PULL_TS, "0").toLongOrNull() ?: 0L
            val req = Request.Builder()
                .url("${base()}/api/browser/sync/pull?since=$since&deviceId=$deviceId")
                .header("Authorization", "Bearer ${AccountStore.token}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    XLog.w(TAG, "pull failed: HTTP ${resp.code}")
                    return@withContext emptyList()
                }
                val text = resp.body?.string().orEmpty()
                val obj = gson.fromJson(text, JsonObject::class.java) ?: return@withContext emptyList()
                val items = obj.getAsJsonArray("items") ?: return@withContext emptyList()
                items.mapNotNull { el ->
                    val o = el.asJsonObject
                    val dt = o.get("dataType")?.asString ?: return@mapNotNull null
                    val pl = o.get("payload")?.asString ?: return@mapNotNull null
                    val did = o.get("deviceId")?.asString ?: return@mapNotNull null
                    val ua = o.get("updatedAt")?.asLong ?: 0L
                    SyncPayload(dt, pl, did, ua)
                }.also {
                    if (it.isNotEmpty()) KVUtils.putString(KEY_LAST_PULL_TS, System.currentTimeMillis().toString())
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "pull error", e)
            emptyList()
        }
    }

    /** 一次全量同步:先推送三类本地数据,再拉取并合入其他设备的更新。 */
    suspend fun syncAll(context: Context): SyncResult = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext SyncResult(notLoggedIn = true)
        // 推送本机数据
        push(context, SyncDataType.BOOKMARK)
        push(context, SyncDataType.HISTORY)
        push(context, SyncDataType.TAB)
        // 拉取并合并其他设备的数据
        val remote = pull(context)
        var bookmarks = 0
        var history = 0
        var tabs = 0
        for (item in remote) {
            when (item.dataType) {
                SyncDataType.BOOKMARK.apiName -> { BookmarkManager.mergeFromRemote(item.payload); bookmarks++ }
                SyncDataType.HISTORY.apiName -> { HistoryStore.mergeFromRemote(item.payload); history++ }
                SyncDataType.TAB.apiName -> { BrowserTabsStore.mergeFromRemote(item.payload); tabs++ }
            }
        }
        KVUtils.putString(KEY_LAST_PULL_TS, System.currentTimeMillis().toString())
        SyncResult(bookmarks = bookmarks, history = history, tabs = tabs, notLoggedIn = false)
    }

    /** 一次同步的汇总结果,用于 UI 提示。 */
    data class SyncResult(
        val bookmarks: Int = 0,
        val history: Int = 0,
        val tabs: Int = 0,
        val notLoggedIn: Boolean = false,
    )

    /** 查询各 data_type 的最后同步时间和设备列表。 */
    suspend fun getStatus(context: Context): SyncStatus = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext SyncStatus(null, null, null)
        try {
            val req = Request.Builder()
                .url("${base()}/api/browser/sync/status")
                .header("Authorization", "Bearer ${AccountStore.token}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext SyncStatus(null, null, null)
                val text = resp.body?.string().orEmpty()
                val obj = gson.fromJson(text, JsonObject::class.java)
                    ?: return@withContext SyncStatus(null, null, null)
                val types = obj.getAsJsonObject("types") ?: return@withContext SyncStatus(null, null, null)
                SyncStatus(
                    bookmark = parseTypeInfo(types, "bookmark"),
                    history = parseTypeInfo(types, "history"),
                    tab = parseTypeInfo(types, "tab"),
                )
            }
        } catch (e: Exception) {
            XLog.w(TAG, "getStatus error", e)
            SyncStatus(null, null, null)
        }
    }

    private fun parseTypeInfo(types: JsonObject, key: String): SyncTypeInfo? {
        val o = types.getAsJsonObject(key) ?: return null
        val last = o.get("lastSync")?.asLong ?: 0L
        val devs = o.getAsJsonArray("devices")?.mapNotNull { it.asString } ?: emptyList()
        return SyncTypeInfo(last, devs)
    }
}
