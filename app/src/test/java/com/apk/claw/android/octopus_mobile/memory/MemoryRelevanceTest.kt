@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.memory 包(带下划线)

package com.apk.claw.android.octopus_mobile.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MemoryStore 相关性排序测试 —— 纯 JVM(KVUtils 在 MMKV 未初始化时退回内存 map,见 testing-jvm-stores)。
 *
 * 验证:给了 taskHint,与任务相关的记忆顶到无关记忆之前;不给则保持原(confidence/插入)序。
 */
class MemoryRelevanceTest {

    private val store = MemoryStore()

    @Before
    fun setUp() = store.clearAll()

    @After
    fun tearDown() = store.clearAll()

    private fun fact(content: String) = MemoryStore.Memory(
        id = content.hashCode().toString(),
        content = content,
        type = MemoryStore.MemoryType.FACT,
        source = "test",
        createdAt = 1L,
        lastReferencedAt = 1L,
        referenceCount = 0,
        confidence = 0.9,   // 同 confidence,排序差异只由相关性决定
    )

    @Test
    fun `relevant fact ranked before irrelevant when taskHint given`() {
        store.addMemory(fact("用户喜欢喝美式咖啡不加糖"))   // 先插:无关
        store.addMemory(fact("用户常去北京出差订机票"))     // 后插:相关
        val out = store.buildPromptSection(taskHint = "订一张去北京的机票")
        val iBeijing = out.indexOf("北京")
        val iCoffee = out.indexOf("咖啡")
        assertTrue("两条都应注入", iBeijing >= 0 && iCoffee >= 0)
        assertTrue("相关记忆(北京/机票)应排在无关(咖啡)之前", iBeijing < iCoffee)
    }

    @Test
    fun `without taskHint keeps original order`() {
        store.addMemory(fact("用户喜欢喝美式咖啡不加糖"))   // 先插
        store.addMemory(fact("用户常去北京出差订机票"))     // 后插
        val out = store.buildPromptSection()   // 无 hint → 同 confidence 稳定排序保持插入序
        assertTrue("无 taskHint 时保持原序:咖啡在前", out.indexOf("咖啡") < out.indexOf("北京"))
    }

    @Test
    fun `addUserFact then removeMemory roundtrip`() {
        store.addUserFact("我住在北京朝阳区")
        val after = store.getMemories()
        assertEquals(1, after.size)
        assertTrue(after[0].content.contains("朝阳区"))
        assertEquals(MemoryStore.MemoryType.FACT, after[0].type)

        store.removeMemory(after[0].id)
        assertEquals(0, store.getMemories().size)
    }

    @Test
    fun `addUserFact blank is ignored and dedups`() {
        store.addUserFact("   ")
        assertEquals(0, store.getMemories().size)
        store.addUserFact("我对花生过敏")
        store.addUserFact("我对花生过敏")   // 去重
        assertEquals(1, store.getMemories().size)
    }

    @Test
    fun `addMemory skips near-duplicate memories`() {
        store.addUserFact("我常用饿了么点外卖")
        store.addUserFact("我常用饿了么点外卖啊")   // 近似 → 跳过
        assertEquals("近似记忆去重,只留 1 条", 1, store.getMemories().size)
        // 真不同的仍能加
        store.addUserFact("我住北京朝阳区")
        assertEquals(2, store.getMemories().size)
    }
}
