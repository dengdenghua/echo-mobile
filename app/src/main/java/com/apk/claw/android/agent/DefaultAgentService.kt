package com.apk.claw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.graphics.Bitmap
import android.util.Base64
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.TaskOrchestrator
import com.apk.claw.android.agent.langchain.LangChain4jToolBridge
import com.apk.claw.android.agent.llm.LlmClient
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.agent.llm.LlmResponse
import com.apk.claw.android.agent.llm.StreamingListener
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.ApiKeyPool
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.octopus_mobile.GoalVerifier
import com.apk.claw.android.octopus_mobile.VisionAnalyzer
import com.apk.claw.android.octopus_mobile.ActionRecorder
import com.apk.claw.android.octopus_mobile.persona.PersonaPromptBuilder
import com.apk.claw.android.octopus_mobile.persona.PersonaStore
import com.apk.claw.android.octopus_mobile.safety.ErrorClassifier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.impl.GetScreenInfoTool
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /** LLM API 调用失败时的最大重试次数 */
        private const val MAX_API_RETRIES = 3
        /** 死循环检测：滑动窗口大小 */
        private const val LOOP_DETECT_WINDOW = 4
        /** 死循环检测：连续触发 N 次后强制 finish，避免无限消耗迭代 */
        private const val MAX_LOOP_WARNINGS = 3
        // 目标自校验：LLM 宣称完成后，用 VLM 看屏确认是否真达成；未达成时最多再修复几轮。
        private const val MAX_GOAL_REPAIRS = 2

        /** VLM 目标校验硬超时(ms):防止 VLM 网络挂起无限阻塞 Agent 执行线程。略高于 VLM HTTP 的 30s callTimeout。 */
        private const val VLM_VERIFY_TIMEOUT_MS = 35_000L

        /** VLM 校验等待轮询间隔(ms):executor 线程每 200ms 检查一次 cancelToken,及时响应取消。 */
        private const val POLL_INTERVAL_MS = 200L

        /** VLM 校验超时后的宽限时间(ms):给 VLM 线程收尾,避免线程泄漏。 */
        private const val POLL_GRACE_MS = 2_000L

        /** base64 图片最大宽度，超过则等比缩放 */
        private const val VISION_MAX_WIDTH = 720
        /** JPEG 压缩质量，用于 VLM 图片 */
        private const val VISION_JPEG_QUALITY = 50

        /** 非流式 LLM chat 调用的硬超时(ms):FutureTask.get 兜底,防止网络挂起永久阻塞。 */
        private const val LLM_CHAT_TIMEOUT_MS = 120_000L

        /** 是否将网络请求/响应原始数据输出到沙盒缓存文件，方便调试 */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null

        /** 流式连续超时 N 次后降级到非流式(某些代理/CDN 不支持 SSE). */
        private const val STREAMING_DEGRADE_THRESHOLD = 2
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    /**
     * 当前 LLM 客户端实际使用的 API Key(可能与 [config].apiKey 不同 —— 当 [ApiKeyPool] 启用且
     * 主 key 故障时,会切换到池中其他 key 并重建 llmClient)。在 [chatWithRetry] 中用于上报
     * 成功/失败到 ApiKeyPool,以驱动冷却/禁用/统计。
     */
    @Volatile
    private var currentApiKey: String = ""
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    /** 后台单线程执行器:异步写 TaskCheckpoint(MMKV 同步写盘),避免阻塞 Agent executor 线程。 */
    private var checkpointExecutor: ExecutorService? = null
    /**
     * 只读工具并行执行线程池。
     *
     * 同一轮 ReAct 中,LLM 可能同时发起多个 get_xxx / list_xxx / search_xxx / read_xxx 查询,
     * 这些工具无副作用可安全并行执行,显著降低响应延迟。
     * 线程数 4(保守,避免大量并发工具压垮 Shizuku/ContentProvider/MediaProjection 等系统资源)。
     * Agent 任务结束统一 shutdown。
     */
    private var toolParallelExecutor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    @Volatile
    private var cancelToken: CancellationToken = CancellationToken()

    /**
     * 流式降级:连续 [STREAMING_DEGRADE_THRESHOLD] 次流式超时后,后续调用全部降级到非流式.
     * 某些代理/CDN 不支持 SSE,反复流式超时浪费时间.降级是单向的(本次任务内不恢复).
     */
    @Volatile
    private var streamingDegraded = false
    private val streamingTimeoutCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun initialize(config: AgentConfig) {
        this.config = config
        // ApiKeyPool 启用时,优先用池中当前可用 key 构造客户端(可能是主 key,也可能是冷却后切到的 fallback)。
        // 池禁用 / 池为空 / 池只有主 key 时,acquireKey 返回 KVUtils.getLlmApiKey()(与 config.apiKey 一致),
        // 行为完全等价于"未接入池"。
        val poolKey = if (ApiKeyPool.isEnabled()) ApiKeyPool.acquireKey() else config.apiKey
        currentApiKey = poolKey
        val effectiveConfig = if (poolKey != config.apiKey) config.copy(apiKey = poolKey) else config
        this.llmClient = LlmClientFactory.create(effectiveConfig)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        this.checkpointExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "checkpoint").apply { isDaemon = true }
        }
        this.toolParallelExecutor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "tool-parallel").apply { isDaemon = true }
        }
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}, poolEnabled=${ApiKeyPool.isEnabled()}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        checkpointExecutor?.shutdownNow()
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    /** 本次运行是否来自不可信来源（LAN 网页控制台 / 聊天渠道）。 */
    @Volatile
    private var untrustedRun = false

    /**
     * 本次运行的会话级工作空间(类似 Codex --cd 选定项目目录)。
     * 非空时覆盖全局脚本工作空间,run_code/run_python 的 WORKSPACE 全局变量切到此处。
     * 由 [executeTask] 设置,工具调用时通过 [ToolRegistry.withWorkspace] 注入 ThreadLocal。
     */
    @Volatile
    private var workspaceOverride: String? = null

    /**
     * 动作录制器：Agent 执行任务时录制 UI 动作序列（锚点文字/viewId，非死坐标），
     * 任务成功后存入 [com.apk.claw.android.octopus_mobile.ActionCache]，供下次同指令
     * 快路径确定性重放——跳过 LLM 推理，实现"自进化 RPA"。
     */
    private var actionRecorder: ActionRecorder? = null

    /**
     * 工具调用频率计数器（包名 → 调用次数），用于自进化：当某个 open_app 模式
     * 被高频使用（≥3 次），自动生成一条 ReflexRouter 快路径规则，跳过 LLM 推理。
     */
    private val toolCallFrequency = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 不可信来源运行时，把工具调用包进来源闸门：高危工具默认拦截，满血/远程放行时通过。 */
    private fun execTool(toolName: String, params: Map<String, Any>): com.apk.claw.android.tool.ToolResult {
        val reg = ToolRegistry.getInstance()
        // 会话级工作空间注入 ThreadLocal —— run_code/run_python 通过 currentWorkspace() 读取。
        // 类似 Codex --cd 选定项目目录,影响 ScriptSandbox/PythonSandbox 的 WORKSPACE 全局变量。
        return ToolRegistry.withWorkspace(workspaceOverride) {
            if (untrustedRun) {
                ToolRegistry.withUntrustedSource { reg.executeTool(toolName, params, cancelToken) }
            } else {
                reg.executeTool(toolName, params, cancelToken)
            }
        }
    }

    override fun setWorkspace(workspace: String?) {
        workspaceOverride = workspace?.takeIf { it.isNotBlank() }
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback, untrusted: Boolean) {
        // 未 initialize() 就调用会让 executor 为 null；用 ?.submit 会静默吞掉任务，
        // 导致 running 永远卡 true。这里显式拦截并回报错误（在抢占 running 之前）。
        val exec = executor
        if (exec == null) {
            callback.onError(0, IllegalStateException("Agent not initialized — call initialize() first"), 0)
            return
        }
        // CAS 抢占 running：get 再 set 存在 TOCTOU，两个并发调用（或与 resumeTask 竞争）
        // 可同时通过检查并各自 submit，导致同一任务被重复执行。
        if (!running.compareAndSet(false, true)) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        cancelToken = CancellationToken()
        untrustedRun = untrusted

        try {
            exec.submit {
                try {
                    runAgentLoop(userPrompt, callback)
                } catch (e: Exception) {
                    XLog.e(TAG, "Agent execution error", e)
                    callback.onError(0, e, 0)
                } finally {
                    running.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // executor 已 shutdown（如配置更新竞态）：复位状态并回报，避免卡死。
            running.set(false)
            callback.onError(0, e, 0)
        }
    }

    override fun resumeTask(callback: AgentCallback): Boolean {
        val checkpoint = TaskCheckpoint.load() ?: return false
        val exec = executor
        if (exec == null) {
            XLog.w(TAG, "Cannot resume: agent not initialized")
            return false
        }
        // CAS 抢占 running：与 executeTask 共享同一标志，get 再 set 存在 TOCTOU，
        // 两个并发 resume（或 resume 与 executeTask）可同时通过并重复执行同一任务。
        if (!running.compareAndSet(false, true)) {
            XLog.w(TAG, "Cannot resume: agent is already running")
            return false
        }

        cancelToken = CancellationToken()
        untrustedRun = checkpoint.untrusted

        XLog.i(TAG, "Resuming task from checkpoint: goal='${checkpoint.goal.take(40)}...', " +
            "iterations=${checkpoint.iterations}, messages=${checkpoint.messages.size}")

        exec.submit {
            try {
                runAgentLoopFromCheckpoint(checkpoint, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Agent resume error", e)
                callback.onError(0, e, 0)
            } finally {
                running.set(false)
            }
        }
        return true
    }

    // ==================== VLM 视觉理解 ====================

    /**
     * 系统弹窗阻断时，截图让 VLM 分析弹窗内容.
     *
     * @param messages 当前对话历史
     * @return true 表示 VLM 已分析并添加了指导消息，可以继续执行；false 表示无法处理
     */
    private fun handleSystemDialogWithVision(
        messages: MutableList<ChatMessage>,
        callback: AgentCallback,
        iterations: Int
    ): Boolean {
        // 1. 截图
        val service = ClawAccessibilityService.getInstance()
        if (service == null) {
            XLog.w(TAG, "Accessibility service not available for VLM screenshot")
            return false
        }

        val bitmap: Bitmap = service.takeScreenshot(5000) ?: run {
            XLog.w(TAG, "Failed to take screenshot for VLM analysis")
            return false
        }

        var scaledBitmap: Bitmap? = null  // 缩放产物(需在 finally 回收);null=未缩放
        try {
            // 2. 缩放并压缩为 JPEG base64
            if (bitmap.width > VISION_MAX_WIDTH) {
                val scale = VISION_MAX_WIDTH.toFloat() / bitmap.width
                val newHeight = Math.round(bitmap.height * scale)
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, VISION_MAX_WIDTH, newHeight, true)
                // 原图不再需要,立即回收释放内存;后续只用 scaledBitmap
                bitmap.recycle()
            }

            val compressTarget = scaledBitmap ?: bitmap
            val baos = ByteArrayOutputStream()
            compressTarget.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, baos)
            val base64Str = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // 3. 构建包含图片的 UserMessage，让 LLM 分析弹窗
            val visionMessage = UserMessage.from(
                TextContent.from(
                    "[系统提示] 检测到系统弹窗阻断了操作。请分析截图中的弹窗内容，判断弹窗类型并决定如何处理。" +
                    "如果是可以关闭的弹窗（广告、权限请求、更新提示、协议等），请在下一轮操作中关闭它。" +
                    "如果是需要用户介入的弹窗（登录、付费等），请调用 finish 说明原因。" +
                    "如果无法判断弹窗内容，也请调用 finish 说明。"
                ),
                ImageContent.from("data:image/jpeg;base64,$base64Str")
            )
            messages.add(visionMessage)

            // 4. 调用 LLM 分析弹窗
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "VLM analysis failed", e)
                return false
            }

            // 5. 将 LLM 的分析结果添加到消息历史
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // 推送 VLM 分析内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果 LLM 决定调用工具（如点击关闭按钮），说明它可以处理
            if (llmResponse.hasToolExecutionRequests()) {
                XLog.i(TAG, "VLM decided to handle the dialog with tool calls")
                // 执行 LLM 决定的工具调用
                for (toolRequest in llmResponse.toolExecutionRequests) {
                    if (cancelToken.isCancelled()) return false

                    val toolName = toolRequest.name() ?: ""
                    val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                    val toolArgs = toolRequest.arguments() ?: "{}"
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)

                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val params: Map<String, Any> = try {
                        GSON.fromJson(toolArgs, mapType) ?: emptyMap()
                    } catch (e: Exception) {
                        XLog.w(TAG, "VLM tool args parse failed for $toolName: ${e.message}")
                        emptyMap()
                    }

                    val toolResult = execTool(toolName, params)
                    val paramsString = if (params.isEmpty()) "" else params.toString()
                    callback.onToolResult(iterations, toolName, displayName, paramsString, toolResult)

                    // 添加工具结果到消息（排除 imageBase64 以节省 token）
                    val resultJson = GSON.toJson(toolResultForJson(toolResult))
                    messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))

                    // 如果 LLM 调用了 finish，说明它认为无法处理
                    if (toolName == "finish") {
                        XLog.i(TAG, "VLM decided to finish task")
                        return false
                    }
                }
                return true
            }

            // LLM 没有调用工具，只返回了文本分析，继续让主循环处理
            XLog.i(TAG, "VLM returned text analysis without tool calls")
            return true

        } catch (e: Exception) {
            XLog.e(TAG, "Error during VLM analysis", e)
            return false
        } finally {
            // 回收缩放产物(若产生了);若未缩放,bitmap 在这里统一回收。
            // 修复:原实现 no-scale 成功路径不回收 bitmap,且 scale 路径异常会泄漏 scaledBitmap。
            if (scaledBitmap != null && !scaledBitmap!!.isRecycled) scaledBitmap!!.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ==================== 环境预检 ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== 设备上下文 ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## 设备信息\n")
        sb.append("- 品牌: ").append(Build.BRAND).append("\n")
        sb.append("- 型号: ").append(Build.MODEL).append("\n")
        sb.append("- Android 版本: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- 屏幕分辨率: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- 已注册工具数: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get application label", e)
            "CoPaw"
        }
        sb.append("\n## 本应用信息\n")
        sb.append("- 应用名: ").append(appName).append("\n")
        sb.append("- 包名: ").append(app.packageName).append("\n")
        sb.append("- 当用户提到'自己/本应用/这个应用'时，指的就是上述应用\n")

        return sb.toString()
    }

    // ==================== LLM 调用（带重试） ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        AgentMetrics.llmCall()
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelToken.isCancelled()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                // 流式降级:连续超时后改走非流式(某些代理/CDN 不支持 SSE)
                val useStreaming = config.streaming && !streamingDegraded
                val response = if (useStreaming) {
                    try {
                        llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                            override fun onPartialText(token: String) {
                                callback.onContent(iteration, token)
                            }
                            override fun onComplete(response: LlmResponse) {}
                            override fun onError(error: Throwable) {}
                        }, cancelToken)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        // 流式超时:计数并判断是否降级
                        val count = streamingTimeoutCount.incrementAndGet()
                        if (count >= STREAMING_DEGRADE_THRESHOLD && !streamingDegraded) {
                            streamingDegraded = true
                            AgentMetrics.streamingDegraded()
                            XLog.w(TAG, "Streaming degraded to non-streaming after $count timeouts")
                        }
                        throw e
                    }
                } else {
                    // 非流式 chat 不接受 CancellationToken,用 FutureTask + 独立线程包装,
                    // poll 等待以便在 cancelToken 取消时 future.cancel(true) 中断底层 HTTP 调用。
                    chatWithCancellation(messages)
                }
                // 流式成功,重置超时计数
                streamingTimeoutCount.set(0)
                // 网关上游 5xx 常表现为 HTTP 200、但流式 body 是 {"error":...}，被解析层吞成"空回复"
                // （无正文、无工具调用）。把它当作可重试的瞬时错误，复用下方分类重试，而不是误判为"任务已完成"。
                if (response.text.isNullOrEmpty() && !response.hasToolExecutionRequests()) {
                    throw RuntimeException(ClawApplication.instance.getString(R.string.agent_empty_response))
                }
                // 成功上报到 ApiKeyPool(重置 consecutiveFailures,池禁用时 no-op)
                if (ApiKeyPool.isEnabled()) ApiKeyPool.reportSuccess(currentApiKey)
                return response
            } catch (e: Exception) {
                lastException = e
                if (cancelToken.isCancelled()) {
                    throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
                }

                // ── ErrorClassifier 智能重试 ──
                // 按错误类型决定退避策略，替代旧的硬编码指数退避。
                val statusCode = extractStatusCode(e)
                val classification = ErrorClassifier.classify(e, statusCode, config.provider.name)
                XLog.w(TAG, "LLM API error: ${classification.category} (action=${classification.action}), " +
                    "attempt ${attempt + 1}/$MAX_API_RETRIES: ${e.message}")

                // 不可重试的错误：认证失败/额度不足、内容过滤、未知错误
                if (!classification.isRetryable) {
                    throw e
                }
                // SWITCH_KEY:认证失败 / 限流 → 切到池中其他 key 后立即重试(无 backoff)。
                // 池禁用 / 池无备选 key 时退化为旧行为(直接抛出)。
                if (classification.action == ErrorClassifier.RecoveryAction.SWITCH_KEY) {
                    if (!ApiKeyPool.isEnabled()) throw e
                    ApiKeyPool.reportFailure(currentApiKey, statusCode)
                    val newKey = ApiKeyPool.acquireKey()
                    if (newKey == currentApiKey) {
                        XLog.w(TAG, "SWITCH_KEY but no alternative key available in pool")
                        throw e
                    }
                    rebuildLlmClientWithKey(newKey)
                    XLog.w(TAG, "Switched API key (status=$statusCode), retrying without backoff")
                    continue
                }
                // 非 SWITCH_KEY 的 HTTP 错误(429/5xx 等)同样上报池以驱动冷却统计
                if (ApiKeyPool.isEnabled() && statusCode != null) {
                    ApiKeyPool.reportFailure(currentApiKey, statusCode)
                }
                // CONTEXT_LENGTH：压缩上下文后立即重试（backoff=0）
                if (classification.action == ErrorClassifier.RecoveryAction.REDUCE_CONTEXT) {
                    XLog.i(TAG, "Context too long, aggressive compression before retry")
                    // compressHistoryForSend 已在 callLlm 每轮调用前执行；
                    // 此处额外触发一次更激进的截断，丢弃更早的历史。
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        compressHistoryForSend(messages as MutableList<ChatMessage>)
                    }
                }

                val delayMs = (classification.backoffSec * 1000).toLong()
                    .coerceAtLeast(500) // 至少 500ms，避免立即重试打满 API
                val jitter = (Math.random() * 500).toLong()
                XLog.w(TAG, "Retrying in ${delayMs + jitter}ms (${classification.message})")
                if (!cancelToken.sleepInterruptible(delayMs + jitter)) {
                    throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
                }
            }
        }
        throw lastException ?: RuntimeException("LLM call failed after $MAX_API_RETRIES retries")
    }

    /**
     * 非流式 LLM chat 的可取消包装:用 FutureTask + 独立 daemon 线程执行 [llmClient.chat],
     * executor 线程 poll 等待(每 [POLL_INTERVAL_MS] 检查一次 [cancelToken]),
     * 取消时 future.cancel(true) 中断底层 HTTP 调用,带 [LLM_CHAT_TIMEOUT_MS] 硬超时兜底。
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun chatWithCancellation(messages: List<ChatMessage>): LlmResponse {
        val future = java.util.concurrent.FutureTask {
            llmClient.chat(messages, toolSpecs)
        }
        Thread(future, "llm-chat").apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + LLM_CHAT_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                if (cancelToken.isCancelled()) {
                    future.cancel(true)
                    throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
                }
                try {
                    return future.get(POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    // 继续轮询,下一轮检查 cancelToken
                }
            }
            future.cancel(true)
            throw RuntimeException("LLM chat timed out after ${LLM_CHAT_TIMEOUT_MS}ms")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
        }
    }

    /** 从异常中提取 HTTP 状态码（LangChain4j HttpException 或消息中的状态码）。 */
    private fun extractStatusCode(e: Throwable): Int? {
        if (e is dev.langchain4j.exception.HttpException) {
            return e.statusCode()
        }
        return null
    }

    /**
     * 用新 API Key 重建 LlmClient(用于 ApiKeyPool 切换 key)。
     *
     * 仅替换 [llmClient] 与 [currentApiKey],[config] 保持原样(下次 initialize 仍用主 key 起步)。
     * 重建成本低:仅构造 OkHttp + ChatModel,无网络握手。线程安全:llmClient 用 volatile 写,
     * 与 executor 单线程串行调用 LLM 的语义一致(无并发请求)。
     */
    private fun rebuildLlmClientWithKey(newKey: String) {
        val effectiveConfig = config.copy(apiKey = newKey)
        this.llmClient = LlmClientFactory.create(effectiveConfig)
        this.currentApiKey = newKey
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** 上下文总字符上限：超过则对保护区外的 AiMessage 文本做截断 */
    private val CONTEXT_MAX_CHARS = 80000

    /** 保护区外 AiMessage 文本截断长度 */
    private val CONTEXT_CHUNK_TRUNCATE_CHARS = 500

    /** 保护区：最近 N 轮完整保留 */
    private val KEEP_RECENT_ROUNDS = 3

    /** 大输出观察类工具 → 压缩后占位符 */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[屏幕信息已省略]",
        "take_screenshot" to "[截图结果已省略]",
        "find_node_info" to "[节点查找结果已省略]",
        "get_installed_apps" to "[应用列表已省略]",
        "scroll_to_find" to "[滚动查找结果已省略]",
        "preview_html" to "[HTML预览截图已省略]",
        "browser_screenshot" to "[浏览器截图已省略]",
    )

    /**
     * 发送前压缩历史消息，节省 input token：
     * - get_screen_info：全局只保留最新一条完整结果
     * - 保护区（最近 KEEP_RECENT_ROUNDS 轮）：完整保留
     * - 保护区外：AI thinking 不动，tool result 压缩为一行摘要
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数
        val charsBefore = countMessagesChars(messages)
        val msgCountBefore = messages.size

        // 0. get_screen_info 特殊处理：无视分级，全局只保留最新一条完整结果
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 0.5. 自动截屏 UserMessage 特殊处理：全局只保留最新一条，旧截图替换为文本占位符。
        // 截图 base64 体积大，历史中积攒多张会迅速膨胀 token 用量。
        val lastAutoScreenshotIdx = messages.indexOfLast { msg ->
            msg is UserMessage && msg.singleText().startsWith("[自动截屏]")
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is UserMessage
                && msg.singleText().startsWith("[自动截屏]")
                && i != lastAutoScreenshotIdx
            ) {
                messages[i] = UserMessage.from("[早期自动截屏已省略]")
            }
        }

        // 1. 找出所有 AiMessage 的索引，每个代表一轮
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // 保护区

            val aiIndex = aiIndices[roundIdx]

            // 收集本轮的 ToolExecutionResultMessage 索引
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // 压缩后统计
        val charsAfter = countMessagesChars(messages)
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }

        // ── ContextCompressor 二次压缩层 ──
        // 如果现有压缩后总字符仍超过 maxChars，对保护区外的 AiMessage 文本做截断
        if (charsAfter > CONTEXT_MAX_CHARS && aiIndices.size > KEEP_RECENT_ROUNDS) {
            applyContextCompressorTruncation(messages, aiIndices, KEEP_RECENT_ROUNDS)
            val charsAfterSecondPass = countMessagesChars(messages)
            val secondSaved = charsAfter - charsAfterSecondPass
            if (secondSaved > 0) {
                val ratio = (charsAfterSecondPass.toFloat() / charsAfter * 100).toInt()
                XLog.i(TAG, "ContextCompressor 二次压缩: ${charsAfter}→${charsAfterSecondPass}字符 (${ratio}%)")
            }
        }
    }

    /** 统计消息列表总字符数（抽取自重复代码） */
    private fun countMessagesChars(messages: List<ChatMessage>): Int =
        messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }

    /**
     * ContextCompressor 二次压缩：对保护区外的 UserMessage 文本做截断.
     * 这是对现有 tool result 压缩的补充层。
     */
    private fun applyContextCompressorTruncation(
        messages: MutableList<ChatMessage>,
        aiIndices: List<Int>,
        keepRecent: Int
    ) {
        val totalRounds = aiIndices.size
        val truncLimit = CONTEXT_CHUNK_TRUNCATE_CHARS

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= keepRecent) break // 保护区不动

            val aiIndex = aiIndices[roundIdx]
            // 截断本轮 AiMessage 的文本部分（不动 toolExecutionRequests）
            val aiMsg = messages[aiIndex] as AiMessage
            val aiText = aiMsg.text() ?: ""
            if (aiText.length > truncLimit) {
                val truncated = aiText.take(truncLimit) + "...[compressed]"
                messages[aiIndex] = if (aiMsg.toolExecutionRequests()?.isNotEmpty() == true) {
                    AiMessage.from(truncated, aiMsg.toolExecutionRequests())
                } else {
                    AiMessage.from(truncated)
                }
            }
        }
    }

    /** 压缩 Tool Result：观察类工具用占位符，其他工具截取摘要 */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // 已足够简短，无需压缩

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // 其他工具：解析 JSON 提取摘要
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** 将 ToolResult JSON 压缩为一行摘要 */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (e: Exception) {
            XLog.w(TAG, "summarizeToolResult failed", e)
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== 主执行循环 ====================

    private class AgentLoopState(
        val messages: MutableList<ChatMessage>,
        val maxIterations: Int,
        /** 本次任务的自然语言目标，供完成时做 VLM 目标自校验。 */
        val goal: String,
    ) {
        var iterations = 0
        var totalTokens = 0
        var loopWarningCount = 0
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        /** 上一轮自动截图的 bitmap hash,用于检测屏幕是否变化(省流). */
        var lastScreenshotHash = 0
        /** 剩余目标修复轮数（VLM 判未达成时消耗）。 */
        var goalRepairsLeft = MAX_GOAL_REPAIRS
        /** 任务是否成功完成:仅 LLM 正常 finish 或目标校验通过时置 true,finishLoop 据此决定是否 commit 动作录制。 */
        var taskSucceeded = false
    }

    private enum class IterationOutcome { CONTINUE, TERMINATE }
    private enum class ToolHandleResult { CONTINUE, SKIP_REMAINING, TERMINATE }

    private fun AgentLoopState.shouldContinue(): Boolean =
        iterations < maxIterations && !cancelToken.isCancelled()

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        actionRecorder = ActionRecorder()
        val state = AgentLoopState(buildInitialMessages(userPrompt), config.maxIterations, userPrompt)
        if (!config.skipCheckpoint) saveCheckpoint(state)
        while (state.shouldContinue()) {
            state.iterations++
            callback.onLoopStart(state.iterations)
            if (state.runSingleIteration(callback) == IterationOutcome.TERMINATE) break
            if (!config.skipCheckpoint) saveCheckpoint(state)
        }
        finishLoop(state, callback)
    }

    private fun runAgentLoopFromCheckpoint(checkpoint: TaskCheckpoint.CheckpointData, callback: AgentCallback) {
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val state = AgentLoopState(checkpoint.messages.toMutableList(), config.maxIterations, checkpoint.goal)
        state.iterations = checkpoint.iterations
        state.goalRepairsLeft = checkpoint.goalRepairsLeft
        XLog.i(TAG, "Resumed from checkpoint at iteration ${state.iterations}, ${state.messages.size} messages")
        while (state.shouldContinue()) {
            state.iterations++
            callback.onLoopStart(state.iterations)
            if (state.runSingleIteration(callback) == IterationOutcome.TERMINATE) break
            saveCheckpoint(state)
        }
        finishLoop(state, callback)
    }

    /** 保存检查点到持久化存储，供崩溃恢复。异步提交到 [checkpointExecutor],不阻塞 Agent 线程。 */
    private fun saveCheckpoint(state: AgentLoopState) {
        val exec = checkpointExecutor ?: return
        val goal = state.goal
        val iterations = state.iterations
        val goalRepairsLeft = state.goalRepairsLeft
        val untrusted = untrustedRun
        val messages = state.messages
        exec.submit {
            TaskCheckpoint.save(
                goal = goal,
                iterations = iterations,
                goalRepairsLeft = goalRepairsLeft,
                untrusted = untrusted,
                messages = messages,
            )
        }
    }

    /**
     * 同步等待 [checkpointExecutor] 中所有已提交任务完成(最多 2 秒)。
     * 在 cancel/finishLoop 清除检查点前调用,确保不会有滞后的异步写盘在 clear 之后落盘。
     */
    private fun flushCheckpointExecutor() {
        try {
            checkpointExecutor?.submit { }?.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            XLog.w(TAG, "checkpoint executor flush timed out", e)
        }
    }

    private fun buildInitialMessages(userPrompt: String): MutableList<ChatMessage> {
        // GUI 交互经验:把这台设备过往界面失败的规避策略追加进系统提示(每任务重算,故实时反映最新教训)。
        val guiLessons = com.apk.claw.android.octopus_mobile.InteractionLedger.getMitigationsSection()
        // 角色卡注入:读取当前激活 Persona,把 systemPrompt + styleHint 追加到系统提示尾部。
        // 不覆盖 config.systemPrompt(开发者基线),而是作为「当前角色设定」叠加,保证可逆切换。
        val persona = runCatching { PersonaStore.getActivePersona(ClawApplication.instance) }
            .getOrNull()
        val personaAppendix = PersonaPromptBuilder.buildSystemAppendix(persona)
        val fullSystemPrompt = config.systemPrompt + buildDeviceContext() +
            config.dynamicPromptSuffix + config.memoryPromptSuffix +
            (if (guiLessons.isNotBlank()) "\n\n$guiLessons" else "") +
            personaAppendix
        val msgs = mutableListOf<ChatMessage>(
            SystemMessage.from(fullSystemPrompt),
        )
        // 角色卡开场白:新对话时把 greeting 作为第一条 AI 消息注入,让用户立刻感知到角色存在。
        // 仅在 userPrompt 不是续接历史时注入(简单判断:无 checkpoint 恢复走这条路径,都有 persona 上下文)。
        if (persona != null && persona.greeting.isNotBlank()) {
            msgs.add(AiMessage.from(persona.greeting))
        }
        // few-shot: 标准工具调用流程示范(帮小模型快速对齐格式,~100 token 开销)
        // 展示 User → Assistant(tool_calls) → ToolResult → Assistant(完成) 的完整闭环
        msgs.add(UserMessage.from("[示例] 帮我打开设置"))
        msgs.add(AiMessage.from(
            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("ex1")
                .name("open_app")
                .arguments("{\"package\":\"com.android.settings\"}")
                .build()
        ))
        msgs.add(ToolExecutionResultMessage.from("ex1", "open_app", "{\"isSuccess\":true,\"data\":\"ok\"}"))
        msgs.add(AiMessage.from("已打开设置。"))
        msgs.add(UserMessage.from(userPrompt))
        return msgs
    }

    /**
     * 快速计算 bitmap 的降采样 hash:缩到 16x16 算 pixel 求和.
     * 用于检测屏幕是否变化,不追求精确(只做粗粒度去重).
     */
    private fun bitmapHash(bitmap: Bitmap): Int {
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        } catch (e: Exception) {
            return bitmap.width * 31 + bitmap.height
        }
        return try {
            var hash = 0
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    hash = hash * 31 + scaled.getPixel(x, y)
                }
            }
            hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * VLM 主力感知：截取当前屏幕并作为 UserMessage(ImageContent) 注入消息历史。
     * 让 LLM 每轮直接看到屏幕状态，而非仅依赖无障碍树文字转述。
     * 截图失败时静默跳过（不影响 Agent 循环）。
     */
    private fun AgentLoopState.injectAutoScreenshot() {
        val service = ClawAccessibilityService.getInstance() ?: return
        val bitmap = service.takeScreenshot(5000) ?: return

        // 截图变化检测:计算 bitmap hash,与上一轮比较;相同则跳过注入(省 30-50KB 流量 + 上千 token)
        val currentHash = bitmapHash(bitmap)
        if (currentHash != 0 && currentHash == lastScreenshotHash) {
            AgentMetrics.screenUnchanged()
            bitmap.recycle()
            messages.add(UserMessage.from("[自动截屏] 屏幕与上一轮相同,未重新截图。如需详细分析请调 look_at_screen。"))
            return
        }
        lastScreenshotHash = currentHash

        var scaledBitmap: Bitmap? = null
        try {
            if (bitmap.width > VISION_MAX_WIDTH) {
                val scale = VISION_MAX_WIDTH.toFloat() / bitmap.width
                val newHeight = Math.round(bitmap.height * scale)
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, VISION_MAX_WIDTH, newHeight, true)
                bitmap.recycle()
            }
            val compressTarget = scaledBitmap ?: bitmap
            val baos = ByteArrayOutputStream()
            compressTarget.compress(Bitmap.CompressFormat.JPEG, VISION_JPEG_QUALITY, baos)
            val base64Str = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            messages.add(
                UserMessage.from(
                    TextContent.from("[自动截屏] 这是当前屏幕状态，请据此决策。"),
                    ImageContent.from("data:image/jpeg;base64,$base64Str")
                )
            )
        } catch (e: Exception) {
            XLog.w(TAG, "Auto-screenshot injection failed", e)
        } finally {
            // 回收缩放产物(若产生了);原图 bitmap 也统一回收。
            // 修复:原实现 no-scale 路径不回收 bitmap,每轮累积导致 OOM。
            if (scaledBitmap != null && !scaledBitmap!!.isRecycled) scaledBitmap!!.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 每轮感知注入:VLM 主力感知每轮把当前屏喂给 LLM。
     * 仅在模型支持视觉 + enableVision + enableAutoScreenshot 均为 true 时生效。
     * 省流模式 + 本机无障碍树够丰富 → 注入树文字替代截图(省 token);否则回退 vision 截图。
     */
    private fun AgentLoopState.injectPerception() {
        if (!(config.enableAutoScreenshot && config.enableVision && llmClient.supportsVision)) return
        if (KVUtils.isFrugalPerceptionMode() && tryInjectAccessibilityTree()) return
        injectAutoScreenshot()
    }

    /**
     * 省流感知:注入当前屏无障碍树文字替代 vision 截图。成功注入返回 true(本轮跳过截图);
     * 远程目标 / 树太稀疏(游戏/Canvas/空窗)/服务未运行 → 返回 false,由调用方回退截图。
     */
    @Suppress("ReturnCount")   // 4 个都是守卫式提前返回,拆开反而更绕
    private fun AgentLoopState.tryInjectAccessibilityTree(): Boolean {
        if (ControlTarget.remoteTarget() != null) return false   // 远程仍走对端截图路径
        val service = ClawAccessibilityService.getInstance() ?: return false
        val tree = runCatching { service.screenTree }.getOrNull()
        if (!FrugalPerception.isTreeRichEnough(tree)) return false
        messages.add(
            UserMessage.from(
                TextContent.from(
                    "[当前屏幕·无障碍树] 省流模式,以下为当前屏幕结构,据此决策;" +
                        "需核对视觉细节(颜色/图标/游戏画面)时再调 look_at_screen:\n$tree"
                )
            )
        )
        return true
    }

    private fun AgentLoopState.runSingleIteration(callback: AgentCallback): IterationOutcome {
        AgentMetrics.iteration()
        injectPerception()
        val llmResponse = callLlm(callback) ?: return IterationOutcome.TERMINATE
        if (handleLlmResponse(llmResponse, callback)) return IterationOutcome.TERMINATE

        val requests = llmResponse.toolExecutionRequests
        if (requests.isEmpty()) {
            return if (handleLoopDetection(callback)) IterationOutcome.TERMINATE else IterationOutcome.CONTINUE
        }

        // ── 分组:只读工具(并行) + 其他工具(串行) ──────────────────────────
        //
        // LLM 一轮里可能同时发起多个 get_*/list_*/search_*/read_* 查询,这些纯查询无副作用
        // 可安全并行;但 tap/swipe/input_text/open_app/file_ops 等有状态副作用的工具必须串行,
        // 否则会有竞态(如两个 tap 同时执行会乱序,两个 file_ops 同时写会冲突)。
        //
        // 策略:
        //  1. 只读组(>=2 个)用 toolParallelExecutor 并行执行 execTool,结果按提交顺序串行 commit
        //  2. 只读组(1 个)直接串行执行,省线程开销
        //  3. 非只读组保持原顺序串行执行(走完整 executeSingleTool 含 UI 状态校验/动作录制等副作用)
        //
        // 并行阶段只调 execTool(纯计算/IO),messages 状态修改在主 Agent 线程上串行 commit,避免竞态。
        val (readonlyReqs, serialReqs) = requests.partition { req ->
            val name = req.name() ?: ""
            // ToolRegistry.getTool 可能返回 null(MCP 工具未连接时),null 时保守走串行
            ToolRegistry.getInstance().getTool(name)?.isReadOnly() == true
        }

        // 1) 只读组:并行执行 execTool,串行 commit 结果
        if (readonlyReqs.isNotEmpty()) {
            val commitResult = commitReadonlyToolsParallel(readonlyReqs, callback)
            when (commitResult) {
                ToolHandleResult.TERMINATE -> return IterationOutcome.TERMINATE
                ToolHandleResult.SKIP_REMAINING -> return IterationOutcome.CONTINUE
                ToolHandleResult.CONTINUE -> { }
            }
        }

        // 2) 非只读组:串行执行(保留 UI 状态校验/动作录制/导航图谱等副作用逻辑)
        for (toolRequest in serialReqs) {
            if (cancelToken.isCancelled()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                return IterationOutcome.TERMINATE
            }
            when (executeSingleTool(toolRequest, callback)) {
                ToolHandleResult.TERMINATE -> return IterationOutcome.TERMINATE
                ToolHandleResult.SKIP_REMAINING -> return IterationOutcome.CONTINUE
                ToolHandleResult.CONTINUE -> { }
            }
        }

        return if (handleLoopDetection(callback)) IterationOutcome.TERMINATE else IterationOutcome.CONTINUE
    }

    /**
     * 并行执行多个只读工具,串行 commit 结果到 messages。
     *
     * 分两阶段:
     *  1. **并行执行阶段**:所有 readonly 工具提交到 [toolParallelExecutor],
     *     每个工具独立调用 [execTool] 拿 [ToolResult]。此阶段不修改任何共享状态(messages、callback)。
     *  2. **串行 commit 阶段**:按 LLM 提交顺序依次等待 Future,在每个工具结果出来后:
     *     - callback.onToolCall / onToolResult(主线程串行)
     *     - appendToolResult 修改 messages(避免竞态)
     *     - 处理 finish/参数错误 等特殊情况
     *
     * 单个 readonly 工具时直接串行执行,省去线程池开销。
     *
     * @return TERMINATE 表示有 finish 工具触发任务完成或 cancel;CONTINUE 表示正常完成。
     */
    private fun AgentLoopState.commitReadonlyToolsParallel(
        readonlyReqs: List<ToolExecutionRequest>,
        callback: AgentCallback,
    ): ToolHandleResult {
        // 单个 readonly 工具:直接串行执行(省线程开销)
        if (readonlyReqs.size == 1) {
            return executeReadonlyTool(readonlyReqs[0], callback)
        }

        // 多个 readonly 工具:并行执行,串行 commit
        val pool = toolParallelExecutor ?: return ToolHandleResult.CONTINUE
        val cancelTask = cancelToken

        // ── 阶段 1:并行提交 execTool ──
        data class ReadonlyExec(
            val request: ToolExecutionRequest,
            val toolName: String,
            val displayName: String,
            val args: String,
            val params: Map<String, Any>?,
            val parseError: String?,  // 非 null 表示参数解析失败,params 为 null
            val result: ToolResult?,  // 参数解析失败时为 null
        )

        val futures = readonlyReqs.map { req ->
            pool.submit<ReadonlyExec> {
                val toolName = req.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = req.arguments() ?: "{}"

                // 参数解析在 worker 线程上做(纯字符串解析,无副作用)
                val params = parseToolArgs(toolName, toolArgs)
                if (params == null) {
                    val schema = formatToolSchema(toolName)
                    val err = "参数解析失败:JSON 格式有误。原始参数: $toolArgs" +
                        (if (schema.isNotEmpty()) "\n正确参数格式: $schema" else "") +
                        "\n请按此格式重新调用。"
                    return@submit ReadonlyExec(req, toolName, displayName, toolArgs, null, err, null)
                }

                // 执行工具(纯查询,无副作用;cancelToken 通过 ToolRegistry.executeTool 内部 ThreadLocal 传递)
                AgentMetrics.toolCall()
                val rawResult = execTool(toolName, params)
                if (!rawResult.isSuccess) AgentMetrics.toolFailure()
                ReadonlyExec(req, toolName, displayName, toolArgs, params, null, rawResult)
            }
        }

        // ── 阶段 2:按提交顺序串行 commit ──
        for (future in futures) {
            if (cancelTask.isCancelled()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                return ToolHandleResult.TERMINATE
            }

            val exec: ReadonlyExec = try {
                future.get()  // 阻塞等单个工具完成(按提交顺序)
            } catch (e: Exception) {
                // 线程被中断或工具抛异常:构造错误结果
                val req = readonlyReqs[futures.indexOf(future)]
                val toolName = req.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val args = req.arguments() ?: "{}"
                val errResult = ToolResult.error("工具执行异常: ${e.message}")
                callback.onToolCall(iterations, toolName, displayName, args)
                callback.onToolResult(iterations, toolName, displayName, args, errResult)
                appendToolResult(req, errResult)
                continue
            }

            // 构造最终 ToolResult
            val result: ToolResult = exec.parseError?.let { ToolResult.error(it) } ?: exec.result!!

            // UI callback(主 Agent 线程串行)
            val paramsStr = if (exec.params.isNullOrEmpty()) "" else exec.params.toString()
            callback.onToolCall(iterations, exec.toolName, exec.displayName, exec.args)
            callback.onToolResult(iterations, exec.toolName, exec.displayName, paramsStr, result)

            // finish 工具特殊处理(虽不算 readonly,但若 LLM 错调到 finish 仍要走原流程)
            if (exec.toolName == "finish" && result.isSuccess) {
                val repair = shouldRepairForGoal(callback)
                if (repair != null) {
                    appendToolResult(exec.request, result)
                    messages.add(UserMessage.from(repair))
                    return ToolHandleResult.CONTINUE
                }
                taskSucceeded = true
                callback.onComplete(iterations, result.data ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                return ToolHandleResult.TERMINATE
            }

            recordFingerprint(exec.toolName, exec.args, result)
            appendToolResult(exec.request, result)
        }

        return ToolHandleResult.CONTINUE
    }

    /**
     * 单个只读工具的简化执行路径:并行阶段不会调用此方法(走 [commitReadonlyToolsParallel]),
     * 仅在 readonly 组只有 1 个工具时使用(省线程开销)。
     *
     * 与 [executeSingleTool] 区别:
     *  - 跳过 UI 状态比对(readonly 工具无 UI 副作用)
     *  - 跳过 PopupDetector.tryDismiss(readonly 工具不触发弹窗)
     *  - 跳过动作录制 ActionRecorder(readonly 工具不算"动作")
     *  - 跳过导航图谱学习 NavigateTool.recorder(readonly 工具不改导航状态)
     *  - 跳过非幂等失败警告(readonly 工具失败可重试,无需警告)
     */
    @Suppress("ReturnCount")
    private fun AgentLoopState.executeReadonlyTool(
        toolRequest: ToolExecutionRequest,
        callback: AgentCallback,
    ): ToolHandleResult {
        val toolName = toolRequest.name() ?: ""
        val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
        val toolArgs = toolRequest.arguments() ?: "{}"
        callback.onToolCall(iterations, toolName, displayName, toolArgs)

        val params = parseToolArgs(toolName, toolArgs) ?: run {
            val schema = formatToolSchema(toolName)
            val errorResult = ToolResult.error(
                "参数解析失败:JSON 格式有误。原始参数: $toolArgs" +
                (if (schema.isNotEmpty()) "\n正确参数格式: $schema" else "") +
                "\n请按此格式重新调用。"
            )
            appendToolResult(toolRequest, errorResult)
            callback.onToolResult(iterations, toolName, displayName, toolArgs, errorResult)
            return ToolHandleResult.CONTINUE
        }

        if (cancelToken.isCancelled()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
            return ToolHandleResult.TERMINATE
        }

        AgentMetrics.toolCall()
        val rawResult = execTool(toolName, params)
        if (!rawResult.isSuccess) AgentMetrics.toolFailure()

        val paramsString = if (params.isEmpty()) "" else params.toString()
        callback.onToolResult(iterations, toolName, displayName, paramsString, rawResult)

        // finish 工具特殊处理(保守:即使被归为 readonly 也保留主循环耦合逻辑)
        if (toolName == "finish" && rawResult.isSuccess) {
            val repair = shouldRepairForGoal(callback)
            if (repair != null) {
                appendToolResult(toolRequest, rawResult)
                messages.add(UserMessage.from(repair))
                return ToolHandleResult.CONTINUE
            }
            taskSucceeded = true
            callback.onComplete(iterations, rawResult.data ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
            return ToolHandleResult.TERMINATE
        }

        recordFingerprint(toolName, toolArgs, rawResult)
        appendToolResult(toolRequest, rawResult)
        return ToolHandleResult.CONTINUE
    }

    private fun AgentLoopState.callLlm(callback: AgentCallback): LlmResponse? {
        compressHistoryForSend(messages)
        return try {
            chatWithRetry(messages, callback, iterations)
        } catch (e: Exception) {
            AgentMetrics.llmFailure()
            XLog.e(TAG, "LLM API call failed after retries", e)
            callback.onError(
                iterations,
                RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)),
                totalTokens
            )
            null
        }
    }

    private fun AgentLoopState.handleLlmResponse(llmResponse: LlmResponse, callback: AgentCallback): Boolean {
        llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

        val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
            if (llmResponse.text.isNullOrEmpty()) {
                AiMessage.from(llmResponse.toolExecutionRequests)
            } else {
                AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
            }
        } else {
            AiMessage.from(llmResponse.text ?: "")
        }
        messages.add(aiMessage)

        if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
            callback.onContent(iterations, llmResponse.text)
        }

        if (!llmResponse.hasToolExecutionRequests()) {
            // 目标自校验：LLM 不再调工具即判 Done 是 ReAct 的盲点——可能点错/被弹窗挡住。
            // 完成前用 VLM 看屏确认；未达成则注入修复提示并继续循环（fail-open，永不弱于现状）。
            val repair = shouldRepairForGoal(callback)
            if (repair != null) {
                messages.add(UserMessage.from(repair))
                return false
            }
            taskSucceeded = true
            callback.onComplete(iterations, llmResponse.text ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
            return true
        }
        return false
    }

    private fun AgentLoopState.executeSingleTool(toolRequest: ToolExecutionRequest, callback: AgentCallback): ToolHandleResult {
        val toolName = toolRequest.name() ?: ""
        val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
        val toolArgs = toolRequest.arguments() ?: "{}"
        callback.onToolCall(iterations, toolName, displayName, toolArgs)

        val params = parseToolArgs(toolName, toolArgs) ?: run {
            // 附上工具参数 schema,让 LLM 知道正确格式,减少重复格式错误
            val schema = formatToolSchema(toolName)
            val errorResult = ToolResult.error(
                "参数解析失败:JSON 格式有误。原始参数: $toolArgs" +
                (if (schema.isNotEmpty()) "\n正确参数格式: $schema" else "") +
                "\n请按此格式重新调用。"
            )
            appendToolResult(toolRequest, errorResult)
            callback.onToolResult(iterations, toolName, displayName, toolArgs, errorResult)
            return ToolHandleResult.CONTINUE
        }

        if (cancelToken.isCancelled()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
            return ToolHandleResult.TERMINATE
        }

        runCatching { com.apk.claw.android.octopus_mobile.PopupDetector.tryDismiss() }

        val uiAction = toolName in setOf(
            "tap", "long_press", "swipe", "input_text", "system_key",
            "dpad_center", "dpad_up", "dpad_down", "dpad_left", "dpad_right",
            "press_menu", "press_power", "volume_up", "volume_down",
            "browser_click", "browser_type", "browser_submit", "browser_scroll"
        )
        val beforeState = if (uiAction) {
            runCatching { com.apk.claw.android.navigation.StateDetector.detectCurrentState() }.getOrNull()
        } else null

        // ── 动作录制：工具执行前抓锚点（点击后屏幕就变了，只能在点之前抓）──
        actionRecorder?.onToolCall(toolName, toolArgs)
        AgentMetrics.toolCall()
        val rawResult = execTool(toolName, params)
        if (!rawResult.isSuccess) AgentMetrics.toolFailure()
        // ── 动作录制：工具执行后记录成功/失败 ──
        actionRecorder?.onToolResult(toolName, rawResult.isSuccess)
        // ── Agent 动作录制：用户主动开启录制时，把工具调用流水收进 AgentActionRecorder ──
        if (AgentActionRecorder.isRecording) {
            val resultSummary = if (rawResult.isSuccess) {
                (rawResult.data ?: "ok").take(200)
            } else {
                "✗ " + (rawResult.error ?: "failed").take(200)
            }
            AgentActionRecorder.recordAction(toolName, params, resultSummary)
        }

        // ── 导航图谱被动学习：有 UI 副作用的工具执行后通知 recorder ──
        if (rawResult.isSuccess) {
            runCatching {
                com.apk.claw.android.tool.impl.NavigateTool.recorder.onToolExecuted(toolName, params)
            }
        }

        var result = rawResult

        if (!rawResult.isSuccess) {
            val tool = ToolRegistry.getInstance().getTool(toolName)
            if (tool != null && !tool.isIdempotent()) {
                result = ToolResult.error(
                    (rawResult.error ?: "unknown error") +
                        "\n[警告] 此操作（$toolName）会改变设备状态，执行可能已产生副作用。" +
                        "切勿盲目重复同样的操作——请先确认当前状态（如 get_screen_info / look_at_screen / browser_get_dom），" +
                        "再决定下一步动作，避免重复点击/发送/提交。"
                )
            }
        }

        if (uiAction && rawResult.isSuccess && beforeState != null) {
            val after = runCatching { com.apk.claw.android.navigation.StateDetector.detectCurrentState() }.getOrNull()
            val unchanged = after != null &&
                com.apk.claw.android.navigation.StateDetector.similarity(beforeState, after) >= 0.97
            if (unchanged) {
                result = ToolResult.success(
                    (rawResult.data ?: "") +
                        "\n[校验] 屏幕未发生变化——此操作可能没生效（目标不存在 / 被弹窗遮挡 / 点到空白）。" +
                        "请先 get_screen_info 或 look_at_screen 确认目标，再换一种方式（如 tap_by_vision / scroll_to_find）重试，不要重复同一动作。"
                )
            }
        }

        val paramsString = if (params.isEmpty()) "" else params.toString()
        callback.onToolResult(iterations, toolName, displayName, paramsString, result)

        when (handleDialogResult(result, callback)) {
            DialogHandleResult.TERMINATE -> return ToolHandleResult.TERMINATE
            DialogHandleResult.SKIP_REMAINING -> return ToolHandleResult.SKIP_REMAINING
            DialogHandleResult.NONE -> { }
        }

        // ── 自进化：高频 open_app 自动生成 ReflexRouter 快路径规则 ──
        if (toolName == "open_app" && result.isSuccess) {
            val packageName = params["package"] as? String
            if (packageName != null) {
                val count = toolCallFrequency.merge(packageName, 1, Int::plus) ?: 1
                if (count >= 3) {
                    // ≥3 次：自动提炼规则，生成快路径
                    TaskOrchestrator.current?.getReflexRouter()?.learnFromPattern(toolName, params, count)
                }
            }
        }

        if (toolName == "finish" && result.isSuccess) {
            // 目标自校验：finish 是 Agent 显式宣称完成的主路径，同样在结束前用 VLM 看屏确认。
            // 未达成则把 finish 的工具结果补回历史（保持对话合法）+ 注入修复提示，继续循环。
            val repair = shouldRepairForGoal(callback)
            if (repair != null) {
                appendToolResult(toolRequest, result)
                messages.add(UserMessage.from(repair))
                return ToolHandleResult.CONTINUE
            }
            taskSucceeded = true
            callback.onComplete(iterations, result.data ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
            return ToolHandleResult.TERMINATE
        }

        recordFingerprint(toolName, toolArgs, result)
        appendToolResult(toolRequest, result)
        return ToolHandleResult.CONTINUE
    }

    private fun AgentLoopState.parseToolArgs(toolName: String, toolArgs: String): Map<String, Any>? {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        return try {
            GSON.fromJson(toolArgs, mapType) ?: emptyMap()
        } catch (e: Exception) {
            XLog.w(TAG, "Tool args parse failed for $toolName: ${e.message}, args=$toolArgs")
            null
        }
    }

    /** 从 toolSpecs 里提取工具参数 schema,格式化为 LLM 可读的 JSON 示例. */
    private fun formatToolSchema(toolName: String): String {
        val spec = toolSpecs.find { it.name() == toolName } ?: return ""
        val params = spec.parameters() ?: return ""
        return runCatching {
            val props = params.properties()
            val required = params.required()
            val example = LinkedHashMap<String, String>()
            for ((key, schema) in props) {
                val typeHint = when (schema) {
                    is dev.langchain4j.model.chat.request.json.JsonStringSchema -> "string"
                    is dev.langchain4j.model.chat.request.json.JsonIntegerSchema -> "integer"
                    is dev.langchain4j.model.chat.request.json.JsonNumberSchema -> "number"
                    is dev.langchain4j.model.chat.request.json.JsonBooleanSchema -> "boolean"
                    else -> "value"
                }
                example[key] = if (key in required) "<$typeHint required>" else "<$typeHint optional>"
            }
            GSON.toJson(example)
        }.getOrDefault("")
    }

    /**
     * 序列化工具结果给 LLM 观测(排除 imageBase64 省 token)。errorCode/errorLine 仅在有值时带上——
     * 给自动修复循环一个机器可读的判据(参数错/超时/脚本第几行崩),而不必去正则解析人类文本。
     */
    private fun toolResultForJson(result: ToolResult): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "isSuccess" to result.isSuccess,
            "data" to result.data,
            "error" to result.error,
        )
        result.errorCode?.let { m["errorCode"] = it }
        result.errorLine?.let { m["errorLine"] = it }
        return m
    }

    private fun AgentLoopState.appendToolResult(toolRequest: ToolExecutionRequest, result: ToolResult) {
        messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(toolResultForJson(result))))

        // 工具返回图片时（如 preview_html），追加视觉消息供多模态 LLM 直接查看并自迭代。
        val img = result.imageBase64
        if (img != null && config.enableVision) {
            messages.add(
                UserMessage.from(
                    TextContent.from("以下是工具截图，请仔细查看后决定下一步："),
                    ImageContent.from("data:image/jpeg;base64,$img"),
                )
            )
        }
    }

    private enum class DialogHandleResult { NONE, SKIP_REMAINING, TERMINATE }

    private fun AgentLoopState.handleDialogResult(result: ToolResult, callback: AgentCallback): DialogHandleResult {
        if (result.isSuccess || result.error != GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) return DialogHandleResult.NONE

        XLog.w(TAG, "System dialog blocked, attempting VLM analysis")
        if (!config.enableVision) {
            XLog.w(TAG, "Vision disabled, notifying user and stopping task")
            callback.onSystemDialogBlocked(iterations, totalTokens)
            return DialogHandleResult.TERMINATE
        }

        val visionHandled = handleSystemDialogWithVision(messages, callback, iterations)
        if (!visionHandled) {
            callback.onSystemDialogBlocked(iterations, totalTokens)
            return DialogHandleResult.TERMINATE
        }
        return DialogHandleResult.SKIP_REMAINING
    }

    private fun AgentLoopState.recordFingerprint(toolName: String, toolArgs: String, result: ToolResult) {
        if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
            lastScreenHash = result.data.hashCode()
            loopHistory.addLast(RoundFingerprint(lastScreenHash, "observe:get_screen_info"))
            if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
        } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
            loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
            if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
        }
    }

    private fun AgentLoopState.handleLoopDetection(callback: AgentCallback): Boolean {
        if (!isStuckInLoop(loopHistory)) {
            loopWarningCount = 0
            return false
        }

        loopWarningCount++
        AgentMetrics.loopWarning()
        XLog.w(TAG, "Dead loop detected at iteration $iterations (warning $loopWarningCount/$MAX_LOOP_WARNINGS)")

        if (loopWarningCount >= MAX_LOOP_WARNINGS) {
            XLog.w(TAG, "Max loop warnings reached, forcing finish")
            callback.onComplete(
                iterations,
                ClawApplication.instance.getString(R.string.agent_task_completed) + "（检测到死循环，已自动停止）",
                totalTokens
            )
            return true
        }

        messages.add(
            UserMessage.from(
                "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                "请尝试完全不同的方法：按 system_key(key=\"back\") 回退、滑动页面寻找目标、或重新打开 App。" +
                "如果确实无法完成任务，请调用 finish 说明原因。" +
                "（警告：再检测到 ${MAX_LOOP_WARNINGS - loopWarningCount} 次死循环将强制结束任务）"
            )
        )
        loopHistory.clear()
        return false
    }

    /**
     * 目标自校验（verdict-repair）：LLM 宣称完成后，用 VLM 看当前屏幕判断目标是否真达成。
     *
     * 返回非 null 的修复提示串 = 未达成且仍有修复机会（调用方应注入该提示并继续循环）；
     * 返回 null = 达成 / 无法校验 / 修复轮数耗尽（调用方应正常结束）。
     *
     * 全程 fail-open：未开启视觉、未配置 VLM、截图失败、校验异常一律返回 null，绝不拦正常完成——
     * 这样接进去永不弱于现状，价值只在 VLM 明确判"未达成"时兑现。外层 maxIterations 仍兜底防失控。
     */
    private fun AgentLoopState.shouldRepairForGoal(callback: AgentCallback): String? {
        if (goalRepairsLeft <= 0) return null
        if (!config.enableVision || goal.isBlank() || !VisionAnalyzer.isConfigured()) return null

        val bitmap = runCatching {
            ClawAccessibilityService.getInstance()?.takeScreenshot(5000)
        }.getOrNull() ?: return null

        AgentMetrics.goalVerify()
        val verdict = try {
            // 在独立线程跑 VLM 校验,executor 线程用 poll 等待 —— 这样 cancelToken 能在
            // VLM 网络挂起时及时终止等待,不再被 runBlocking 阻塞到 35s 超时才检查取消。
            // 硬超时 35s(略高于 VLM HTTP 的 30s callTimeout)仍作兜底。
            verifyGoalWithCancellation(goal, bitmap)
        } catch (e: Exception) {
            XLog.w(TAG, "goal verify failed, fail-open: ${e.message}")
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        if (verdict == null || verdict.achieved) return null

        goalRepairsLeft--
        AgentMetrics.goalRepair()
        XLog.i(TAG, "Goal not achieved (repairs left=$goalRepairsLeft): ${verdict.reason}")
        callback.onContent(iterations, "[目标校验] 目标尚未达成：${verdict.reason}")
        return "[目标校验] 经看屏确认，目标尚未达成：${verdict.reason}。" +
            "请继续操作直到真正完成；若确实无法完成，再调用 finish 说明原因。"
    }

    /**
     * 在独立线程跑 VLM 目标校验,当前(executor)线程 poll 等待结果,每 200ms 检查一次
     * [cancelToken]。这样 Agent 取消时能及时跳出等待,不被 VLM 网络 RT 阻塞。
     *
     * 超时或取消时返回 null(fail-open,不拦正常完成)。bitmap 由调用方 recycle。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "MagicNumber")
    private fun verifyGoalWithCancellation(goal: String, bitmap: android.graphics.Bitmap): GoalVerifier.Verdict? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<GoalVerifier.Verdict?>(null)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val vlmThread = Thread({
            try {
                // 用 runBlocking 在 VLM 线程内驱动 suspend verify;executor 线程不受阻塞。
                result.set(runBlocking {
                    withTimeoutOrNull(VLM_VERIFY_TIMEOUT_MS) {
                        GoalVerifier.verify(goal, bitmap)
                    }
                })
            } catch (e: Throwable) {
                error.set(e)
            } finally {
                latch.countDown()
            }
        }, "goal-verifier").apply { isDaemon = true }

        vlmThread.start()

        // executor 线程 poll 等待,每 200ms 检查取消 —— 不再被 runBlocking 钉死。
        val deadline = System.currentTimeMillis() + VLM_VERIFY_TIMEOUT_MS + POLL_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (cancelToken.isCancelled()) {
                vlmThread.interrupt()
                // 等待 VLM 线程退出,避免调用方 recycle bitmap 时线程仍在做 base64/网络传输导致 use-after-recycle 崩溃。
                joinVlmThread(vlmThread)
                XLog.w(TAG, "goal verify cancelled by CancellationToken")
                return null
            }
            if (latch.await(POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
        }

        // 如果 VLM 线程还在跑(超时未返回),中断它避免线程泄漏。
        if (vlmThread.isAlive) {
            vlmThread.interrupt()
            joinVlmThread(vlmThread)
            XLog.w(TAG, "goal verify timed out, VLM thread interrupted")
            return null
        }

        error.get()?.let { throw it }
        return result.get()
    }

    /**
     * 等待 VLM 线程退出(带 3 秒超时),用于 [verifyGoalWithCancellation] 取消/超时路径。
     * 确保 VLM 线程不再持有 bitmap 引用后,调用方才 recycle bitmap,避免 use-after-recycle 崩溃。
     */
    private fun joinVlmThread(thread: Thread) {
        try {
            thread.join(3_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            XLog.w(TAG, "VLM thread still alive after join(3s), bitmap recycle may race")
        }
    }

    private fun finishLoop(state: AgentLoopState, callback: AgentCallback) {
        if (!config.skipCheckpoint) {
            flushCheckpointExecutor()
            TaskCheckpoint.clear()
        }
        // 持久化 AgentMetrics 并输出汇总报告
        runCatching {
            AgentMetrics.persist()
            XLog.i(TAG, "AgentMetrics: ${AgentMetrics.report()}")
        }
        // ── 动作录制：仅在任务真正成功完成时才提交快路径缓存 ──
        // 旧逻辑用 iterations < maxIterations 判断"正常完成",但 LLM 报错提前终止也满足该条件,
        // 会把失败动作序列缓存到快路径。改用显式的 taskSucceeded 标志位。
        if (state.taskSucceeded && !cancelToken.isCancelled()) {
            actionRecorder?.commit(state.goal.hashCode().toString(), state.goal)
        }
        when {
            cancelToken.isCancelled() ->
                callback.onComplete(state.iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), state.totalTokens)
            // 仅在真正耗尽迭代次数时才报"已达最大迭代次数"。
            // 正常结束（onComplete）或调用失败（onError）已在循环内发出对应消息，
            // 这里不能再无条件追加，否则每次都叠加一条自相矛盾的"任务已完成 + 已达最大迭代次数"。
            state.iterations >= state.maxIterations ->
                callback.onError(
                    state.iterations,
                    RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, state.maxIterations)),
                    state.totalTokens
                )
        }
    }

    override fun cancel() {
        cancelToken.cancel(ClawApplication.instance.getString(R.string.agent_task_cancel))
        if (!config.skipCheckpoint) {
            flushCheckpointExecutor()
            TaskCheckpoint.clear()
        }
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        checkpointExecutor?.shutdown()
        toolParallelExecutor?.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()
}
