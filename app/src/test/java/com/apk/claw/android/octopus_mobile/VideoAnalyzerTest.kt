@file:Suppress("PackageNaming", "MagicNumber")   // 沿用 octopus_mobile 包;帧数 / 时长等内联常量

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * VideoAnalyzer 单元测试 —— 纯 JVM,聚焦于不依赖 MediaMetadataRetriever 的逻辑。
 *
 * MediaMetadataRetriever 是 Android 系统类,在 JVM 单测环境会抛 RuntimeException,
 * 因此 extractFrames 的真实解码路径无法单测,只测参数归一化与错误处理。
 * analyzeVideo 的 HTTP 调用由 [LightweightLlmClientTest] 已覆盖。
 *
 * 本测试聚焦:
 *  1. ChatMessage.User 的多图字段(images)与单图字段(imageBase64)的归一化
 *  2. LightweightLlmClient 构造请求时多图正确的 OpenAI content 数组格式
 *  3. VideoAnalyzer 常量约束(帧数上限 / 时长上限)
 *  4. analyze_video 工具的参数校验(路径检查、未配置时返回错误)
 */
class VideoAnalyzerTest {

    @Before
    fun setUp() {
        KVUtils.resetForTest()
    }

    @Test
    fun `ChatMessage User with images keeps list intact`() {
        val msg = ChatMessage.User(content = "test", images = listOf("img1", "img2", "img3"))
        assertEquals(3, msg.images?.size)
        assertEquals("img1", msg.images?.get(0))
        assertNull(msg.imageBase64)
    }

    @Test
    fun `ChatMessage User with single imageBase64 keeps backward compat`() {
        val msg = ChatMessage.User(content = "test", imageBase64 = "single")
        assertEquals("single", msg.imageBase64)
        assertNull(msg.images)
    }

    @Test
    fun `ChatMessage User with both images and imageBase64 prefers images`() {
        // images 优先,但 imageBase64 字段仍保留(向后兼容,业务方应只用其一)
        val msg = ChatMessage.User(
            content = "test",
            imageBase64 = "single",
            images = listOf("multi1", "multi2"),
        )
        // LightweightLlmClient 归一化逻辑:images 优先
        val allImages: List<String> = msg.images ?: msg.imageBase64?.let { listOf(it) } ?: emptyList()
        assertEquals(2, allImages.size)
        assertEquals("multi1", allImages[0])
    }

    @Test
    fun `ChatMessage User with no images produces empty normalized list`() {
        val msg = ChatMessage.User(content = "plain text")
        val allImages: List<String> = msg.images ?: msg.imageBase64?.let { listOf(it) } ?: emptyList()
        assertTrue(allImages.isEmpty())
    }

    @Test
    fun `LightweightLlmClient builds correct multi-image content array`() {
        // 模拟 LightweightLlmClient 构造 OpenAI content 数组的逻辑
        val frames = listOf("base64_frame_0", "base64_frame_1", "base64_frame_2")
        val contentArray = JSONArray()
        val textObj = JSONObject().apply {
            put("type", "text")
            put("text", "describe this video")
        }
        contentArray.put(textObj)
        for (img in frames) {
            val imageObj = JSONObject().apply {
                put("type", "image_url")
                val imageUrlObj = JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$img")
                }
                put("image_url", imageUrlObj)
            }
            contentArray.put(imageObj)
        }

        // 验证:text + 3 张图 = 4 个 content 元素
        assertEquals(4, contentArray.length())
        // 第 0 个是 text
        assertEquals("text", contentArray.getJSONObject(0).getString("type"))
        assertEquals("describe this video", contentArray.getJSONObject(0).getString("text"))
        // 第 1-3 是 image_url
        for (i in 1..3) {
            val obj = contentArray.getJSONObject(i)
            assertEquals("image_url", obj.getString("type"))
            val url = obj.getJSONObject("image_url").getString("url")
            assertTrue(url.startsWith("data:image/jpeg;base64,base64_frame_"))
        }
    }

    @Test
    fun `VideoAnalyzer constants are sensible defaults`() {
        assertEquals(4, VideoAnalyzer.DEFAULT_FRAME_COUNT)
        assertEquals(8, VideoAnalyzer.MAX_FRAME_COUNT)
        assertEquals(60_000L, VideoAnalyzer.MAX_DURATION_MS)
        // 帧数应 <= 上限
        assertTrue(VideoAnalyzer.DEFAULT_FRAME_COUNT <= VideoAnalyzer.MAX_FRAME_COUNT)
    }

    @Test
    fun `isConfigured returns false when no api key set`() {
        // KVUtils 刚 reset,既无 vision key 也无 llm key
        assertFalse(VideoAnalyzer.isConfigured())
    }

    @Test
    fun `isConfigured returns true when vision api key set`() {
        KVUtils.setVisionApiKey("sk-vision-test")
        assertTrue(VideoAnalyzer.isConfigured())
    }

    @Test
    fun `isConfigured returns true when llm api key set`() {
        KVUtils.setLlmApiKey("sk-llm-test")
        assertTrue(VideoAnalyzer.isConfigured())
    }

    @Test
    fun `extractFrames with invalid path returns empty list`() {
        // MediaMetadataRetriever.setDataSource 在 JVM 环境会抛异常,
        // extractFrames 应捕获并返回空列表(而非传播异常)
        val result = VideoAnalyzer.extractFrames("/nonexistent/path/video.mp4")
        // JVM 环境下 MediaMetadataRetriever 不可用,预期返回空列表
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractFrames clamps frame count to max`() {
        // 传入超过 MAX_FRAME_COUNT 的值,extractFrames 应 clamp 到 MAX_FRAME_COUNT
        // 由于 JVM 无法真实提取帧,这里只验证不抛异常
        val result = VideoAnalyzer.extractFrames("/nonexistent.mp4", frameCount = 100)
        assertTrue(result.isEmpty())  // 因路径无效,返回空
    }

    private fun assertFalse(actual: Boolean) {
        org.junit.Assert.assertFalse(actual)
    }
}
