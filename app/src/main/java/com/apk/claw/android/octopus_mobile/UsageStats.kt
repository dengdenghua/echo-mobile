@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import java.util.concurrent.atomic.AtomicLong

/**
 * 用量统计(Usage Stats)—— 累计任务数 + 累计 LLM token,让走积分计费的用户对成本心里有数。
 *
 * 与 [EvolutionMetrics](自进化效果)/ AgentMetrics(主循环计数)互补:这里只管「花了多少」。
 * 轻量:2 个内存 AtomicLong,每次任务完成即持久化到 KVUtils(long)。可纯 JVM 测。
 */
object UsageStats {

    private const val KEY_TASKS = "USAGE_TASKS"
    private const val KEY_TOKENS = "USAGE_TOKENS"

    private val tasks = AtomicLong(0)
    private val tokens = AtomicLong(0)

    /** 一个任务完成:任务数 +1,累计 token 加上本次(负数按 0 处理,防脏数据)。 */
    fun recordTask(tokenCount: Int) {
        tasks.incrementAndGet()
        tokens.addAndGet(tokenCount.toLong().coerceAtLeast(0))
        persist()
    }

    fun tasks(): Long = tasks.get()

    fun tokens(): Long = tokens.get()

    /** 重置累计(信任中心「重置统计」连带调用)。 */
    fun reset() {
        tasks.set(0)
        tokens.set(0)
        persist()
    }

    /** 从 KVUtils 恢复(ClawApplication 启动时调用)。 */
    fun load() {
        tasks.set(KVUtils.getLong(KEY_TASKS, 0))
        tokens.set(KVUtils.getLong(KEY_TOKENS, 0))
    }

    private fun persist() {
        KVUtils.putLong(KEY_TASKS, tasks.get())
        KVUtils.putLong(KEY_TOKENS, tokens.get())
    }
}
