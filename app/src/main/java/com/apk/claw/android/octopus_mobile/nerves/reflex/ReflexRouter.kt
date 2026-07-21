package com.apk.claw.android.octopus_mobile.nerves.reflex

import android.util.Log

/**
 * 反射路由器 —— 从母体 runtime/core/nerves/reflex/reflex_router.py 移植.
 *
 * 关键词 → 自动动作，跳过 LLM 推理：
 *  - "打开微信" → 直接 open_app(weixin)
 *  - "截屏" → 直接 take_screenshot
 *  - "返回" → 直接 system_key(back)
 *  - "帮我搜一下..." → 走 LLM（需要理解意图）
 *
 * 三种匹配器：
 *  1. RegexMatcher：正则匹配
 *  2. DeterministicMatcher：意图类型 + 关键词
 *  3. CacheMatcher：缓存最近结果（相同输入直接返回）
 *
 * 用法：
 * ```kotlin
 * val router = ReflexRouter()
 * router.addRule(RegexMatcher("open_wechat", "打开微信|打开wechat", mapOf("tool" to "open_app", "package" to "com.tencent.mm")))
 *
 * val result = router.tryMatch("帮我打开微信")
 * // result = ReflexMatch(rule_id="open_wechat", response={tool=open_app, package=com.tencent.mm})
 * ```
 */
