package com.apk.claw.android.octopus_mobile.evolution

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回合打分器 —— 从母体 runtime/memory/turn_scoring.py 移植.
 *
 * L1 免费层（零 LLM 调用）：
 *  - 每次工具调用后自动打分
 *  - 统计成功率 / 平均轮数 / 趋势
 *  - 持久化到本地 JSON 文件
 *
 * 用法：
 * ```kotlin
 * val scorer = TurnScorer(context.filesDir)
 * scorer.record("tap", success = true, rounds = 3, reason = "completed")
 * val fitness = scorer.computeFitness()
 * ```
 */
open class TurnScorer(
    private val dataDir: File,
    private val windowSize: Int = 20,
) {
    companion object {
        private const val TAG = "TurnScorer"
        private const val SCORES_FILE = "turn_scores.jsonl"
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    private val scoresFile = File(dataDir, SCORES_FILE)

    // ── 数据类 ────────────────────────────────────────

    data class TurnScore(
        val ts: String,
        val toolName: String,
        val score: Double,      // 0.0 失败 / 1.0 成功 / 0.5 部分成功
        val reason: String,
        val rounds: Int,
    )

    data class FitnessReport(
        val score: Double,          // 0.0-1.0 综合分
        val trend: String,          // improving / stable / regressing
        val successRate: Double,    // 成功率
        val avgRounds: Double,      // 平均轮数
        val verdict: String,        // healthy / degraded / unhealthy / critical
        val totalSamples: Int,
        val topFailure: String?,    // 最常见失败工具
    )

    // ── 记录 ──────────────────────────────────────────

    /**
     * 记录一次工具调用结果.
     */
    open fun record(toolName: String, success: Boolean, rounds: Int = 1, reason: String = "") {
        val score = if (success) 1.0 else 0.0
        val entry = TurnScore(
            ts = synchronized(DATE_FMT) { DATE_FMT.format(Date()) },
            toolName = toolName,
            score = score,
            reason = if (toolName == "mirror_frame" && success) "Frame delivered"
                else reason.take(BoundedScoreLog.MAX_REASON_CHARS),
            rounds = rounds,
        )
        appendScore(entry)
    }

    /**
     * 记录一次部分成功.
     */
    fun recordPartial(toolName: String, rounds: Int = 1, reason: String = "partial") {
        val entry = TurnScore(
            ts = synchronized(DATE_FMT) { DATE_FMT.format(Date()) },
            toolName = toolName,
            score = 0.5,
            reason = reason.take(BoundedScoreLog.MAX_REASON_CHARS),
            rounds = rounds,
        )
        appendScore(entry)
    }

    // ── 读取 ──────────────────────────────────────────

    /**
     * 读取最近 N 条打分记录.
     */
    @Synchronized
    fun readRecentScores(limit: Int = windowSize): List<TurnScore> {
        if (!scoresFile.exists()) return emptyList()

        val recent = scoresFile.reader().use { BoundedScoreLog.read(it, limit) }

        return recent.mapNotNull { line ->
            try {
                val json = JSONObject(line)
                TurnScore(
                    ts = json.optString("ts", ""),
                    toolName = json.optString("tool_name", ""),
                    score = json.optDouble("score", 0.0),
                    reason = json.optString("reason", ""),
                    rounds = json.optInt("rounds", 1),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── 适应度计算（L1 免费层）─────────────────────────

    /**
     * 计算当前适应度报告.
     *
     * 纯启发式，零 LLM 调用：
     *  - 成功率
     *  - 趋势（前半 vs 后半对比）
     *  - 判定（healthy / degraded / unhealthy / critical）
     *  - 最常见失败工具
     */
    fun computeFitness(): FitnessReport {
        val scores = readRecentScores(windowSize)
        if (scores.isEmpty()) {
            return FitnessReport(
                score = 0.5, trend = "stable", successRate = 0.0,
                avgRounds = 0.0, verdict = "degraded", totalSamples = 0,
                topFailure = null,
            )
        }

        val successRate = scores.count { it.score >= 1.0 }.toDouble() / scores.size
        val avgRounds = scores.map { it.rounds.toDouble() }.average()

        // 趋势：前半 vs 后半
        val trend = if (scores.size >= 4) {
            val firstHalf = scores.subList(0, scores.size / 2)
            val secondHalf = scores.subList(scores.size / 2, scores.size)
            val avgFirst = firstHalf.map { it.score }.average()
            val avgSecond = secondHalf.map { it.score }.average()
            val delta = avgSecond - avgFirst
            when {
                delta > 0.1 -> "improving"
                delta < -0.1 -> "regressing"
                else -> "stable"
            }
        } else {
            "stable"
        }

        val rawScore = scores.map { it.score }.average()

        // 最常见失败工具
        val topFailure = scores
            .filter { it.score < 1.0 }
            .groupingBy { it.toolName }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val verdict = when {
            rawScore >= 0.8 -> "healthy"
            rawScore >= 0.5 -> "degraded"
            rawScore >= 0.3 -> "unhealthy"
            else -> "critical"
        }

        return FitnessReport(
            score = (rawScore * 1000).toInt() / 1000.0,  // 3 位小数
            trend = trend,
            successRate = (successRate * 1000).toInt() / 1000.0,
            avgRounds = (avgRounds * 10).toInt() / 10.0,
            verdict = verdict,
            totalSamples = scores.size,
            topFailure = topFailure,
        )
    }

    /**
     * 获取工具级统计.
     */
    fun toolStats(): Map<String, ToolStat> {
        val scores = readRecentScores(windowSize * 5)  // 更大窗口
        return scores
            .groupBy { it.toolName }
            .mapValues { entries ->
                val total = entries.value.size
                val successes = entries.value.count { it.score >= 1.0 }
                ToolStat(
                    toolName = entries.key,
                    totalCalls = total,
                    successCount = successes,
                    failureCount = total - successes,
                    successRate = if (total > 0) successes.toDouble() / total else 0.0,
                )
            }
    }

    data class ToolStat(
        val toolName: String,
        val totalCalls: Int,
        val successCount: Int,
        val failureCount: Int,
        val successRate: Double,
    )

    // ── 内部 ──────────────────────────────────────────

    @Synchronized
    private fun appendScore(entry: TurnScore) {
        try {
            dataDir.mkdirs()
            val json = JSONObject().apply {
                put("ts", entry.ts)
                put("tool_name", entry.toolName)
                put("score", entry.score)
                put("reason", entry.reason)
                put("rounds", entry.rounds)
            }
            scoresFile.appendText(json.toString() + "\n")

            // 自动裁剪：保留最近 1000 条
            trimScores(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append score", e)
        }
    }

    private fun trimScores(maxLines: Int) {
        try {
            if (!scoresFile.exists()) return
            val trimmed = scoresFile.reader().use { BoundedScoreLog.read(it, maxLines) }
            scoresFile.bufferedWriter().use { output ->
                trimmed.forEach { output.appendLine(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trim scores", e)
        }
    }
}
