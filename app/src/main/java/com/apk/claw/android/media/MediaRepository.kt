package com.apk.claw.android.media

import com.apk.claw.android.R
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 一张生成的图。 */
data class GenImage(val url: String)

/**
 * 视频任务快照(透传服务端 → Agnes,OpenAI-Sora 风格)。
 * status: queued / in_progress / processing / completed / failed …
 * url 为完成后的可下载/播放地址(若服务端响应里带);否则用 [videoId] 走下载端点取(待服务端补)。
 */
data class VideoTask(
    val taskId: String,
    val status: String,
    val progress: Int = 0,
    val videoId: String? = null,
    val url: String? = null,
    val error: String? = null,
) {
    val isDone: Boolean get() = status.lowercase() in DONE
    val isFailed: Boolean get() = status.lowercase() in FAILED

    companion object {
        val DONE = setOf("completed", "succeeded", "success")
        val FAILED = setOf("failed", "error", "cancelled", "canceled")
    }
}

/**
 * 生图 / 生视频客户端调用层(增值功能)。
 *
 * 调服务端中转端点(token 复用账号登录态,key 永远在服务端):
 *   - POST /v1/images/generations          生图(同步)
 *   - POST /v1/video/generations           生视频(异步提交,返回 task_id)
 *   - GET  /v1/video/generations/{taskId}  轮询视频任务
 *
 * 门控(会员免费 / 非会员扣积分 / 视频每日配额)由服务端处理:本层只负责发请求 + 解析,
 * 把服务端的友好错误(402 积分不足 / 429 繁忙等)透传给 UI。所有方法用 [Result] 包裹。
 */
object MediaRepository {

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS) // 生图同步,给足上游出图时间
        .build()

    private fun base() = AccountConfig.baseUrl.trimEnd('/')

    /** 生图(同步)。返回首张图的 url。 */
    suspend fun generateImage(prompt: String, size: String = "1024x1024"): Result<GenImage> =
        runCatching {
            val obj = postJson("/v1/images/generations", mapOf("prompt" to prompt, "size" to size))
            val url = obj.getAsJsonArray("data")
                ?.firstOrNull()?.asJsonObject?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw RuntimeException("未返回图片")
            GenImage(url)
        }

    /** 生视频:仅提交,返回 task(异步)。轮询键 = video_id(Agnes 用它查 /agnesapi)。 */
    suspend fun submitVideo(prompt: String): Result<VideoTask> =
        runCatching {
            val o = postJson("/v1/video/generations", mapOf("prompt" to prompt))
            val videoId = strField(o, "video_id", "task_id", "id") ?: ""
            VideoTask(
                taskId = videoId,
                status = strField(o, "status") ?: "queued",
                progress = intField(o),
                videoId = videoId,
                url = videoUrl(o),
                error = strField(o, "error", "fail_reason"),
            )
        }

    /** 轮询单个视频任务(传 submitVideo 返回的 video_id)。响应来自 Agnes /agnesapi。 */
    suspend fun pollVideo(videoId: String): Result<VideoTask> =
        runCatching {
            val o = getJson("/v1/video/generations/$videoId")
            VideoTask(
                taskId = videoId, // 响应里没有 video_id,保留传入的作轮询键
                status = strField(o, "status") ?: "queued",
                progress = intField(o),
                videoId = videoId,
                url = videoUrl(o),
                error = strField(o, "error", "fail_reason"),
            )
        }

    /**
     * 生视频一站式:提交 → 每 3s 轮询直到完成/失败/超时(默认 ~3 分钟)。
     * [onProgress] 每次状态更新回调,供 UI 显示进度。
     */
    suspend fun generateVideo(prompt: String, onProgress: (VideoTask) -> Unit = {}): Result<VideoTask> {
        var task = submitVideo(prompt).getOrElse { return Result.failure(it) }
        onProgress(task)
        repeat(60) {
            if (task.isDone) return Result.success(task)
            if (task.isFailed) return Result.failure(RuntimeException(task.error ?: "视频生成失败"))
            delay(3_000)
            task = pollVideo(task.taskId).getOrElse { return Result.failure(it) }
            onProgress(task)
        }
        return if (task.isDone) Result.success(task)
        else Result.failure(RuntimeException("视频生成超时,请稍后再查"))
    }

    // ── 字段解析助手 ──
    private fun strField(o: JsonObject, vararg keys: String): String? {
        for (k in keys) o.get(k)?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
        return null
    }

    private fun intField(o: JsonObject, key: String = "progress"): Int =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: 0

    /** 完成后的视频地址:Agnes 放在 remixed_from_video_id(其次兼容常见字段);仅取 http 链接。 */
    private fun videoUrl(o: JsonObject): String? {
        val u = strField(o, "remixed_from_video_id", "url", "video_url", "download_url", "output_url")
        return if (u != null && u.startsWith("http")) u else null
    }

    // ── HTTP(镜像 HttpAccountGateway:Bearer token + FastAPI detail/error.message 错误透传)──
    private fun reqBuilder(path: String): Request.Builder {
        // 生图/生视频按账号积分计费:本地模式(未登录)不发请求,直接给工具一句看得懂的原因。
        val token = AccountStore.token
        check(token.isNotEmpty()) { ClawApplication.instance.getString(R.string.account_gate_media) }
        return Request.Builder().url(base() + path).header("Authorization", "Bearer $token")
    }

    private suspend fun postJson(path: String, body: Any): JsonObject = withContext(Dispatchers.IO) {
        exec(reqBuilder(path).post(gson.toJson(body).toRequestBody(JSON)).build())
    }

    private suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        exec(reqBuilder(path).get().build())
    }

    private fun exec(req: Request): JsonObject {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException(serverError(text) ?: "HTTP ${resp.code}: ${text.take(200)}")
            }
            return gson.fromJson(text, JsonObject::class.java) ?: JsonObject()
        }
    }

    /** 透传服务端友好错误:FastAPI `{"detail": "..."}` 或中转 `{"error": {"message": "..."}}`。 */
    private fun serverError(body: String): String? = runCatching {
        val o = gson.fromJson(body, JsonObject::class.java) ?: return@runCatching null
        o.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
            ?: o.getAsJsonObject("error")?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()
}
