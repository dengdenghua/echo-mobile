package com.apk.claw.android.code

import kotlin.math.ln

/**
 * BM25 评分器——Okapi BM25 标准公式。
 *
 * 公式(与母本 `repo_context.py:_bm25` 完全一致):
 * ```
 * score = Σ_t  idf(t) · (f(t,d) · (k1 + 1)) / (f(t,d) + k1 · (1 - b + b · |d| / avgdl))
 * idf(t) = ln( (N - df(t) + 0.5) / (df(t) + 0.5) + 1 )
 * ```
 * - N        = 总文档数(chunks)
 * - df(t)    = 含 t 的文档数
 * - f(t,d)   = t 在文档 d 中的词频
 * - |d|      = 文档 d 的长度(token 总数)
 * - avgdl    = 平均文档长度
 * - k1=1.5, b=0.75(标准默认值)
 *
 * idf 里的 `+1` 保证非负(即便 df 接近 N)——母本同款做法。
 *
 * 这个类无状态、线程安全;调用方负责准备 [chunkTokens] / [docFreq] / [avgDocLen] 等统计量。
 */
class Bm25Scorer(
    val k1: Double = 1.5,
    val b: Double = 0.75,
) {
    /**
     * 计算单个文档对查询的 BM25 得分。
     *
     * @param queryTokens 查询 token 列表(可含重复;重复会被加权——但通常应去重后传入)
     * @param chunkTokens 该文档的 token→count 映射(只查 query 命中的 token 即可,
     *                    其他 token 不参与得分)
     * @param avgDocLen   平均文档长度(token 总数的均值)
     * @param docFreq     token→含该 token 的文档数(只查 query 命中的 token 即可)
     * @param totalDocs   总文档数 N
     * @return BM25 得分;>0 表示有命中
     */
    fun score(
        queryTokens: List<String>,
        chunkTokens: Map<String, Int>,
        avgDocLen: Double,
        docFreq: Map<String, Int>,
        totalDocs: Int,
    ): Double {
        if (avgDocLen <= 0.0 || totalDocs <= 0 || queryTokens.isEmpty()) return 0.0
        // 文档长度 = 该 chunk 所有 token 的总频次
        val docLen = chunkTokens.values.sum().toDouble()
        if (docLen <= 0.0) return 0.0

        var total = 0.0
        for (term in queryTokens) {
            val f = chunkTokens[term] ?: 0
            if (f <= 0) continue
            val df = docFreq[term] ?: 0
            // idf = ln( (N - df + 0.5) / (df + 0.5) + 1 )
            val idf = ln((totalDocs - df + 0.5) / (df + 0.5) + 1.0)
            // 分母:f + k1 · (1 - b + b · |d| / avgdl)
            val denom = f + k1 * (1.0 - b + b * docLen / avgDocLen)
            total += idf * (f * (k1 + 1.0)) / denom
        }
        return total
    }
}
