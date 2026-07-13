@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * UsageStats 测试 —— 纯 JVM(KVUtils 在 MMKV 未初始化时退回内存 map)。
 */
class UsageStatsTest {

    @Before
    fun setUp() = UsageStats.reset()

    @Test
    fun `records tasks and accumulates tokens`() {
        UsageStats.recordTask(100)
        UsageStats.recordTask(50)
        assertEquals(2, UsageStats.tasks())
        assertEquals(150, UsageStats.tokens())
    }

    @Test
    fun `negative tokens treated as zero`() {
        UsageStats.recordTask(-999)
        assertEquals(1, UsageStats.tasks())
        assertEquals(0, UsageStats.tokens())
    }

    @Test
    fun `reset clears counters`() {
        UsageStats.recordTask(100)
        UsageStats.reset()
        assertEquals(0, UsageStats.tasks())
        assertEquals(0, UsageStats.tokens())
    }

    @Test
    fun `load restores persisted counters`() {
        UsageStats.recordTask(80)   // persist 写入 KVUtils
        UsageStats.load()           // 从 KVUtils 读回
        assertEquals(1, UsageStats.tasks())
        assertEquals(80, UsageStats.tokens())
    }
}
