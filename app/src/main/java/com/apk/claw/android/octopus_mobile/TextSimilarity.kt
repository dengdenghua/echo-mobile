@file:Suppress("PackageNaming", "ReturnCount")   // octopus_mobile 包;isNearDuplicate 守卫式提前 return

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.memory.TextRelevance

/**
 * 轻量本地文本近似判定 —— 判断两段文本是否「意思差不多」,用于知识去重。
 *
 * 随着用户教规矩([InteractionLedger])、Agent 记事([com.apk.claw.android.octopus_mobile.memory.MemoryStore])、
 * 以及多源导入(剪贴板/文件/局域网,见 [KnowledgeLocal]),会攒出近似重复:空白/标点变体、
 * 加了填充词的(「订机票」vs「帮我订机票」)、高度改写的。精确去重(contains/hash)抓不到,
 * 一多就把注入 prompt 撑肥、还添噪音。这里用词项集合的 Jaccard + 包含度做模糊判定。
 *
 * 复用 [TextRelevance.keywords] 分词(ASCII 词 + 中文 2-gram + 停用词),纯本地、零网络、可 JVM 测。
 * **判定刻意保守**:宁可漏抓也别误合并——如「打开淘宝先关弹窗」和「打开京东先关弹窗」共享大量结构词
 * 但语义不同,必须保留两条。所以阈值调高,只抓明显近似(填充词/整体高度重合),不做激进语义合并。
 */
object TextSimilarity {

    /** 词项交集/并集 ≥ 此值 → 近似(整体高度重合,如换个说法)。 */
    private const val JACCARD_THRESHOLD = 0.5

    /** 短的一方词项 ⊆ 长的一方的比例 ≥ 此值 → 近似(如加了填充词:「帮我订机票」含「订机票」)。 */
    private const val CONTAINMENT_THRESHOLD = 0.8

    /** 两段文本是否近似重复。 */
    fun isNearDuplicate(a: String, b: String): Boolean {
        val ta = a.trim()
        val tb = b.trim()
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta.equals(tb, ignoreCase = true)) return true

        val ka = TextRelevance.keywords(ta)
        val kb = TextRelevance.keywords(tb)
        // 抽不出关键词(太短/纯停用词)→ 退回精确比较(上面已比过,这里即 false)。
        if (ka.isEmpty() || kb.isEmpty()) return false

        val intersection = ka.count { it in kb }
        if (intersection == 0) return false
        val union = (ka + kb).size
        val jaccard = intersection.toDouble() / union
        val containment = intersection.toDouble() / minOf(ka.size, kb.size)
        return jaccard >= JACCARD_THRESHOLD || containment >= CONTAINMENT_THRESHOLD
    }
}
