@file:Suppress("PackageNaming") // 包名 octopus_mobile 带下划线,历史遗留(原与 StartupMode.kt 同待遇,该文件已于 P2 清理中删除)

package com.apk.claw.android.octopus_mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MemoryStore 测试 —— 正则提取(只扫用户指令)、去重、上限淘汰、prompt 注入(纯 JVM,
 * KVUtils 在 MMKV 未初始化时退回内存 map)。
 */
class MemoryStoreTest {

    private val store = MemoryStore()

    @Before
    fun reset() {
        store.clearAll()
    }

    private fun mem(
        id: String,
        content: String,
        type: MemoryStore.MemoryType = MemoryStore.MemoryType.FACT,
        refs: Int = 0,
    ) = MemoryStore.Memory(
        id = id, content = content, type = type, source = "manual",
        createdAt = 0L, lastReferencedAt = System.currentTimeMillis(), referenceCount = refs,
    )

    @Test
    fun `从用户指令提取偏好`() {
        store.extractFromTask("我习惯用饿了么不用美团,帮我点份黄焖鸡")
        val prefs = store.getMemoriesByType(MemoryStore.MemoryType.PREFERENCE)
        assertEquals(1, prefs.size)
        assertTrue(prefs[0].content.contains("饿了么"))
        assertEquals("task_inferred", prefs[0].source)
    }

    @Test
    fun `从用户指令提取事实`() {
        store.extractFromTask("我叫小明,帮我发条短信给我妈")
        val facts = store.getMemoriesByType(MemoryStore.MemoryType.FACT)
        assertEquals(1, facts.size)
        assertTrue(facts[0].content.contains("小明"))
    }

    @Test
    fun `不含句式的指令不产生记忆`() {
        store.extractFromTask("打开淘宝搜索蓝牙耳机")
        assertTrue(store.getMemories().isEmpty())
    }

    @Test
    fun `相同内容不重复添加`() {
        store.addMemory(mem("a", "我用饿了么"))
        store.addMemory(mem("b", "我用饿了么"))
        assertEquals(1, store.getMemories().size)
    }

    @Test
    fun `超上限时优先淘汰非偏好记忆`() {
        // 内容加「号」后缀,避免"事实1"被"事实10"包含而触发近似去重
        repeat(50) { store.addMemory(mem("f$it", "事实${it}号", MemoryStore.MemoryType.FACT)) }
        store.addMemory(mem("p", "我只用地铁出行", MemoryStore.MemoryType.PREFERENCE))
        store.addMemory(mem("f-new", "新事实", MemoryStore.MemoryType.FACT))
        val all = store.getMemories()
        assertEquals(50, all.size)
        assertTrue(all.any { it.id == "p" })
    }

    @Test
    fun `prompt 注入按类型分区`() {
        store.addMemory(mem("p", "我用饿了么不用美团", MemoryStore.MemoryType.PREFERENCE))
        store.addMemory(mem("f", "我叫小明", MemoryStore.MemoryType.FACT))
        val section = store.buildPromptSection()
        assertTrue(section.contains("用户偏好"))
        assertTrue(section.contains("用户信息"))
        assertTrue(section.indexOf("饿了么") > section.indexOf("用户偏好"))
    }

    @Test
    fun `无记忆时注入空串`() {
        assertEquals("", store.buildPromptSection())
    }

    @Test
    fun `互为包含的近似重复不再添加`() {
        store.addMemory(mem("a", "我用饿了么"))
        store.addMemory(mem("b", "我用饿了么点外卖"))
        store.addMemory(mem("c", "饿了么"))
        assertEquals(1, store.getMemories().size)
    }

    @Test
    fun `harvest 收割 MEMO 行入库并从展示文本剥离`() {
        val answer = "已经帮你点好外卖了。\nMEMO: 用户习惯用饿了么点外卖\nMEMO: 用户住在中关村"
        val cleaned = store.harvestMemos(answer)
        assertEquals("已经帮你点好外卖了。", cleaned)
        val all = store.getMemories()
        assertEquals(2, all.size)
        assertTrue(all.all { it.source == "agent_inferred" })
        // 命中偏好措辞("习惯")按 PREFERENCE,否则按 FACT
        assertEquals(
            MemoryStore.MemoryType.PREFERENCE,
            all.first { it.content.contains("饿了么") }.type,
        )
        assertEquals(
            MemoryStore.MemoryType.FACT,
            all.first { it.content.contains("中关村") }.type,
        )
    }

    @Test
    fun `harvest 每任务最多收 2 条且无 MEMO 时原样返回`() {
        val flood = (1..5).joinToString("\n") { "MEMO: 事实编号$it" }
        store.harvestMemos("好的。\n$flood")
        assertEquals(2, store.getMemories().size)
        val plain = "好的,已完成。"
        assertEquals(plain, store.harvestMemos(plain))
    }

    @Test
    fun `注入超预算时偏好最先保住`() {
        store.addMemory(mem("f", "字".repeat(60), MemoryStore.MemoryType.FACT))
        store.addMemory(mem("p", "我只用地铁出行", MemoryStore.MemoryType.PREFERENCE))
        val section = store.buildPromptSection(charBudget = 20)
        assertTrue(section.contains("地铁"))
        assertFalse(section.contains("字字"))
    }

    @Test
    fun `采集指令只在对话页开关打开时附加`() {
        store.addMemory(mem("p", "我用饿了么", MemoryStore.MemoryType.PREFERENCE))
        assertFalse(store.buildPromptSection().contains("MEMO"))
        assertTrue(store.buildPromptSection(withMemoInstruction = true).contains("MEMO"))
    }

    @Test
    fun `注入即记一次引用`() {
        store.addMemory(mem("p", "我用饿了么", MemoryStore.MemoryType.PREFERENCE))
        store.buildPromptSection()
        store.buildPromptSection()
        assertEquals(2, store.getMemories().first().referenceCount)
    }

    @Test
    fun `超上限淘汰的是最少引用里最旧的`() {
        repeat(49) { store.addMemory(mem("f$it", "事实${it}号", refs = 1)) }
        store.addMemory(
            MemoryStore.Memory(
                id = "old-zero", content = "很旧且零引用", type = MemoryStore.MemoryType.FACT,
                source = "manual", createdAt = 0L, lastReferencedAt = 1L,
            ),
        )
        store.addMemory(mem("fresh-zero", "很新但零引用"))
        val all = store.getMemories()
        assertEquals(50, all.size)
        assertFalse(all.any { it.id == "old-zero" })
        assertTrue(all.any { it.id == "fresh-zero" })
    }

    @Test
    fun `过期上下文被清理而偏好不受影响`() {
        store.addMemory(
            MemoryStore.Memory(
                id = "ctx", content = "刚订了海底捞", type = MemoryStore.MemoryType.CONTEXT,
                source = "manual", createdAt = 0L, lastReferencedAt = 0L,
            ),
        )
        store.addMemory(mem("p", "我用饿了么", MemoryStore.MemoryType.PREFERENCE))
        store.pruneExpiredContexts()
        val all = store.getMemories()
        assertFalse(all.any { it.id == "ctx" })
        assertTrue(all.any { it.id == "p" })
    }
}
