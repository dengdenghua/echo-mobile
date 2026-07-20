package com.apk.claw.android.octopus_mobile

import android.util.Log
import com.apk.claw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM API Key 池 —— 多 key 轮询 + 失败自动切换 + 配额统计。
 *
 * **背景**:
 *  - 单 key 容易遇到限流(429)或临时配额耗尽,导致 Agent 整轮中断
 *  - 多 key 池可在当前 key 失败时自动切到下一个,显著提升可用性
 *  - 同一个 provider(同 baseUrl)下多个 key 通常可互换,无需改 model
 *
 * **架构**:
 *  - 持久化:多 key 以 JSON 数组存 MMKV(加密),含每个 key 的统计(success/fail/lastUsed)
 *  - 主 key(在 LlmConfig 里配的)始终优先,池中其他 key 仅作 fallback
 *  - 调用方:[acquireKey] 取当前 key,[reportFailure] 上报失败,[reportSuccess] 上报成功
 *  - 切换策略:遇到 401/403/429/5xx 自动标记当前 key 为"冷却"并切到下一个可用 key
 *
 * **线程安全**:
 *  - 所有状态读写走 synchronized(locks per key)
 *  - 持久化在 reportSuccess/reportFailure 时异步落盘(MMKV 单线程写入安全)
 *
 * **不支持的(刻意保守)**:
 *  - 不做"按 key 配额预算"(各 provider 配额规则不同,本地难精确跟踪)
 *  - 不做"按延迟选 key"(引入额外探测开销,主→fallback 顺序足够)
 *  - 不与 LlmRouting 平台中转冲突:平台中转用 AccountStore.token,不走本池
 */
object ApiKeyPool {

    private const val TAG = "ApiKeyPool"
    private const val KEY_POOL_JSON = "llm_api_key_pool_v1"
    private const val KEY_POOL_ENABLED = "llm_api_key_pool_enabled"

    /** key 冷却时间(ms):被标记失败后,在此期间不会被 acquireKey 选中。 */
    private const val COOLDOWN_MS = 5 * 60 * 1000L

    /** 单 key 连续失败次数上限:超过则永久禁用(直到用户重置)。 */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    data class KeyEntry(
        val key: String,             // 完整 API Key(脱敏展示时用 [masked])
        var successCount: Int = 0,
        var failCount: Int = 0,
        var consecutiveFailures: Int = 0,
        var lastUsedMs: Long = 0,
        var lastFailMs: Long = 0,
        var disabled: Boolean = false,  // 连续失败超阈值后永久禁用
    )

    @Volatile
    private var pool: MutableList<KeyEntry> = mutableListOf()

    @Volatile
    private var enabled: Boolean = false

    /** 当前选中的 key 索引(原子操作保证线程安全轮询)。 */
    private val currentIndex = AtomicInteger(0)

    @Volatile
    private var initialized = false

    /**
     * 初始化:从 MMKV 加载池配置。在 ClawApplication.onCreate 调用一次即可。
     * 重复调用幂等。
     */
    @Synchronized
    fun init() {
        if (initialized) return
        try {
            enabled = KVUtils.getBoolean(KEY_POOL_ENABLED, false)
            val json = KVUtils.getString(KEY_POOL_JSON, "")
            pool = if (json.isNotEmpty()) {
                parseJson(json).toMutableList()
            } else {
                mutableListOf()
            }
            initialized = true
            Log.i(TAG, "ApiKeyPool initialized: enabled=$enabled, size=${pool.size}")
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            pool = mutableListOf()
            initialized = true
        }
    }

    /** 是否启用 key 池(用户在设置页可开关)。 */
    fun isEnabled(): Boolean {
        if (!initialized) init()
        return enabled && pool.size > 1  // 池里只有 1 个 key 时等同单 key,返回 false 让上层走原路径
    }

