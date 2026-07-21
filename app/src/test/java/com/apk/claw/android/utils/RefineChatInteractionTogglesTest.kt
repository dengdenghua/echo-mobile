@file:Suppress("PackageNaming")

package com.apk.claw.android.utils

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * refine-chat-interaction Task 9:SettingsActivity 两个开关的持久化测试。
 *
 * 纯 JVM(KVUtils 在 MMKV 未初始化时退回内存 map,与 ApiKeyPoolTest 同款),
 * 不需要 Robolectric。
 */
class RefineChatInteractionTogglesTest {

    @Before
    fun setUp() {
        KVUtils.resetForTest()
    }

    @After
    fun tearDown() {
        KVUtils.resetForTest()
    }

    @Test
    fun `isCollapseParallelTools default is true`() {
        assertTrue(KVUtils.isCollapseParallelTools())
    }

    @Test
    fun `setCollapseParallelTools false then isCollapseParallelTools returns false`() {
        KVUtils.setCollapseParallelTools(false)
        assertFalse(KVUtils.isCollapseParallelTools())
    }

    @Test
    fun `setCollapseParallelTools true after false restores true`() {
        KVUtils.setCollapseParallelTools(false)
        assertFalse(KVUtils.isCollapseParallelTools())
        KVUtils.setCollapseParallelTools(true)
        assertTrue(KVUtils.isCollapseParallelTools())
    }

    @Test
    fun `isDetailDrawerEnabled default is true`() {
        assertTrue(KVUtils.isDetailDrawerEnabled())
    }

    @Test
    fun `setDetailDrawerEnabled false then isDetailDrawerEnabled returns false`() {
        KVUtils.setDetailDrawerEnabled(false)
        assertFalse(KVUtils.isDetailDrawerEnabled())
    }

    @Test
    fun `setDetailDrawerEnabled true after false restores true`() {
        KVUtils.setDetailDrawerEnabled(false)
        assertFalse(KVUtils.isDetailDrawerEnabled())
        KVUtils.setDetailDrawerEnabled(true)
        assertTrue(KVUtils.isDetailDrawerEnabled())
    }
}
