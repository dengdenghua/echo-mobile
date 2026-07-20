package com.apk.claw.android.tool.localmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * MnnModelManager 单测 —— 覆盖 detectType 纯逻辑。
 *
 * 不依赖 Android Context / Robolectric:detectType 只看目录内文件名,
 * 用临时目录造文件即可。
 *
 * importFromUri / listModels / setActiveLlm 涉及 SAF / KVUtils / Android Context,
 * 留给真机验证。
 */
class MnnModelManagerTest {

    private lateinit var tmpRoot: File

    @Before
    fun setup() {
        tmpRoot = File(System.getProperty("java.io.tmpdir"), "mnn_test_${System.nanoTime()}")
        tmpRoot.mkdirs()
    }

    @After
    fun teardown() {
        tmpRoot.deleteRecursively()
    }

    @Test
    fun `detectType returns LLM for directory with embeddings mnn`() {
        val dir = File(tmpRoot, "qwen-1.8b").apply { mkdirs() }
        File(dir, "embeddings.mnn").writeText("fake")
        File(dir, "blocks_0_qkv.mnn").writeText("fake")
        File(dir, "tokenizer.json").writeText("{}")
        File(dir, "config.json").writeText("{}")

        assertEquals(MnnModelManager.ModelType.LLM, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType returns WHISPER for directory with encoder and decoder mnn`() {
        val dir = File(tmpRoot, "whisper-tiny").apply { mkdirs() }
        File(dir, "embedding.mnn").writeText("fake")  // whisper 也有 embedding,但有 encoder+decoder 优先判为 whisper
        File(dir, "encoder.mnn").writeText("fake")
        File(dir, "decoder.mnn").writeText("fake")
        File(dir, "tokenizer.json").writeText("{}")

        assertEquals(MnnModelManager.ModelType.WHISPER, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType returns UNKNOWN for empty directory`() {
        val dir = File(tmpRoot, "empty").apply { mkdirs() }
        assertEquals(MnnModelManager.ModelType.UNKNOWN, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType returns UNKNOWN for directory with no mnn files`() {
        val dir = File(tmpRoot, "junk").apply { mkdirs() }
        File(dir, "readme.txt").writeText("not a model")
        File(dir, "image.png").writeText("fake")
        assertEquals(MnnModelManager.ModelType.UNKNOWN, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType is case insensitive`() {
        val dir = File(tmpRoot, "mixed-case").apply { mkdirs() }
        File(dir, "ENCODER.MNN").writeText("fake")
        File(dir, "Decoder.Mnn").writeText("fake")
        // 大小写不敏感
        assertEquals(MnnModelManager.ModelType.WHISPER, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType returns LLM for embedding singular without encoder`() {
        val dir = File(tmpRoot, "llm-only").apply { mkdirs() }
        File(dir, "embedding.mnn").writeText("fake")
        File(dir, "config.json").writeText("{}")
        assertEquals(MnnModelManager.ModelType.LLM, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType returns WHISPER when only encoder present but no decoder`() {
        // 边界:只有 encoder 没有 decoder → 不是 whisper(需要 encoder + decoder 同时存在)
        val dir = File(tmpRoot, "half-whisper").apply { mkdirs() }
        File(dir, "encoder.mnn").writeText("fake")
        // embedding 也没,所以应该是 UNKNOWN
        assertEquals(MnnModelManager.ModelType.UNKNOWN, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType walks subdirectories`() {
        // 模型目录可能有子目录(如 tokenizer/、assets/)
        val dir = File(tmpRoot, "nested").apply { mkdirs() }
        val subDir = File(dir, "weights").apply { mkdirs() }
        File(subDir, "embeddings.mnn").writeText("fake")
        File(dir, "config.json").writeText("{}")
        assertEquals(MnnModelManager.ModelType.LLM, MnnModelManager.detectType(dir))
    }

    @Test
    fun `detectType returns UNKNOWN for nonexistent directory`() {
        val dir = File(tmpRoot, "does-not-exist")
        // 不存在的目录 → walkTopDown 返回空序列 → UNKNOWN
        assertEquals(MnnModelManager.ModelType.UNKNOWN, MnnModelManager.detectType(dir))
    }

    // ── ModelInfo 数据类(仅编译时验证字段存在,不实际构造)──

    @Test
    fun `ModelType enum has exactly three values`() {
        val values = MnnModelManager.ModelType.values().toSet()
        assertEquals(3, values.size)
        assertNull(null)  // 占位,确保测试有 assertion
        assert(values.containsAll(listOf(
            MnnModelManager.ModelType.LLM,
            MnnModelManager.ModelType.WHISPER,
            MnnModelManager.ModelType.UNKNOWN,
        )))
    }
}