    /** 启用/禁用 key 池。 */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        KVUtils.putBoolean(KEY_POOL_ENABLED, value)
        Log.i(TAG, "ApiKeyPool ${if (value) "enabled" else "disabled"}")
    }

    /**
     * 获取池中所有 key(脱敏后的副本,用于 UI 展示)。
     * @return 列表 of (id, maskedKey, stats)
     */
    fun listKeys(): List<Pair<String, KeyEntry>> {
        if (!initialized) init()
        synchronized(pool) {
            return pool.mapIndexed { idx, entry ->
                "$idx" to entry.copy(key = maskKey(entry.key))
            }
        }
    }

    /**
     * 添加一个 key 到池中。重复 key 会被忽略。
     *
     * 特殊处理:如果池当前为空,先把 KVUtils.getLlmApiKey() 作为主 Key 加到索引 0,
     * 再把新 key 加到索引 1+。这保证了"池索引 0 = 主 Key"的不变式,
     * 使得 isEnabled() (要求 pool.size > 1) 在用户加了第一个 fallback 后即生效。
     *
     * @return true 添加成功,false 已存在或空
     */
    @Synchronized
    fun addKey(apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        init()
        synchronized(pool) {
            if (pool.any { it.key == apiKey }) return false
            // 池为空时先把当前主 Key(KVUtils)加为索引 0,使池结构一致:
            // 索引 0 = 主 Key,索引 1+ = fallback。避免 acquireKey 跳过主 Key 直接用 fallback。
            if (pool.isEmpty()) {
                val primary = KVUtils.getLlmApiKey()
                if (primary.isNotEmpty() && primary != apiKey) {
                    pool.add(KeyEntry(key = primary))
                }
            }
            pool.add(KeyEntry(key = apiKey))
            persist()
            Log.i(TAG, "Key added, pool size=${pool.size}")
            return true
        }
    }

    /**
     * 从池中移除指定索引的 key。
     * 不允许移除主 key(索引 0,与 LlmConfig 里的主 key 对应)。
     */
    @Synchronized
    fun removeKey(index: Int): Boolean {
        if (index <= 0) return false  // 主 key 不可删
        init()
        synchronized(pool) {
            if (index >= pool.size) return false
            pool.removeAt(index)
            if (currentIndex.get() >= pool.size) {
                currentIndex.set(0)
            }
            persist()
            Log.i(TAG, "Key removed at $index, pool size=${pool.size}")
            return true
        }
    }

    /** 清空池(保留主 key 索引 0,即 LlmConfig 里的主 key)。 */
    @Synchronized
    fun clearFallbacks() {
        init()
        synchronized(pool) {
            if (pool.size > 1) {
                pool.subList(1, pool.size).clear()
                persist()
                Log.i(TAG, "Fallbacks cleared, pool size=${pool.size}")
            }
        }
    }

    /**
     * 重置某 key 的统计与冷却状态(用户在 UI 点击"重新启用")。
     */
    @Synchronized
    fun resetKey(index: Int): Boolean {
        init()
        synchronized(pool) {
            if (index < 0 || index >= pool.size) return false
            val e = pool[index]
            e.consecutiveFailures = 0
            e.disabled = false
            e.lastFailMs = 0
            persist()
            return true
        }
    }

    /**
     * 同步主 Key 到池索引 0。当用户在 LlmConfig 改了主 Key 时调用。
     *
     * - 池为空:no-op(用户尚未添加任何 fallback,池仍禁用,主 Key 由 KVUtils 单独存)
     * - 池非空:把索引 0 替换为新主 Key(保留 successCount/failCount,重置失败状态)
     *   —— 这避免"用户改了主 Key 但池里索引 0 还是旧主 Key"导致 acquireKey 返回旧 Key 的不一致
     *
     * @param newPrimaryKey 新的主 API Key(来自 KVUtils.getLlmApiKey())
     */
    @Synchronized
    fun syncPrimary(newPrimaryKey: String) {
        init()
        synchronized(pool) {
            if (pool.isEmpty()) return
            if (pool[0].key == newPrimaryKey) return
            val old = pool[0]
            pool[0] = KeyEntry(
                key = newPrimaryKey,
                successCount = old.successCount,
                failCount = old.failCount,
                consecutiveFailures = 0,  // 新主 Key 视为新开始
                lastUsedMs = old.lastUsedMs,
                lastFailMs = 0,
                disabled = false,
            )
            currentIndex.set(0)  // 主 Key 更换后,从索引 0 重新开始
            persist()
            Log.i(TAG, "Primary key synced in pool")
        }
    }

    /**
     * 获取当前可用 key(优先主 key,fallback 到池中其他未冷却 key)。
     *
     * 调用方应在每次 LLM 请求前调用此方法,因为可能在上次失败后已切换。
     *
     * @return 当前选中的 API Key,池为空时返回主 key(KVUtils.getLlmApiKey)
     */
    fun acquireKey(): String {
        if (!initialized) init()
        if (pool.isEmpty()) return KVUtils.getLlmApiKey()

        synchronized(pool) {
            val now = System.currentTimeMillis()
            // 从当前索引开始找可用 key
            for (i in pool.indices) {
                val idx = (currentIndex.get() + i) % pool.size
                val entry = pool[idx]
                if (isAvailable(entry, now)) {
                    currentIndex.set(idx)
                    entry.lastUsedMs = now
                    return entry.key
                }
            }
            // 所有 key 都不可用:返回主 key 让请求自然失败,触发用户感知
            Log.w(TAG, "All keys unavailable, falling back to primary")
            return pool[0].key
        }
    }

    /** 上报 key 调用成功。 */
    fun reportSuccess(apiKey: String) {
        if (!initialized) init()
        synchronized(pool) {
            val entry = pool.firstOrNull { it.key == apiKey } ?: return
            entry.successCount++
            entry.consecutiveFailures = 0
            // 异步持久化(轻量,直接同步写 MMKV)
            persist()
        }
    }

    /**
     * 上报 key 调用失败。
     *
     * 根据 HTTP 状态码决定处理方式:
     *  - 401/403:认证失败,标记禁用(可能 key 已失效)
     *  - 429:限流,标记冷却 5 分钟
     *  - 5xx:服务端错误,标记冷却 5 分钟
     *  - 其他:增加失败计数但不冷却(可能是网络抖动)
     *
     * @param apiKey 失败的 key
     * @param httpStatus HTTP 状态码,null 表示非 HTTP 错误(网络异常等)
     */
    fun reportFailure(apiKey: String, httpStatus: Int? = null) {
        if (!initialized) init()
        synchronized(pool) {
            val entry = pool.firstOrNull { it.key == apiKey } ?: return
            entry.failCount++
            entry.consecutiveFailures++
            entry.lastFailMs = System.currentTimeMillis()

            when (httpStatus) {
                401, 403 -> {
                    entry.disabled = true
                    Log.w(TAG, "Key $${maskKey(apiKey)} disabled (auth failure ${httpStatus})")
                }
                429, in 500..599 -> {
                    // 冷却由 isAvailable 检查 lastFailMs + COOLDOWN_MS 自动处理
                    Log.w(TAG, "Key ${maskKey(apiKey)} cooldown (status=$httpStatus)")
                }
            }

            if (entry.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                entry.disabled = true
                Log.w(TAG, "Key ${maskKey(apiKey)} disabled (consecutive failures=${entry.consecutiveFailures})")
            }

            // 切到下一个可用 key
            advanceToNextAvailable()
            persist()
        }
    }

    /** 当前池大小(含主 key)。 */
    fun size(): Int {
        if (!initialized) init()
        return pool.size
    }

    /**
     * 测试专用:重置单例状态(pool 清空、enabled=false、currentIndex=0、initialized=false)。
     *
     * 单测间隔离用:ApiKeyPool 是 object 单例,状态会跨测试泄漏。@Before 调本方法 + KVUtils.resetForTest()。
     * 生产环境不应调本方法 —— 会清掉用户的备用 Key 配置。
     */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun resetForTest() {
        pool = mutableListOf()
        enabled = false
        currentIndex.set(0)
        initialized = false
    }

    // ── 内部工具 ──

    private fun isAvailable(entry: KeyEntry, now: Long): Boolean {
        if (entry.disabled) return false
        // 冷却期内不可用
        if (entry.lastFailMs > 0 && now - entry.lastFailMs < COOLDOWN_MS) {
            return false
        }
        return true
    }

    private fun advanceToNextAvailable() {
        val now = System.currentTimeMillis()
        for (i in 1..pool.size) {
            val idx = (currentIndex.get() + i) % pool.size
            if (isAvailable(pool[idx], now)) {
                currentIndex.set(idx)
                Log.i(TAG, "Switched to key index $idx")
                return
            }
        }
    }

    private fun maskKey(key: String): String {
        return if (key.length <= 8) "••••"
        else key.take(5) + "••••" + key.takeLast(4)
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (entry in pool) {
                arr.put(JSONObject().apply {
                    put("k", entry.key)
                    put("s", entry.successCount)
                    put("f", entry.failCount)
                    put("cf", entry.consecutiveFailures)
                    put("lu", entry.lastUsedMs)
                    put("lf", entry.lastFailMs)
                    put("d", entry.disabled)
                })
            }
            KVUtils.putString(KEY_POOL_JSON, arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
        }
    }

    private fun parseJson(json: String): List<KeyEntry> {
        val arr = JSONArray(json)
        val list = ArrayList<KeyEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(KeyEntry(
                key = obj.optString("k"),
                successCount = obj.optInt("s"),
                failCount = obj.optInt("f"),
                consecutiveFailures = obj.optInt("cf"),
                lastUsedMs = obj.optLong("lu"),
                lastFailMs = obj.optLong("lf"),
                disabled = obj.optBoolean("d"),
            ))
        }
        return list
    }
}
