package com.apk.claw.android.agent.llm

import android.content.Context
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * MNN 模型管理器(agent/llm 包专用)—— 管理 LLM 模型文件的下载/列出/删除/推荐。
 *
 * 与 `tool.localmodel.MnnModelManager` 的区别:
 *  - 那个负责通用模型文件导入(包括 Whisper ASR),通过 SAF Uri 导入
 *  - 本类专注于 LLM,负责从 [MnnModelPresets] 下载预置模型并解压,
 *    供 [MnnLlmClient] 加载使用
 *
 * 模型目录约定:`filesDir/models/mnn/<name>/` 内含 .mnn 权重 + config.json + tokenizer。
 *
 * 集成阶段需在 build.gradle.kts 添加:
 * ```
 * implementation("com.squareup.okhttp3:okhttp:4.12.0")   // 已存在
 * implementation("com.alibaba.mnn:mnn-android:1.3.0")    // native 推理依赖
 * ```
 */
class MnnModelManager(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val TAG = "agent.llm.MnnModelManager"
        private const val ROOT_DIR = "models/mnn"
        private const val CONFIG_FILE = "config.json"
        private const val TMP_SUFFIX = ".downloading"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 获取 MNN 模型根目录(自动创建)。 */
    fun getRootDir(context: Context): File {
        return File(context.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }
    }

    /** 获取指定 name 的模型目录(不一定存在)。 */
    fun getModelDir(context: Context, name: String): File {
        return File(getRootDir(context), name)
    }

    /**
     * 列出所有已安装的 MNN LLM 模型。
     *
     * 扫描 [getRootDir] 下每个子目录,仅返回含 config.json 的目录(视为合法 LLM)。
     */
    fun listInstalledModels(context: Context): List<MnnModelInfo> {
        val root = getRootDir(context)
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val config = File(dir, CONFIG_FILE)
            if (!config.exists()) return@mapNotNull null
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            MnnModelInfo(
                name = dir.name,
                path = dir.absolutePath,
                configPath = config.absolutePath,
                sizeBytes = size,
                installedAt = dir.lastModified(),
            )
        }?.sortedByDescending { it.installedAt } ?: emptyList()
    }

    /**
     * 下载预置 MNN 模型(.zip)并解压。
     *
     * @param context 用于定位 filesDir
     * @param url 下载地址
     * @param name 目标目录名(同名校验:已存在则失败)
     * @return 成功返回 [MnnModelInfo],失败返回 Result with exception
     */
    suspend fun downloadModel(context: Context, url: String, name: String): Result<MnnModelInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetDir = getModelDir(context, name)
                if (targetDir.exists()) {
                    throw IllegalStateException("同名模型已存在: $name")
                }
                targetDir.mkdirs()

                val tmpZip = File(targetDir.parentFile, "$name$TMP_SUFFIX.zip")
                try {
                    downloadTo(url, tmpZip)
                    unzip(tmpZip, targetDir)
                } finally {
                    tmpZip.delete()
                }

                // 校验:解压后必须含 config.json
                val config = File(targetDir, CONFIG_FILE)
                if (!config.exists()) {
                    targetDir.deleteRecursively()
                    throw IllegalStateException("下载的模型包缺少 $CONFIG_FILE")
                }

                val size = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                XLog.i(TAG, "Model installed: $name (${size / 1024 / 1024}MB) -> ${targetDir.absolutePath}")
                MnnModelInfo(
                    name = targetDir.name,
                    path = targetDir.absolutePath,
                    configPath = config.absolutePath,
                    sizeBytes = size,
                    installedAt = targetDir.lastModified(),
                )
            }
        }

    /** 删除已安装的模型目录。返回是否删除成功(目录不存在也返回 true)。 */
    fun deleteModel(context: Context, name: String): Boolean {
        val dir = getModelDir(context, name)
        if (!dir.exists()) return true
        val ok = dir.deleteRecursively()
        if (ok) XLog.i(TAG, "Model deleted: $name")
        else XLog.w(TAG, "Failed to delete model: $name")
        return ok
    }

    /** 根据当前设备硬件推荐预置模型(委托给 [HardwareDetector])。 */
    fun recommendModel(context: Context): MnnModelPreset? {
        return HardwareDetector.getRecommendedModel(context)
    }

    // ── 内部工具 ──

    private fun downloadTo(url: String, dest: File) {
        XLog.i(TAG, "Downloading: $url -> ${dest.absolutePath}")
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("下载失败 HTTP ${resp.code}: ${resp.message}")
            }
            val body = resp.body ?: throw IllegalStateException("下载响应体为空")
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
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
