package com.apk.claw.android.octopus_mobile
import com.apk.claw.android.utils.OctoHttp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

/**
 * 方案 F 轻量 LLM 客户端.
 *
 * 直接 OkHttp 调 OpenAI-兼容 API（DeepSeek / Qwen / GLM / Ollama 都行）.
 * 无任何 LangChain 框架依赖，约 200 行 Kotlin.
 *
 * 优势对比 LangChain4j：
 *  - 包大小：+0MB（vs +5MB）
 *  - 启动：+0ms（vs +1-2s 框架初始化）
 *  - 调试：白盒（vs 框架黑盒）
 *  - 切换厂商：改 1 个 URL（vs 换 SDK）
 *
 * Phase 0 占位实现 - 后续可加 retry / streaming / multimodal.
 */
class LightweightLlmClient(
    private val config: LlmConfig,
    /**
     * 可选：自定义 OkHttpClient.
     * 生产环境用默认（15s connect / 60s read），测试用 MockWebServer 时注入.
     */
    private val httpClient: OkHttpClient = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val tag = "LightweightLlm"

    /**
     * 调 LLM，返回 [LlmResponse].
     *
     * @param messages       对话历史（含 system / user / assistant / tool 消息）
     * @param skills         SKILL.md 解析后的工具描述（喂给 LLM）
     * @param temperature    覆盖配置温度（null 则使用 config.temperature）
     * @param retryAttempts  最大重试次数（瞬态错误时）
     * @param retryBaseDelayMs 重试基础延迟（指数退避，带 jitter）
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        skills: List<SkillSpec>,
        temperature: Double? = null,
        retryAttempts: Int = 3,
        retryBaseDelayMs: Long = 500,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val effectiveTemp = temperature ?: config.temperature
        var lastError: Exception? = null

        for (attempt in 0..retryAttempts) {
            try {
                val requestBody = buildRequestBody(messages, skills, effectiveTemp)
                val request = Request.Builder()
                    .url(config.apiUrl)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: throw LlmException("Empty response body")
                    if (!response.isSuccessful) {
                        val isTransient = response.code == 429 || response.code >= 500
                        if (isTransient && attempt < retryAttempts) {
                            val delayMs = computeBackoff(attempt, retryBaseDelayMs)
                            Log.w(tag, "LLM HTTP ${response.code} (attempt ${attempt + 1}/${retryAttempts + 1}), retrying in ${delayMs}ms")
                            delay(delayMs)
                            return@use null
                        }
                        throw LlmException("LLM HTTP ${response.code}: $body")
                    }
                    return@withContext parseResponse(body)
                }
            } catch (e: Exception) {
                lastError = e
                if (e is LlmException && !isTransientError(e) && attempt >= retryAttempts) {
                    throw e
                }
                if (attempt < retryAttempts) {
                    val delayMs = computeBackoff(attempt, retryBaseDelayMs)
                    Log.w(tag, "LLM call failed (attempt ${attempt + 1}/${retryAttempts + 1}): ${e.message}, retrying in ${delayMs}ms")
                    delay(delayMs)
                }
            }
        }
        throw lastError ?: LlmException("LLM call failed after $retryAttempts retries")
    }

    private fun isTransientError(e: LlmException): Boolean {
        val msg = e.message ?: return false
        return msg.contains("HTTP 429") || msg.contains("HTTP 5") ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("connection", ignoreCase = true) ||
                msg.contains("reset", ignoreCase = true)
    }

    private fun computeBackoff(attempt: Int, baseDelayMs: Long): Long {
        val raw = (baseDelayMs * 2.0.pow(attempt)).toLong().coerceAtMost(30_000L)
        val jitterFactor = 0.75 + Random.nextDouble() * 0.25
        return (raw * jitterFactor).toLong().coerceAtLeast(0)
    }

    private fun buildRequestBody(messages: List<ChatMessage>, skills: List<SkillSpec>, temperature: Double): String {
        val json = JSONObject()
        json.put("model", config.model)
        json.put("temperature", temperature)
        json.put("max_tokens", config.maxTokens)

        // 消息列表
        val msgArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            when (msg) {
                is ChatMessage.System -> {
                    msgObj.put("role", "system")
                    msgObj.put("content", msg.content)
                }
                is ChatMessage.User -> {
                    msgObj.put("role", "user")
                    // 归一化图片列表:images 优先,否则用单图 imageBase64(向后兼容)
                    val allImages: List<String> = msg.images
                        ?: msg.imageBase64?.let { listOf(it) }
                        ?: emptyList()
                    if (allImages.isNotEmpty()) {
                        // 多模态格式:content 是数组,先放文本再放每张图
                        val contentArray = JSONArray()
                        val textObj = JSONObject()
                        textObj.put("type", "text")
                        textObj.put("text", msg.content)
                        contentArray.put(textObj)
                        for (img in allImages) {
                            val imageObj = JSONObject()
                            imageObj.put("type", "image_url")
                            val imageUrlObj = JSONObject()
                            imageUrlObj.put("url", "data:image/jpeg;base64,$img")
                            imageObj.put("image_url", imageUrlObj)
                            contentArray.put(imageObj)
                        }
                        msgObj.put("content", contentArray)
                    } else {
                        msgObj.put("content", msg.content)
                    }
                }
                is ChatMessage.Assistant -> {
                    msgObj.put("role", "assistant")
                    msgObj.put("content", msg.content ?: "")
                    if (msg.toolCalls.isNotEmpty()) {
                        val tcArray = JSONArray()
                        for (tc in msg.toolCalls) {
                            val tcObj = JSONObject()
                            tcObj.put("id", tc.id)
                            tcObj.put("type", "function")
                            val fnObj = JSONObject()
                            fnObj.put("name", tc.name)
                            // LLM 工具调用的参数必须是合法 JSON 字符串
                            fnObj.put("arguments", JSONObject(tc.args).toString())
                            tcObj.put("function", fnObj)
                            tcArray.put(tcObj)
                        }
                        msgObj.put("tool_calls", tcArray)
                    }
                }
                is ChatMessage.Tool -> {
                    msgObj.put("role", "tool")
                    msgObj.put("tool_call_id", msg.toolCallId)
                    msgObj.put("content", msg.content)
                }
            }
            msgArray.put(msgObj)
        }
        json.put("messages", msgArray)

        // 工具列表（OpenAI tools 格式）
        if (skills.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (skill in skills) {
                val toolObj = JSONObject()
                toolObj.put("type", "function")
                val fnObj = JSONObject()
                fnObj.put("name", skill.id)
                fnObj.put("description", skill.description)
                fnObj.put("parameters", skill.parametersSchema)  // 已是 JSON Object
                toolObj.put("function", fnObj)
                toolsArray.put(toolObj)
            }
            json.put("tools", toolsArray)
            json.put("tool_choice", "auto")
        }

        return json.toString()
    }

    private fun parseResponse(body: String): LlmResponse {
        return try {
            val root = JSONObject(body)
            val choice = root.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            // content 为空时回退到 reasoning_content：推理型模型(如 mimo-v2.5)有时只产出思考、
            // content 留空，此时思考内容即为可用答案，强于直接丢空。
            val content = message.optString("content", "").takeIf { it.isNotEmpty() }
                ?: message.optString("reasoning_content", "").takeIf { it.isNotEmpty() }
            val toolCalls = mutableListOf<ToolCall>()

            message.optJSONArray("tool_calls")?.let { tcArray ->
                for (i in 0 until tcArray.length()) {
                    val tc = tcArray.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    val argsStr = fn.optString("arguments", "{}")
                    val args = parseJsonArgs(argsStr)
                    toolCalls.add(
                        ToolCall(
                            id = tc.getString("id"),
                            name = fn.getString("name"),
                            args = args
                        )
                    )
                }
            }

            val usage = root.optJSONObject("usage")?.let {
                TokenUsage(
                    promptTokens = it.optInt("prompt_tokens", 0),
                    completionTokens = it.optInt("completion_tokens", 0),
                    totalTokens = it.optInt("total_tokens", 0)
                )
            }

            // OpenAI 的 finish_reason 合法值: stop / length / tool_calls / content_filter / function_call
            // 未知值映射为 "stop" 防止下游分支遗漏
            val rawFinish = choice.optString("finish_reason", "stop")
            val finishReason = when (rawFinish) {
                "stop", "length", "tool_calls", "content_filter", "function_call" -> rawFinish
                else -> "stop"
            }

            LlmResponse(
                content = content,
                toolCalls = toolCalls,
                usage = usage,
                finishReason = finishReason
            )
        } catch (e: Exception) {
            throw LlmException("Failed to parse LLM response: ${e.message}", e)
        }
    }

    private fun parseJsonArgs(argsStr: String): Map<String, Any?> {
        return try {
            val obj = JSONObject(argsStr)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, obj.get(k))
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "parseJsonArgs failed: $argsStr", e)
            emptyMap()
        }
    }
}

/** LLM 配置. */
data class LlmConfig(
    val apiUrl: String,            // e.g. "https://api.deepseek.com/v1/chat/completions"
    val apiKey: String,
    val model: String,             // e.g. "deepseek-chat" / "qwen-turbo" / "gpt-4o-mini"
    val temperature: Double = 0.0,
    val maxTokens: Int = 2048
) {
    companion object {
        /** 几个常见厂商的预设. */
        fun deepSeek(apiKey: String) = LlmConfig(
            apiUrl = "https://api.deepseek.com/v1/chat/completions",
            apiKey = apiKey,
            model = "deepseek-chat"
        )
        fun qwen(apiKey: String) = LlmConfig(
            apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            apiKey = apiKey,
            model = "qwen-turbo"
        )
        fun openAi(apiKey: String, model: String = "gpt-4o-mini") = LlmConfig(
            apiUrl = "https://api.openai.com/v1/chat/completions",
            apiKey = apiKey,
            model = model
        )
        fun ollama(model: String = "qwen2.5:7b") = LlmConfig(
            apiUrl = "http://10.0.2.2:11434/v1/chat/completions",  // Android emulator -> host
            apiKey = "ollama",  // Ollama 不需要 key
            model = model
        )
    }
}

/** LLM 调用异常. */
class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
