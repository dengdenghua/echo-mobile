@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KnowledgeSync 测试 —— 用 MockWebServer 起真 HTTP 端点,验证拉取→解析→导入本机。
 * InteractionLedger 靠 init(tmpDir) 纯 JVM 可测;MemoryStore 靠 KVUtils 内存回退。
 */
class KnowledgeSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        InteractionLedger.reset()
        InteractionLedger.init(tmp.root)
        MemoryStore().clearAll()
    }

    @After
    fun tearDown() {
        server.shutdown()
        InteractionLedger.reset()
        MemoryStore().clearAll()
    }

    @Test
    fun `pull imports rules and memories from peer`() {
        val bundle = KnowledgeBundle.export(
            rules = listOf("打开淘宝先关弹窗", "别点广告"),
            memories = listOf(KnowledgeBundle.MemItem("我对花生过敏", "FACT")),
        )
        server.enqueue(MockResponse().setBody(bundle))

        val result = KnowledgeSync.pullFrom(server.url("/").toString(), "tok", http)
        assertEquals(2 to 1, result)
        assertEquals("2 条规矩已导入本机", 2, InteractionLedger.size())
        assertEquals("1 条记忆已导入本机", 1, MemoryStore().getMemories().size)
    }

    @Test
    fun `pull returns null on server error`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(KnowledgeSync.pullFrom(server.url("/").toString(), "tok", http))
    }

    @Test
    fun `pull returns null on non-bundle body`() {
        server.enqueue(MockResponse().setBody("<html>not a bundle</html>"))
        assertNull(KnowledgeSync.pullFrom(server.url("/").toString(), "tok", http))
    }
}