class ReflexRouter(
    private val defaultThreshold: Double = 0.85,
) {
    companion object {
        private const val TAG = "ReflexRouter"

        /** 这些意图类型强制走 LLM，不走路由 */
        val FORCE_DELIBERATIVE = setOf("plan", "refactor", "debug", "design")

        /** 预置的常用反射规则 */
        fun defaultRules(): List<Reflex> = listOf(
            // ── App 打开 ──
            RegexMatcher("open_wechat", "打开微信|打开wechat|open wechat",
                response = mapOf("tool" to "open_app", "package" to "com.tencent.mm")),
            RegexMatcher("open_alipay", "打开支付宝|打开alipay",
                response = mapOf("tool" to "open_app", "package" to "com.eg.android.AlipayGphone")),
            RegexMatcher("open_taobao", "打开淘宝|打开taobao",
                response = mapOf("tool" to "open_app", "package" to "com.taobao.taobao")),
            RegexMatcher("open_jd", "打开京东|打开jd",
                response = mapOf("tool" to "open_app", "package" to "com.jingdong.app.mall")),
            RegexMatcher("open_douyin", "打开抖音|打开douyin",
                response = mapOf("tool" to "open_app", "package" to "com.ss.android.ugc.aweme")),
            RegexMatcher("open_bilibili", "打开B站|打开bilibili|打开哔哩哔哩",
                response = mapOf("tool" to "open_app", "package" to "tv.danmaku.bili")),

            // ── 系统操作 ──
            RegexMatcher("go_back", "返回|回去|go back|返回上一页",
                response = mapOf("tool" to "system_key", "key" to "back")),
            RegexMatcher("go_home", "回到桌面|回到主页|go home|回主屏",
                response = mapOf("tool" to "system_key", "key" to "home")),
            RegexMatcher("take_screenshot", "截屏|截图|screenshot|截个图",
                response = mapOf("tool" to "take_screenshot")),
            RegexMatcher("volume_up", "音量加|大声点|volume up",
                response = mapOf("tool" to "system_key", "key" to "volume_up")),
            RegexMatcher("volume_down", "音量减|小声点|volume down",
                response = mapOf("tool" to "system_key", "key" to "volume_down")),

            // ── 浏览器 ──
            RegexMatcher("open_url", "打开(网址|链接|网站|网页)?\\s*(https?://\\S+)",
                handler = { match, _ ->
                    val url = match.groupValues[1].let {
                        if (it.startsWith("http")) it else match.groupValues[2]
                    }
                    mapOf("tool" to "browser_navigate", "url" to (url ?: ""))
                }),

            // ── 扩展 ──
            RegexMatcher("install_ublock", "装.*ublock|安装.*广告拦截|去广告",
                response = mapOf("tool" to "browser_install_extension", "source" to "cws:cjpalhdlnbpafiamejdnhcphjbkeiagm")),
        )
    }

    // ── 数据类 ────────────────────────────────────────

    data class ReflexMatch(
        val ruleId: String,
        val confidence: Double = 1.0,
        val response: Any?,
        val latencyMs: Double = 0.0,
        val kind: String = "regex",  // regex / deterministic / cache
    )

    data class ReflexMiss(
        val triedRules: List<String> = emptyList(),
        val reason: String = "",
    )

    // ── 匹配器接口 ────────────────────────────────────

    abstract class Reflex(
        val ruleId: String,
        val kind: String = "regex",
        val priority: Int = 0,
    ) {
        abstract fun tryMatch(intent: ParsedIntent): ReflexMatch?
    }

    data class ParsedIntent(
        val goal: String,
        val normalizedGoal: String = goal.lowercase().trim(),
        val intentType: String = "action",
        val flags: Map<String, Boolean> = emptyMap(),
    )

    // ── 正则匹配器 ────────────────────────────────────

    class RegexMatcher(
        ruleId: String,
        pattern: String,
        private val response: Any? = null,
        private val handler: ((MatchResult, ParsedIntent) -> Any?)? = null,
        priority: Int = 0,
        caseInsensitive: Boolean = true,
    ) : Reflex(ruleId, "regex", priority) {

        private val regex = if (caseInsensitive) {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } else {
            Regex(pattern)
        }

        override fun tryMatch(intent: ParsedIntent): ReflexMatch? {
            val t0 = System.nanoTime()
            val match = regex.find(intent.normalizedGoal) ?: return null

            val resp = if (handler != null) {
                handler(match, intent)
            } else if (response != null) {
                response
            } else {
                mapOf("match" to match.value, "groups" to match.groupValues)
            }

            val latencyMs = (System.nanoTime() - t0) / 1_000_000.0
            return ReflexMatch(
                ruleId = ruleId,
                response = resp,
                latencyMs = latencyMs,
                kind = "regex",
            )
        }
    }

    // ── 缓存匹配器 ────────────────────────────────────

    class CacheMatcher(
        ruleId: String = "cache",
        private val ttlSeconds: Int = 3600,
        private val maxEntries: Int = 1000,
        priority: Int = 5,
    ) : Reflex(ruleId, "cache", priority) {

        private data class CacheHit(
            val key: String,
            val value: Any,
            val ts: Long = System.currentTimeMillis(),
        )

        private val store = mutableMapOf<String, CacheHit>()

        fun put(intent: ParsedIntent, response: Any) {
            val key = keyFor(intent)
            if (store.size >= maxEntries) {
                store.remove(store.keys.first())
            }
            store[key] = CacheHit(key = key, value = response)
        }

        override fun tryMatch(intent: ParsedIntent): ReflexMatch? {
            val t0 = System.nanoTime()
            val key = keyFor(intent)
            val hit = store[key] ?: return null

            if (System.currentTimeMillis() - hit.ts > ttlSeconds * 1000L) {
                store.remove(key)
                return null
            }

            val latencyMs = (System.nanoTime() - t0) / 1_000_000.0
            return ReflexMatch(
                ruleId = ruleId,
                response = hit.value,
                latencyMs = latencyMs,
                kind = "cache",
                confidence = 0.95,
            )
        }

        fun size(): Int = store.size
        fun clear() = store.clear()

        companion object {
            fun keyFor(intent: ParsedIntent): String {
                val input = "${intent.intentType}|${intent.normalizedGoal}"
                return input.hashCode().toString(16)
            }
        }
    }

    // ── 路由器核心 ────────────────────────────────────

    private val reflexes = mutableListOf<Reflex>()
    private var hitCount = 0
    private var tryCount = 0
    private val hitCountByRule = mutableMapOf<String, Int>()
    private val tryCountByRule = mutableMapOf<String, Int>()
    private val lastHitAtByRule = mutableMapOf<String, Long>()

    fun addRule(reflex: Reflex) {
        reflexes.add(reflex)
        reflexes.sortByDescending { it.priority }
    }

    fun addRules(rules: List<Reflex>) {
        reflexes.addAll(rules)
        reflexes.sortByDescending { it.priority }
    }

    fun tryMatch(intent: ParsedIntent): ReflexMatch? {
        tryCount++

        // 强制走 LLM 的意图类型
        if (intent.intentType in FORCE_DELIBERATIVE) return null
        if (intent.flags["deep"] == true) return null

        val tried = mutableListOf<String>()

        for (reflex in reflexes) {
            tryCountByRule[reflex.ruleId] = (tryCountByRule[reflex.ruleId] ?: 0) + 1
            tried.add(reflex.ruleId)

            val match = reflex.tryMatch(intent) ?: continue
            if (match.confidence < defaultThreshold) continue

            hitCount++
            hitCountByRule[match.ruleId] = (hitCountByRule[match.ruleId] ?: 0) + 1
            lastHitAtByRule[match.ruleId] = System.currentTimeMillis()

            Log.d(TAG, "Reflex hit: ${match.ruleId} (${match.latencyMs.toInt()}ms)")
            return match
        }

        return null  // 没命中，走 LLM
    }

    /**
     * 便捷方法：直接从用户文本匹配.
     */
    fun tryMatchText(text: String): ReflexMatch? {
        return tryMatch(ParsedIntent(goal = text))
    }

    val hitRate: Double
        get() = if (tryCount == 0) 0.0 else hitCount.toDouble() / tryCount

    fun statsByRule(): Map<String, Map<String, Any>> {
        val now = System.currentTimeMillis()
        return tryCountByRule.keys.associateWith { ruleId ->
            val tries = tryCountByRule[ruleId] ?: 0
            val hits = hitCountByRule[ruleId] ?: 0
            val lastHit = lastHitAtByRule[ruleId]
            val result = mutableMapOf<String, Any>(
                "tries" to tries,
                "hits" to hits,
                "hit_rate" to (if (tries > 0) hits.toDouble() / tries else 0.0),
            )
            if (lastHit != null) {
                result["stale_for_hours"] = (now - lastHit) / 3600_000.0
            }
            result
        }
    }

    fun listRules(): List<Map<String, Any>> {
        return reflexes.map { r ->
            mutableMapOf<String, Any>(
                "rule_id" to r.ruleId,
                "kind" to r.kind,
                "priority" to r.priority,
            )
        }
    }

    /**
     * 从高频工具调用模式中自动提炼新规则.
     *
     * 当某个 "open_app(package=X)" 模式在最近 N 次调用中出现超过阈值时，
     * 自动生成一条 ReflexRouter 规则.
     *
     * ⚠️ 已废弃（PROJECT_ANALYSIS P2 死代码清理,2026-07）：
     *     仅 `DefaultAgentService` 在 open_app 频次≥3 时调用,自动学习的规则从不持久化
     *     也未被任何热路径读取生效,属"学习闭环未完成"的死代码。保留以避免破坏编译。
     */
    @Deprecated(
        "learnFromPattern 学习的规则不持久化、不被热路径读取,学习闭环未完成。详见 PROJECT_ANALYSIS P2。",
        level = DeprecationLevel.WARNING,
    )
    fun learnFromPattern(toolName: String, args: Map<String, Any>, frequency: Int) {
        // 只学习 open_app 类的高频模式
        if (toolName != "open_app") return
        val packageName = args["package"] as? String ?: return

        // 生成规则 ID，避免重复
        val ruleId = "learned_open_${packageName.substringAfterLast(".")}"
        if (reflexes.any { it.ruleId == ruleId }) return

        // 从包名推断应用名（简单方式：取最后一段）
        val appName = packageName.substringAfterLast(".")
        val pattern = "打开$appName|open $appName"

        addRule(RegexMatcher(ruleId, pattern,
            response = mapOf("tool" to "open_app", "package" to packageName)))
        Log.i(TAG, "Auto-learned reflex: $ruleId for $packageName (frequency=$frequency)")
    }
}
