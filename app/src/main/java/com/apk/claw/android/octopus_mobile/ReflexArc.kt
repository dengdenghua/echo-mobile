@file:Suppress("PackageNaming", "ReturnCount", "MagicNumber")   // 沿用既有 octopus_mobile 包;快路径多出口/内联阈值

package com.apk.claw.android.octopus_mobile

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 反射快路径（Reflex Arc）——从 octopus-os reflex.md 协议移植。
 *
 * 核心思想：不经大脑（Cerebrum/LLM）的低成本响应，优先走
 *   ① **正则规则**（精确匹配常见简单输入）→ 直出响应
 *   ② **生成缓存**（相似需求已做过）→ 复用最近生成结果
 *   ③ **置信度阈值**：规则置信度 < 0.85 时不命中，继续走LLM
 *
 * 这避免了用户说"你好""谢谢""你是谁""再做一次"时还烧 token 走完整生成流程。
 *
 * 注意：Reflex 只在 Agent 层对输入做快速判定，generate_app 工具内部不走 Reflex。
 */
object ReflexArc {

    private const val TAG = "ReflexArc"
    private const val CACHE_MAX_SIZE = 20
    private const val SIMILARITY_THRESHOLD = 0.85

    data class ReflexMatch(
        val response: String,
        val confidence: Double,
        val source: String,  // "rule" / "cache"
        val cachedHtml: String? = null,
        val cachedAppId: String? = null,
    )

    data class CacheEntry(
        val normalizedQuery: String,
        val appName: String,
        val html: String,
        val appId: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val cache = ConcurrentLinkedDeque<CacheEntry>()

    /**
     * 正则规则表：(pattern, response, confidence)。
     * 只匹配简单对话/系统指令，不涉及生成代码的需求。
     */
    private val rules = listOf(
        Triple(
            Regex("^(你好|hi|hello|嗨|哈喽|hey|在吗|在不在)\\s*[!！。.?？]*$", RegexOption.IGNORE_CASE),
            "你好，我是 Echo，你的 AI 手机助手。可以帮你操作手机、生成小程序、回答问题。想做什么，直接说。",
            0.95
        ),
        Triple(
            Regex("^(谢谢|多谢|thanks|thank you|thx|感谢|3Q|三克油)\\s*[!！。.?？]*$", RegexOption.IGNORE_CASE),
            "不客气。还有要做的，随时说。",
            0.95
        ),
        Triple(
            Regex("^(你是谁|你是什么|who are you|介绍一下你自己|自我介绍)\\s*[!！。.?？]*$", RegexOption.IGNORE_CASE),
            "我是 Echo，一个 AI 手机助手。我可以：\n" +
                    "• 用一句话帮你生成小程序（比如「做个记账app」「做个计算器」）\n" +
                    "• 通过自然语言操控手机（打开应用、发送消息等）\n" +
                    "• 回答问题、搜索信息\n" +
                    "试试说「帮我做个待办清单」。",
            0.95
        ),
        Triple(
            Regex("^(你能做什么|你会什么|能帮我做什么|有什么功能|功能介绍|帮助|help)\\s*[!！。.?？]*$", RegexOption.IGNORE_CASE),
            "我可以帮你做这些事：\n" +
                    "• **生成小程序**：说「做个XX应用」，我直接生成一个能用的 H5 小程序\n" +
                    "• **操作手机**：打开应用、发消息、查日程（需要开启无障碍服务）\n" +
                    "• **查信息**：搜索、翻译、计算\n" +
                    "• **定时任务**：按时间自动执行的操作\n" +
                    "直接说需求就行。",
            0.90
        ),
        Triple(
            Regex("^(好的|ok|okay|收到|明白|了解|嗯嗯|嗯|知道了|行|可以)\\s*[!！。.?？]*$", RegexOption.IGNORE_CASE),
            "好的，有需要随时说。",
            0.90
        ),
        Triple(
            Regex("^(再见|拜拜|bye|goodbye|拜|先走了)\\s*[!！。.?？]*$", RegexOption.IGNORE_CASE),
            "再见，需要时随时找我。",
            0.95
        ),
        Triple(
            Regex("^重做|再来一次|重新生成|重新做|再做一个(一样的|相同的)?$", RegexOption.IGNORE_CASE),
            "REGEN_LAST",  // 特殊标记：触发重新生成
            0.90
        ),
    )

    /**
     * 尝试反射快路径匹配。返回 null 表示未命中，需要走 LLM 正常流程。
     */
    fun tryMatch(input: String): ReflexMatch? {
        val trimmed = input.trim()

        for ((pattern, response, confidence) in rules) {
            if (pattern.matches(trimmed)) {
                if (confidence >= SIMILARITY_THRESHOLD) {
                    Log.i(TAG, "Reflex rule hit: ${pattern.pattern} -> conf=$confidence")
                    EvolutionMetrics.reflexHit()
                    return ReflexMatch(response, confidence, "rule")
                }
            }
        }

        val cached = findCache(trimmed)
        if (cached != null) {
            Log.i(TAG, "Reflex cache hit: ${cached.normalizedQuery.take(30)}")
            EvolutionMetrics.reflexHit()
            return ReflexMatch(
                response = "找到了你之前做过的「${cached.appName}」，直接给你打开～",
                confidence = 0.88,
                source = "cache",
                cachedHtml = cached.html,
                cachedAppId = cached.appId,
            )
        }

        EvolutionMetrics.reflexMiss()
        return null
    }

    /** 判断是否为"重做上一个"指令。 */
    fun isRegenerateRequest(input: String): Boolean {
        val trimmed = input.trim()
        return rules.any { (pattern, response, _) ->
            response == "REGEN_LAST" && pattern.matches(trimmed)
        }
    }

    /** 存入生成缓存。 */
    fun remember(query: String, appName: String, html: String, appId: String) {
        val normalized = normalize(query)
        cache.addFirst(CacheEntry(normalized, appName, html, appId))
        while (cache.size > CACHE_MAX_SIZE) {
            cache.removeLast()
        }
        Log.d(TAG, "Cached: $normalized -> $appName (cache size: ${cache.size})")
    }

    /** 获取最近一次缓存（用于"重做"）。 */
    fun lastEntry(): CacheEntry? = cache.firstOrNull()

    private fun findCache(query: String): CacheEntry? {
        val normalized = normalize(query)
        return cache.firstOrNull { similarity(normalized, it.normalizedQuery) >= SIMILARITY_THRESHOLD }
    }

    /**
     * 简单相似度计算：基于字符bigram的Jaccard相似度。
     * 轻量、快速、不需要额外依赖，足够做"之前是不是做过类似的"判断。
     */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val bigramsA = a.windowed(2, 1).toSet()
        val bigramsB = b.windowed(2, 1).toSet()
        val intersection = bigramsA.intersect(bigramsB).size
        val union = bigramsA.union(bigramsB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun normalize(s: String): String {
        return s.trim()
            .lowercase()
            .replace(Regex("[\\s,，。.!！?？~`@#\$%^&*()\\[\\]{}:;\"'<>/\\\\|+=_-]+"), "")
            .take(50)
    }
}
