package com.apk.claw.android.registry

import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 市场列表要能区分「网络失败」和「真的为空」：失败时界面显示「加载失败 + 重试」，不再显示空状态。
 * 纯 JVM：KVUtils 在 MMKV 未初始化时退回内存 map；registry 基址指向 MockWebServer。
 */
class RegistryClientListResultTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        KVUtils.resetForTest()
        server = MockWebServer()
        server.start()
        KVUtils.putString("REGISTRY_BASE_URL", server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
        KVUtils.resetForTest()
    }

    private fun listBody(vararg slugs: String): String = slugs.joinToString(
        prefix = """{"success":true,"total":${slugs.size},"data":[""",
        postfix = "]}",
    ) { """{"id":"skill/$it","type":"skill","name":"$it","version":"1.0.0"}""" }

    @Test
    fun `server error without cache is a failure, not an empty list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = RegistryClient.listSkillsResult()

        assertTrue(result.isFailure)
    }

    @Test
    fun `legacy listSkills still returns empty on failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(emptyList<RegistryAsset>(), RegistryClient.listSkills())
    }

    @Test
    fun `server error falls back to the cached catalogue`() = runBlocking {
        server.enqueue(MockResponse().setBody(listBody("alpha", "beta")))
        server.enqueue(MockResponse().setResponseCode(500))

        val fresh = RegistryClient.listSkillsResult().getOrThrow()
        val offline = RegistryClient.listSkillsResult()

        assertEquals(listOf("alpha", "beta"), fresh.map { it.slug })
        assertTrue(offline.isSuccess)
        assertEquals(listOf("alpha", "beta"), offline.getOrThrow().map { it.slug })
    }

    @Test
    fun `empty catalogue from a healthy server is a success`() = runBlocking {
        server.enqueue(MockResponse().setBody(listBody()))

        val result = RegistryClient.listPluginsResult()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }
}
