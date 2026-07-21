package com.apk.claw.android.code

/**
 * 检索结果——一个命中的代码片段。
 *
 * @property filePath   相对项目根的路径(posix 风格)
 * @property startLine  起始行号(1-indexed)
 * @property endLine    结束行号(1-indexed, inclusive)
 * @property content    片段原文
 * @property score      融合后的最终得分,用于排序
 * @property bm25Score  BM25 原始得分
 * @property denseScore dense embedding 余弦相似度;null 表示 dense 不可用(纯 BM25)
 */
data class SearchResult(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val score: Double,
    val bm25Score: Double,
    val denseScore: Double?,
)

/**
 * 索引统计——一次 [CodeIndex.indexDirectory] 调用的产出汇报。
 *
 * @property totalFiles   实际(重新)索引的文件数
 * @property totalChunks  切分出的代码片段数
 * @property skippedFiles 跳过的文件数(过大 / 读失败 / 未知扩展名)
 * @property durationMs   耗时(毫秒)
 */
data class IndexStats(
    val totalFiles: Int,
    val totalChunks: Int,
    val skippedFiles: Int,
    val durationMs: Long,
)
