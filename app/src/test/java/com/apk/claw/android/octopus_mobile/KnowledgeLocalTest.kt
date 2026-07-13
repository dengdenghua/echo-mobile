@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KnowledgeLocal 测试 —— 纯 JVM(InteractionLedger init(tmpDir) + MemoryStore KVUtils 内存回退)。
 * gather 采本机知识、restore 导入,gather→restore 一圈保真。
 */
class KnowledgeLocalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        InteractionLedger.reset()
        InteractionLedger.init(tmp.root)
        MemoryStore().clearAll()
    }

    @After
    fun tearDown() {
        InteractionLedger.reset()
        MemoryStore().clearAll()
    }

    @Test
    fun `gather then restore roundtrips local knowledge`() {
        InteractionLedger.addManualRule("打开淘宝先关弹窗")
        MemoryStore().addUserFact("我对花生过敏")

        val bundle = KnowledgeLocal.gather()

        // 模拟另一台干净设备:换个空目录重新 init(原目录持久化的规矩不带过去),记忆清空。
        InteractionLedger.reset()
        InteractionLedger.init(tmp.newFolder())
        MemoryStore().clearAll()
        assertEquals(0, InteractionLedger.size())

        val restored = KnowledgeLocal.restore(bundle)
        assertEquals(1 to 1, restored)
        assertEquals(1, InteractionLedger.size())
        assertTrue(MemoryStore().getMemories().any { it.content.contains("花生") })
    }

    @Test
    fun `restore rejects invalid bundle`() {
        assertNull(KnowledgeLocal.restore("not a bundle"))
        assertNull(KnowledgeLocal.restore("{}"))
    }
}
