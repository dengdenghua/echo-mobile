package com.apk.claw.android.tool.localmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import com.apk.claw.android.utils.KVUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MNN 模型管理器 —— 管理 MNN 格式模型目录的导入/列出/删除/激活。
 *
 * 与 [LocalModelManager] 的区别:
 *  - [LocalModelManager] 负责推理(加载/complete/卸载),通过 [LocalLlmEngine] 抽象
 *  - [MnnModelManager] 负责模型文件管理(导入/列出/删除/激活),不分引擎
 *
 * MNN 模型目录结构(转换后):
 *   Qwen-1.8B-INT8/
 *   ├── embeddings.mnn
 *   ├── blocks_0_qkv.mnn
 *   ├── ...
 *   ├── tokenizer.json
 *   ├── config.json
 *   └── mnn.json(模型清单)
 *
 * Whisper 模型目录结构:
 *   whisper-tiny/
 *   ├── embedding.mnn
 *   ├── encoder.mnn
 *   ├── decoder.mnn
 *   ├── tokenizer.json
 *   └── config.json
 *
 * 模型类型识别:看目录内是否有 encoder/decoder/embeddings 文件
 *  - 含 embedding*.mnn + tokenizer.json + (无 encoder) → LLM
 *  - 含 encoder.mnn + decoder.mnn → Whisper ASR
 */
object MnnModelManager {

    private const val TAG = "MnnModelManager"

    /** MNN 模型根目录(在 app 私有目录下)。 */
    private const val MNN_DIR = "mnn_models"

    /** 模型类型。 */
    enum class ModelType { LLM, WHISPER, UNKNOWN }

    /** 已导入的模型目录(目录名 → 信息)。 */
    data class ModelInfo(
        val dir: File,
        val name: String,
        val type: ModelType,
        val sizeBytes: Long,
        val fileCount: Int,
        val importedAt: Long,
    )

    /** 获取 MNN 模型根目录(自动创建)。 */
    fun getRootDir(ctx: Context): File {
        return File(ctx.filesDir, MNN_DIR).apply { if (!exists()) mkdirs() }
    }

    /** 列出所有已导入的 MNN 模型。 */
    fun listModels(ctx: Context): List<ModelInfo> {
        val root = getRootDir(ctx)
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) return@mapNotNull null
            val type = detectType(dir)
            ModelInfo(
                dir = dir,
                name = dir.name,
                type = type,
                sizeBytes = files.sumOf { it.length() },
                fileCount = files.size,
                importedAt = dir.lastModified(),
            )
        }?.sortedByDescending { it.importedAt } ?: emptyList()
    }

    /** 检测模型类型。 */
    fun detectType(dir: File): ModelType {
        val files = dir.walkTopDown().filter { it.isFile }.map { it.name.lowercase() }.toSet()
        val hasEncoder = files.any { it.contains("encoder") }
        val hasDecoder = files.any { it.contains("decoder") }
        val hasEmbedding = files.any { it.contains("embedding") }
        return when {
            hasEncoder && hasDecoder -> ModelType.WHISPER
            hasEmbedding -> ModelType.LLM
            else -> ModelType.UNKNOWN
        }
    }

    /**
     * 从 SAF Uri 导入 MNN 模型(支持 .zip 压缩包或单 .mnn 文件)。
     * 压缩包会自动解压到目录。
     *
     * @return 成功返回模型目录,失败返回 null + 错误消息
     */
    suspend fun importFromUri(
        ctx: Context,
        uri: Uri,
    ): Pair<File?, String?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val name = queryFileName(ctx, uri) ?: "model_${System.currentTimeMillis()}"
            val targetDir = File(getRootDir(ctx), name.substringBeforeLast('.'))
            if (targetDir.exists()) {
                return@withContext null to "同名模型已存在: ${targetDir.name}"
            }
            targetDir.mkdirs()

            when (name.lowercase().substringAfterLast('.', "")) {
                "zip" -> {
                    // 解压 zip
                    val tmpZip = File(targetDir.parentFile, "_tmp_$name.zip")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmpZip.outputStream().use { input.copyTo(it) }
                    } ?: return@withContext null to "无法读取文件"
                    unzip(tmpZip, targetDir)
                    tmpZip.delete()
                }
                "mnn" -> {
                    // 单个 .mnn 文件
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        File(targetDir, name).outputStream().use { input.copyTo(it) }
                    } ?: return@withContext null to "无法读取文件"
                }
                else -> {
                    // 不识别扩展名,但尝试复制
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        File(targetDir, name).outputStream().use { input.copyTo(it) }
                    } ?: return@withContext null to "无法读取文件"
                }
            }

            // 验证导入的目录是否合法
            val type = detectType(targetDir)
            if (type == ModelType.UNKNOWN) {
                targetDir.deleteRecursively()
                return@withContext null to "导入的模型格式不识别(目录内无 embedding/encoder/decoder .mnn 文件)"
            }
            Log.i(TAG, "Model imported: ${targetDir.name} (type=$type)")
            targetDir to null
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            null to (e.message ?: "导入失败")
        }
    }

    /** 删除模型目录。 */
    fun deleteModel(dir: File): Boolean {
        return dir.deleteRecursively().also { ok ->
            if (ok) Log.i(TAG, "Model deleted: ${dir.name}")
        }
    }

    /** 设置 LLM 活跃模型(用绝对路径写入 KVUtils)。 */
    fun setActiveLlm(dir: File) {
        KVUtils.setActiveLocalModel(dir.absolutePath)
    }

    /** 设置 Whisper 活跃模型。 */
    fun setActiveWhisper(dir: File) {
        KVUtils.putString(MnnWhisperEngine.KEY_ACTIVE_WHISPER_MODEL, dir.absolutePath)
    }

    /** 获取已配置的 Whisper 模型目录(可能未加载)。 */
    fun getActiveWhisperPath(): String =
        KVUtils.getString(MnnWhisperEngine.KEY_ACTIVE_WHISPER_MODEL, "")

    // ── 工具 ──

    private fun queryFileName(ctx: Context, uri: Uri): String? {
        return try {
            val cursor = ctx.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // 防 Zip Slip 路径遍历
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
