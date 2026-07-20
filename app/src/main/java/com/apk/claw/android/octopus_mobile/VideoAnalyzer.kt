package com.apk.claw.android.octopus_mobile

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * 视频理解 —— 从视频文件提取关键帧,批量喂给 VLM 返回文字分析.
 *
 * **背景**:
 *  - [VisionAnalyzer] 只支持单张截图(屏幕感知),无法处理视频内容
 *  - 用户场景:"这段视频在讲什么"、"视频里出现了哪些文字"、"视频第几秒出现红色物体"
 *  - 方案:用 [MediaMetadataRetriever] 按等间距提取 N 帧 → 每帧 JPEG 编码 →
 *    用 [ChatMessage.User] 的多图能力一次性发给 VLM(复用 [LightweightLlmClient])
 *
 * **约束**:
 *  - 帧数默认 4,上限 8(避免 VLM token 爆炸 + 移动端 base64 内存压力)
 *  - 每帧降采样到 720px 宽(JPEG q=50),与 [VisionAnalyzer] 保持一致
 *  - 视频时长上限 60s(超过则只取前 60s,避免长视频内存爆炸)
 *  - 仅支持本机路径(file:// 或 /sdcard/...);远程 URL 需先下载
 *
 * **资源安全**:
 *  - [MediaMetadataRetriever] 必须在 finally 中 release()(系统资源,泄漏会拖垮媒体服务)
 *  - 提取的 Bitmap 在编码 base64 后立即 recycle()
 */
object VideoAnalyzer {

    private const val TAG = "VideoAnalyzer"

    /** 默认提取的帧数。太少(1)信息不足,太多(>8)token 爆炸。4 帧覆盖 10s 短视频足够。 */
    const val DEFAULT_FRAME_COUNT = 4
    /** 帧数上限。超过会被截断(VLM 输入图片数有上限,且移动端 base64 内存有限)。 */
    const val MAX_FRAME_COUNT = 8
    /** 视频时长上限(ms)。超过则只取前 N 毫秒,避免长视频内存爆炸与处理超时。 */
    const val MAX_DURATION_MS = 60_000L
    /** 单帧 JPEG 最大宽度(等比缩放),与 [VisionAnalyzer.MAX_WIDTH] 一致。 */
    private const val MAX_WIDTH = 720
    /** JPEG 压缩质量。 */
    private const val JPEG_QUALITY = 50

    /**
     * 从视频文件提取 [frameCount] 帧并编码为 base64 JPEG 列表。
     *
     * @param path 视频路径(/sdcard/xxx.mp4 或 file:// URI)
     * @param frameCount 要提取的帧数,会被 clamp 到 [1, MAX_FRAME_COUNT]
     * @param maxDurationMs 视频时长上限,超过则只取前 N 毫秒
     * @return 帧列表(base64 JPEG,无 data: 前缀),空列表表示提取失败或视频无效
     */
    fun extractFrames(
        path: String,
        frameCount: Int = DEFAULT_FRAME_COUNT,
        maxDurationMs: Long = MAX_DURATION_MS,
    ): List<String> {
        val n = frameCount.coerceIn(1, MAX_FRAME_COUNT)
        val retriever = MediaMetadataRetriever()
        val bitmaps = mutableListOf<Bitmap>()
        try {
            // 兼容 /sdcard/xxx 直接路径与 file:// content:// URI
            val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri.parse(path)
            } else null
            if (uri != null) {
                retriever.setDataSource(com.apk.claw.android.ClawApplication.instance, uri)
            } else {
                retriever.setDataSource(path)
            }

            val durationMs = try {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: 0L
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract duration: ${e.message}")
                0L
            }
            if (durationMs <= 0) return emptyList()

            val effectiveDuration = minOf(durationMs, maxDurationMs)
            // 在 [0, effectiveDuration) 内等间距取 n 帧:每帧位置 = i * step
            // i=0 取第 0ms(开场),i=n-1 取接近末尾(避免某些 codec 末尾帧解码失败)
            val step = if (n > 1) effectiveDuration / n else 0L

            for (i in 0 until n) {
                val posUs = (i * step) * 1000L  // MediaMetadataRetriever 用 us
                val bmp = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } else {
                        retriever.getFrameAtTime(posUs)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getFrameAtTime failed at ${posUs}us: ${e.message}")
                    null
                }
                if (bmp != null) bitmaps.add(bmp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractFrames failed: ${e.message}")
            return emptyList()
        } finally {
            try { retriever.release() } catch (e: Exception) { /* ignore */ }
        }

        // 编码为 base64 JPEG
        val result = bitmaps.map { encodeScaledJpeg(it) }
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        return result
    }

    /**
     * 分析视频并返回文字结果(阻塞,供同步工具调用)。
     *
     * @param path 视频路径
     * @param question 要问 VLM 的问题
     * @param frameCount 帧数,默认 4
     * @return VLM 的文字回答
     */
    fun analyzeVideoBlocking(
        path: String,
        question: String,
        frameCount: Int = DEFAULT_FRAME_COUNT,
    ): String = runBlocking {
        analyzeVideo(path, question, frameCount)
    }

    suspend fun analyzeVideo(
        path: String,
        question: String,
        frameCount: Int = DEFAULT_FRAME_COUNT,
    ): String {
        val frames = extractFrames(path, frameCount)
        check(frames.isNotEmpty()) { "无法从视频中提取帧(路径错误或格式不支持): $path" }

        val cfg = resolveConfig()
        val client = LightweightLlmClient(cfg)

        val system = ChatMessage.System(
            content = "你是视频内容分析助手。用户会给你一段视频的多个关键帧(按时间顺序排列)," +
                "请基于这些帧回答问题。描述要简洁准确,涉及时间点时用「第 N 帧」或「约 N 秒」表示。"
        )
        val user = ChatMessage.User(
            content = question,
            images = frames,
        )
        val resp = client.chat(listOf(system, user), emptyList())
        return resp.content?.trim().orEmpty().ifEmpty { "(视觉模型没有返回内容)" }
    }

    /** 是否已配置可用的视觉能力(与 [VisionAnalyzer.isConfigured] 同款)。 */
    fun isConfigured(): Boolean = VisionAnalyzer.isConfigured()

    /** 解析视觉模型配置(与 [VisionAnalyzer.resolveConfig] 同款,独立保留以便未来差异化)。 */
    private fun resolveConfig(): LlmConfig {
        val apiKey = KVUtils.getVisionApiKey().ifBlank { KVUtils.getLlmApiKey() }.trim()
        check(apiKey.isNotEmpty()) { "未配置视觉模型,请到 设置 → 模型配置 填写视觉模型" }

        val base = KVUtils.getVisionBaseUrl().ifBlank { KVUtils.getLlmBaseUrl() }
            .trim().ifEmpty { "https://api.deepseek.com/v1" }
        val model = KVUtils.getVisionModelName().ifBlank { KVUtils.getLlmModelName() }
            .trim().ifEmpty { "deepseek-chat" }

        val apiUrl = if (base.endsWith("/chat/completions")) base
        else base.trimEnd('/') + "/chat/completions"

        // 视频多帧会消耗更多 token,output 预算给足 2048
        return LlmConfig(
            apiUrl = apiUrl,
            apiKey = apiKey,
            model = model,
            temperature = 0.0,
            maxTokens = 2048,
        )
    }

    private fun encodeScaledJpeg(bitmap: Bitmap): String {
        var scaled = bitmap
        if (bitmap.width > MAX_WIDTH) {
            val scale = MAX_WIDTH.toFloat() / bitmap.width
            val newH = Math.round(bitmap.height * scale)
            scaled = Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, newH, true)
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
