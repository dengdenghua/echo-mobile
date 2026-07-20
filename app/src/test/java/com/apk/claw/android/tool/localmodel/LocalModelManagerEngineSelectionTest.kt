package com.apk.claw.android.tool.localmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * LocalModelManager 引擎选择单测 —— 覆盖 selectEngineForPath 纯逻辑。
 *
 * 不测试 loadModel/complete(依赖 native 库 + Android Context),
 * 不测试 isAvailable(依赖 System.loadLibrary 真实加载)。
 *
 * 仅验证:给定一个路径,能选对引擎(LlamaEngine / MnnLlmEngine / null)。
 */
class LocalModelManagerEngineSelectionTest {

    private lateinit var tmpRoot: File

    @Before
    fun setup() {
        tmpRoot = File(System.getProperty("java.io.tmpdir"), "engine_sel_${System.nanoTime()}")
        tmpRoot.mkdirs()
    }

    @After
    fun teardown() {
        tmpRoot.deleteRecursively()
    }

    // ── 文件路径(按后缀)──

    @Test
    fun `selectEngineForPath returns LlamaEngine for gguf file`() {
        val engine = LocalModelManager.selectEngineForPath("/sdcard/qwen-3b.gguf")
        assertEquals(LlamaEngine, engine)
    }

    @Test
    fun `selectEngineForPath returns MnnLlmEngine for mnn file`() {
        val engine = LocalModelManager.selectEngineForPath("/sdcard/model.mnn")
        assertEquals(MnnLlmEngine, engine)
    }

    @Test
    fun `selectEngineForPath is case insensitive for file extensions`() {
        assertEquals(LlamaEngine, LocalModelManager.selectEngineForPath("/x/Qwen.GGUF"))
        assertEquals(MnnLlmEngine, LocalModelManager.selectEngineForPath("/x/MODEL.MNN"))
        assertEquals(LlamaEngine, LocalModelManager.selectEngineForPath("/x/Qwen.Gguf"))
    }

    @Test
    fun `selectEngineForPath returns null for unknown extension`() {
        assertNull(LocalModelManager.selectEngineForPath("/x/model.txt"))
        assertNull(LocalModelManager.selectEngineForPath("/x/model.png"))
        assertNull(LocalModelManager.selectEngineForPath("/x/model.bin"))
        assertNull(LocalModelManager.selectEngineForPath("/x/model"))
    }

    @Test
    fun `selectEngineForPath returns null for path with no extension`() {
        assertNull(LocalModelManager.selectEngineForPath("/x/no_extension_file"))
    }

    // ── 目录路径(看内部是否有 .mnn 文件)──

    @Test
    fun `selectEngineForPath returns MnnLlmEngine for directory containing mnn files`() {
        val dir = File(tmpRoot, "qwen-mnn").apply { mkdirs() }
        File(dir, "embeddings.mnn").writeText("fake")
        File(dir, "tokenizer.json").writeText("{}")
        assertEquals(MnnLlmEngine, LocalModelManager.selectEngineForPath(dir.absolutePath))
    }

    @Test
    fun `selectEngineForPath returns null for directory without mnn files`() {
        val dir = File(tmpRoot, "empty-dir").apply { mkdirs() }
        File(dir, "readme.txt").writeText("not a model")
        assertNull(LocalModelManager.selectEngineForPath(dir.absolutePath))
    }

    @Test
    fun `selectEngineForPath returns null for empty directory`() {
        val dir = File(tmpRoot, "empty").apply { mkdirs() }
        assertNull(LocalModelManager.selectEngineForPath(dir.absolutePath))
    }

    @Test
    fun `selectEngineForPath detects mnn files case insensitive in directory`() {
        val dir = File(tmpRoot, "upper-mnn").apply { mkdirs() }
        File(dir, "MODEL.MNN").writeText("fake")
        assertEquals(MnnLlmEngine, LocalModelManager.selectEngineForPath(dir.absolutePath))
    }

    @Test
    fun `selectEngineForPath returns MnnLlmEngine when directory has only one mnn file`() {
        val dir = File(tmpRoot, "single-mnn").apply { mkdirs() }
        File(dir, "lonely.mnn").writeText("fake")
        assertEquals(MnnLlmEngine, LocalModelManager.selectEngineForPath(dir.absolutePath))
    }

    // ── 边界情况 ──

    @Test
    fun `selectEngineForPath handles nonexistent path as file`() {
        // 不存在的路径 → File.isDirectory 返回 false → 走文件分支
        // 路径有 .gguf 后缀 → LlamaEngine
        assertEquals(
            LlamaEngine,
            LocalModelManager.selectEngineForPath("/nonexistent/path/model.gguf"),
        )
    }

    @Test
    fun `selectEngineForPath handles nonexistent path without extension`() {
        assertNull(LocalModelManager.selectEngineForPath("/nonexistent/path/no_ext"))
    }

    @Test
    fun `selectEngineForPath handles relative path`() {
        // 相对路径 /sdcard/... 不会触发 isDirectory=true
        assertEquals(LlamaEngine, LocalModelManager.selectEngineForPath("relative/path/model.gguf"))
    }

    // ── 引擎注册(用 hasAnyEngineAvailable 不依赖 native 加载,但会返回 false)──

    @Test
    fun `listEngines returns exactly two registered engines`() {
        val engines = LocalModelManager.listEngines()
        assertEquals(2, engines.size)
        val ids = engines.map { it.id }.toSet()
        assert(ids.contains("llama_cpp")) { "Should contain llama_cpp engine" }
        assert(ids.contains("mnn")) { "Should contain mnn engine" }
    }

    @Test
    fun `isEngineAvailable returns false when native libs not loaded in JVM`() {
        // 在 JVM 测试环境(非 Android),native 库显然未加载
        assertEquals(false, LocalModelManager.isEngineAvailable("llama_cpp"))
        assertEquals(false, LocalModelManager.isEngineAvailable("mnn"))
    }

    @Test
    fun `isEngineAvailable returns false for unknown engine id`() {
        assertEquals(false, LocalModelManager.isEngineAvailable("nonexistent_engine"))
    }

    @Test
    fun `hasAnyEngineAvailable returns false in JVM environment`() {
        assertEquals(false, LocalModelManager.hasAnyEngineAvailable())
    }

    // ── 引擎 displayName ──

    @Test
    fun `LlamaEngine has correct metadata`() {
        assertEquals("llama_cpp", LlamaEngine.id)
        assertEquals("gguf", LlamaEngine.supportsFormat())
        assert(LlamaEngine.displayName.isNotEmpty())
    }

    @Test
    fun `MnnLlmEngine has correct metadata`() {
        assertEquals("mnn", MnnLlmEngine.id)
        assertEquals("mnn", MnnLlmEngine.supportsFormat())
        assert(MnnLlmEngine.displayName.isNotEmpty())
    }
}
