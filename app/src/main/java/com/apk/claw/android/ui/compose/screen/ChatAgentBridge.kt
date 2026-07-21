package com.apk.claw.android.ui.compose.screen

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.agent.TaskCheckpoint
import com.apk.claw.android.floating.LiveControlOverlay
import com.apk.claw.android.octopus_mobile.ActionRecorder
import com.apk.claw.android.octopus_mobile.ActivityLog
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.ReflexArc
import com.apk.claw.android.octopus_mobile.persona.PersonaStore
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils

/**
 * 把 Compose 对话页直接接到真实 Agent（[DefaultAgentService]）。
 *
 * 这样用户在 App 内对话框输入自然语言指令，就能真正驱动 LLM + 工具执行，
 * 无需先去某个 IM 平台配置机器人 —— 这是让应用「真的能跑」的本地入口。
 *
 * 设计：
 *  - 配置直接取自 [KVUtils]（与渠道/编排器同一份存储），所以在「LLM 配置」里
 *    填好 DeepSeek/OpenAI 的 key/baseUrl/model 后，这里立即生效。
 *  - 所有 [AgentCallback] 事件都切回主线程，方便直接更新 Compose 状态。
 *  - 关闭视觉（VLM）：deepseek-chat 等纯文本模型不支持图片输入。
 */
object ChatAgentBridge {

    private val service = DefaultAgentService()
    private val main = Handler(Looper.getMainLooper())

    // 长期记忆/教训:与渠道路径(AppViewModel.getAgentConfig)同待遇。对话页此前只注入技能,
    // 记忆读写双缺 —— 用户在对话页说"我用饿了么不用美团"既记不住、下次也想不起来。
    private val memoryStore by lazy { com.apk.claw.android.octopus_mobile.memory.MemoryStore() }
    private val lessonStore by lazy {
        com.apk.claw.android.octopus_mobile.evolution.LessonStore(ClawApplication.instance)
    }
    // 单 Agent 服务:同一时刻只跑一个任务。网页端([AgentWebBridge])与 App 对话页共享本桥,
    // 用一个忙标记拦截并发,避免后来的 run() 经 updateConfig 把前一个任务的 executor 关掉。
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 是否正在执行任务(网页端 / App 端共享判断)。 */
    fun isBusy(): Boolean = busy.get()

    /** 是否有崩溃前未完成的任务可恢复。 */
    fun hasPendingCheckpoint(): Boolean = TaskCheckpoint.hasPending()

    /** 获取待恢复任务的目标摘要（用于 UI 提示）。 */
    fun pendingCheckpointSummary(): String? {
        val cp = TaskCheckpoint.load() ?: return null
        return "「${cp.goal.take(40)}${if (cp.goal.length > 40) "..." else ""}」" +
            "（已执行 ${cp.iterations} 轮）"
    }

    /**
     * 恢复崩溃前未完成的任务。回调与 [run] 一致。
     * @return true 如果成功开始恢复
     */
    fun resumePendingTask(
        onTool: (icon: String, name: String, args: String, result: String?) -> Unit,
        onText: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (!TaskCheckpoint.hasPending()) return false
        if (!busy.compareAndSet(false, true)) {
            onError(ClawApplication.instance.getString(R.string.chat_agent_bridge_busy_error))
            return false
        }
        val cp = TaskCheckpoint.load() ?: run {
            busy.set(false)
            return false
        }
        service.updateConfig(buildConfig())
        curTask = cp.goal
        curTarget = ControlTarget.label()
        curSteps = 0
        curStart = System.currentTimeMillis()
        LiveControlOverlay.show("恢复任务中…") { cancel() }
        val batcher = StreamBatcher(main) { txt -> onText(txt) }
        val resumed = service.resumeTask(object : AgentCallback {
            override fun onLoopStart(round: Int) {
                LiveControlOverlay.updateStep(ClawApplication.instance.getString(R.string.chat_agent_bridge_thinking))
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) batcher.submit(content)
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {}

            override fun onToolResult(
                round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult
            ) {
                curSteps++
                val summary = if (result.isSuccess) "✓ " + (result.data ?: "") else "✗ " + (result.error ?: "")
                main.post { onTool(toolId, toolName, parameters, summary.take(48)) }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                batcher.flushNow()
                main.post {
                    onDone(finalAnswer)
                    LiveControlOverlay.hide()
                    finalize("completed", finalAnswer.take(120))
                    busy.set(false)
                }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                batcher.flushNow()
                main.post {
                    onError(error.message ?: "Unknown error")
                    LiveControlOverlay.hide()
                    finalize("error", error.message?.take(120) ?: "unknown")
                    busy.set(false)
                }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                main.post {
                    onError(ClawApplication.instance.getString(R.string.chat_agent_bridge_dialog_detected_full))
                    LiveControlOverlay.hide()
                    finalize("blocked", "system dialog")
                    busy.set(false)
                }
            }
        })
        if (!resumed) {
            busy.set(false)
            LiveControlOverlay.hide()
        }
        return resumed
    }

    /** 丢弃待恢复的检查点。 */
    fun discardPendingCheckpoint() {
        TaskCheckpoint.clear()
    }

    // 当前任务的审计采集
    private var curTask: String? = null
    private var curTarget = ""
    private var curSteps = 0
    private var curStart = 0L

    /**
     * 是否有可用的 LLM。走与主对话页同一套路由 [LlmRouting]：
     * 默认是平台中转(登录即有 token、扣积分),其次才是用户自填的 BYO key。
     * 不能再只看 BYO key,否则付费用户在浏览器里会被错误地要求"先配置模型"。
     */
    fun isConfigured(): Boolean =
        com.apk.claw.android.account.LlmRouting.effective().apiKey.isNotBlank()

    /** 中断当前正在运行的任务。 */
    fun cancel() {
        service.cancel()
        LiveControlOverlay.hide()
        finalize("cancelled", ClawApplication.instance.getString(R.string.chat_agent_bridge_manually_stopped))
        busy.set(false)
    }

    /** 落一条审计记录并清空当前任务状态（幂等：无活动任务时跳过）。 */
    @Synchronized
    private fun finalize(outcome: String, detail: String) {
        val task = curTask ?: return
        runCatching {
            ActivityLog.record(
                ActivityLog.Entry(
                    id = "act_" + System.currentTimeMillis(),
                    ts = System.currentTimeMillis(),
                    task = task,
                    target = curTarget,
                    steps = curSteps,
                    outcome = outcome,
                    detail = detail.take(120),
                )
            )
        }
        curTask = null
    }

    private fun buildConfig(prompt: String? = null): AgentConfig {
        // 与 AppViewModel.getAgentConfig() 一致：默认平台中转(扣积分),会员且显式选择才用 BYO,
        // 未配置中转/未登录时回退到本地 LLM 配置。
        val eff = com.apk.claw.android.account.LlmRouting.effective()
        var baseUrl = eff.baseUrl
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com/v1"
        // 注入与本次任务相关的已启用「提示词技能」(见 PromptSkillStore):按 prompt 命中,省 token。
        val skillSuffix = com.apk.claw.android.octopus_mobile.skill.PromptSkillStore.buildPromptSection(prompt)
        // 教训(B2 反思产物)+ 跨会话记忆:渠道路径早就注入,对话页补齐读侧(写侧见 run 的 onComplete)。
        // withMemoInstruction 只在对话页开:让主模型顺手用 MEMO 行标注用户透露的偏好/事实,
        // onComplete 里 harvestMemos 收割并剥离;渠道路径不开,MEMO 行会原样漏给 IM 用户。
        val lessonSuffix = lessonStore.buildPromptSection()
        // taskHint=prompt:记忆按与本次任务的相关性排序,记忆多了也只把相关的顶进有限预算(与技能同款)。
        val memorySuffix = memoryStore.buildPromptSection(withMemoInstruction = true, taskHint = prompt)
        return AgentConfig.Builder()
            .apiKey(eff.apiKey)
            .baseUrl(baseUrl)
            .modelName(eff.model.ifBlank { if (eff.platform) "mimo-v2-flash" else "deepseek-chat" })
            .temperature(0.1)
            .maxIterations(80)
            .enableVision(false)
            .streaming(true)   // 逐字流式输出
            .dynamicPromptSuffix(lessonSuffix + skillSuffix)
            .memoryPromptSuffix(memorySuffix)
            .build()
    }

    /**
     * 运行一次任务。所有回调均在主线程触发。
     *
     * @param onTool 一次工具执行完成（图标、工具名、参数、结果摘要）
     * @param onText Agent 中间思考文本
     * @param onDone 任务完成（最终回答）
     * @param onError 出错（含未配置 / LLM 调用失败）
     * @param recordKey 非空时把本次运行的有效 UI 动作录成「快路径」存到该 key（例程 id）下，
     *                  供 [FastReplay] 下次确定性重放。对话页传 null（不录）。仅本机目标可录。
     * @param conversationContext 最近几轮对话的摘要（[com.apk.claw.android.agent.ConversationContext.build]
     *                  产出）。非空时拼进任务 prompt 的「对话背景」区,让 Agent 能理解
     *                  「换成蓝牙的」这类依赖上文的指代;为 null 时行为与从前完全一致。
     * @param persona 非空时要求 Agent 全程以该人设的第一人称身份/口吻回答(TV 模式的角色扮演),
     *                  拼在任务 prompt 最前;为 null 时不扮演,行为与从前完全一致。
     * @param workspace 非空时拼进任务 prompt 的「工作空间」区,告诉 Agent 当前会话的项目目录路径,
     *                  类似 Codex 启动时 --cd 选定项目目录。Agent 可用 file_ops/browse_files
     *                  读该目录下的代码与文件,run_code/run_python 的 WORKSPACE 也会切到此处。
     *                  为 null 时不注入(行为与改造前一致)。
     */
    @Suppress("LongParameterList") // 参数主体是一束 UI 回调(onTool/onText/…),收拢成对象要连改 7 个调用点,可读性反而更差
    fun run(
        prompt: String,
        onTool: (icon: String, name: String, args: String, result: String?) -> Unit,
        onText: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        recordKey: String? = null,
        untrusted: Boolean = false,
        onImage: ((toolName: String, imageBase64: String) -> Unit)? = null,
        onHtml: ((toolName: String, htmlContent: String) -> Unit)? = null,
        onFile: ((toolName: String, filePath: String) -> Unit)? = null,
        onDiff: ((toolName: String, diff: String) -> Unit)? = null,
        onForm: ((toolName: String, formJson: String) -> Unit)? = null,
        onTextArtifact: ((toolName: String, title: String, refId: String) -> Unit)? = null,
        /**
         * search_code 工具结果回调(spec refine-chat-interaction Task 5)。
         * search_code 的结果 data 已改为结构化 JSON(含 file/startLine/endLine/snippet 4 字段),
         * 本桥把完整 [ToolResult] 透传给 UI,由 UI 调用
         * [com.apk.claw.android.tool.impl.handleSearchCodeResult] 生成 CODE_SNIPPET Artifact。
         * 仅当工具名为 "search_code" 且结果成功时触发。
         */
        onCodeSnippet: ((toolName: String, result: ToolResult) -> Unit)? = null,
        /**
         * 纯文本产物回调(run_code stdout 等)。UI 据此生成 TEXT 类 Artifact 卡片:
         * 折叠态显示前 3 行预览,右侧栏 TextDetailPane 显示全文。
         * 参数:toolName 触发工具名,body 文本正文。
         */
        onTextBody: ((toolName: String, body: String) -> Unit)? = null,
        /**
         * Plan 模式产物回调。LLM 在 PLAN 模式下输出步骤数组后调 exit_plan_mode,
         * 经用户确认切换回 DEFAULT 模式时,DefaultAgentService 从 LLM 文本提取 plan JSON
         * 通过 ToolResult.planJson 回传,本桥据此触发本回调。
         * UI 据此生成 PLAN 类 Artifact 卡片(标题「计划(N 步)」,详情走右侧栏 PlanDetailPane)。
         * 参数:toolName 固定为 "exit_plan_mode",planJson 为步骤数组 JSON 字符串。
         */
        onPlan: ((toolName: String, planJson: String) -> Unit)? = null,
        conversationContext: String? = null,
        persona: String? = null,
        workspace: String? = null,
        /**
         * 非空时覆盖当前激活 Persona 执行本次任务(用于互聊模式：每个 Persona 轮流发言)。
         * 实现方式：在 executeTask 前临时 setActive(override)，在 onDone/onError 恢复原 active。
         * 为 null 时行为与从前一致（用全局激活 Persona）。
         */
        personaIdOverride: String? = null,
    ) {
        // 忙判断必须在改动任何共享状态(updateConfig/curTask)之前,拒绝并发任务。
        if (!busy.compareAndSet(false, true)) {
            onError(ClawApplication.instance.getString(R.string.chat_agent_bridge_busy_error))
            return
        }

        ReflexArc.tryMatch(prompt)?.let { match ->
            main.post {
                if (match.cachedHtml != null && match.cachedAppId != null) {
                    onText(match.response)
                    onHtml?.invoke("open_mini_app", "700\n${match.cachedHtml}")
                } else {
                    onText(match.response)
                }
                onDone(match.response)
            }
            busy.set(false)
            return
        }

        val recorder = recordKey?.let { ActionRecorder() }
        service.updateConfig(buildConfig(prompt))   // prompt 传入以按相关性注入提示词技能(按当前指令算相关性)
        // 带上人设/对话背景组任务 prompt;审计(curTask)仍记原始指令,别把背景刷进审计日志
        var taskPrompt = conversationContext?.takeIf { it.isNotBlank() }?.let {
            "【对话背景】以下是本会话之前的对话摘要,仅用于理解当前指令里的指代与延续意图," +
                "其中提到的任务都已结束,不要重复执行:\n$it\n\n【当前指令】\n$prompt"
        } ?: prompt
        persona?.takeIf { it.isNotBlank() }?.let {
            taskPrompt = "【角色扮演】$it\n\n$taskPrompt"
        }
        // 注入工作空间(类似 Codex --cd 选定项目目录):告诉 Agent 当前会话的项目根,
        // 可用 file_ops/browse_files/read 读取该目录下的代码与文件。
        workspace?.takeIf { it.isNotBlank() }?.let { ws ->
            taskPrompt = "【工作空间】本会话已选定项目目录:$ws\n" +
                "你可以用 file_ops(action=read/browse)、browse_files、search_files 读取该目录下的代码与文件;" +
                "run_code / run_python 的 WORKSPACE 全局变量也已切到此处,read_file/write_file 默认落到这里。\n\n$taskPrompt"
        }
        // 审计采集：开始一次任务
        curTask = prompt
        curTarget = ControlTarget.label()
        curSteps = 0
        curStart = System.currentTimeMillis()
        // 实时控制层：任务期间悬浮显示当前步骤 + 停止键（即使 Agent 跳出本 App 也可见）
        LiveControlOverlay.show(ClawApplication.instance.getString(R.string.chat_agent_bridge_preparing)) { cancel() }
        // 流式 token 批量合并:50ms 间隔合并 post,避免每 token 一次 main.post 风暴
        val batcher = StreamBatcher(main) { txt -> onText(txt) }
        // 注入会话级工作空间(类似 Codex --cd 选定项目目录):execTool 时通过 ThreadLocal
        // 透传给 ScriptSandbox/PythonSandbox,影响 run_code/run_python 的 WORKSPACE 全局变量。
        service.setWorkspace(workspace)
        // 互聊模式：临时切换激活 Persona，任务结束后恢复原激活（保证不污染全局 Persona 状态）。
        // saveActive 为 null 表示原本无激活；override 为 null 表示不覆盖。
        val ctx = ClawApplication.instance
        val saveActive = if (personaIdOverride != null) PersonaStore.getActive(ctx) else null
        if (personaIdOverride != null) PersonaStore.setActive(ctx, personaIdOverride)
        val restorePersona = {
            if (personaIdOverride != null) PersonaStore.setActive(ctx, saveActive)
        }
        service.executeTask(taskPrompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                LiveControlOverlay.updateStep(ClawApplication.instance.getString(R.string.chat_agent_bridge_thinking))
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) batcher.submit(content)
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                // toolId = 真实工具名，parameters = LLM 原始 JSON 参数（执行前，可抓点击锚点）
                recorder?.onToolCall(toolId, parameters)
            }

            override fun onToolResult(
                round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult
            ) {
                recorder?.onToolResult(toolId, result.isSuccess)
                val summary = if (result.isSuccess) "✓ " + (result.data ?: "") else "✗ " + (result.error ?: "")
                val icon = iconFor(toolName)
                val friendly = ToolRegistry.getInstance().getDisplayName(toolName)
                curSteps++
                LiveControlOverlay.updateStep("$icon $friendly")
                main.post { onTool(icon, friendly, parameters, summary.take(48)) }
                val img = result.imageBase64
                if (img != null && onImage != null) {
                    main.post { onImage(toolName, img) }
                }
                val html = result.htmlContent
                if (html != null && onHtml != null) {
                    main.post { onHtml(toolName, html) }
                }
                val fp = result.filePath
                if (fp != null && onFile != null) {
                    main.post { onFile(toolName, fp) }
                }
                val df = result.diff
                if (df != null && onDiff != null) {
                    main.post { onDiff(toolName, df) }
                }
                val form = result.formData
                if (form != null && onForm != null) {
                    main.post { onForm(toolName, form) }
                }
                // search_code 结构化结果:透传 ToolResult 给 UI 生成 CODE_SNIPPET Artifact
                if (toolId == "search_code" && result.isSuccess && onCodeSnippet != null) {
                    main.post { onCodeSnippet(toolId, result) }
                }
                val txt = result.textBody
                if (txt != null && onTextBody != null) {
                    main.post { onTextBody(toolName, txt) }
                }
                val plan = result.planJson
                if (plan != null && onPlan != null) {
                    main.post { onPlan(toolName, plan) }
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                batcher.flushNow()
                if (recordKey != null) recorder?.commit(recordKey, prompt)
                // 记忆写侧(仿 TaskOrchestrator 任务后钩子;对话页绕过编排器,得自己做):
                // ① 收割主模型标注的 MEMO 行(高质量提取,展示前剥离);
                // ② 正则保底只扫用户原始指令,不喂组装后的 taskPrompt(背景区是历史消息,别重复提取)。
                val cleaned = runCatching { memoryStore.harvestMemos(finalAnswer) }.getOrDefault(finalAnswer)
                runCatching {
                    memoryStore.extractFromTask(prompt)
                    memoryStore.pruneExpiredContexts()
                }
                LiveControlOverlay.finish(true, ClawApplication.instance.getString(R.string.floating_circle_success_state))
                finalize("success", cleaned)
                busy.set(false)
                restorePersona()
                main.post { onDone(cleaned) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                batcher.flushNow()
                LiveControlOverlay.finish(false, error.message?.take(20) ?: ClawApplication.instance.getString(R.string.chat_agent_bridge_error))
                finalize("error", error.message ?: ClawApplication.instance.getString(R.string.chat_agent_bridge_call_failed))
                busy.set(false)
                restorePersona()
                main.post { onError(error.message ?: ClawApplication.instance.getString(R.string.chat_agent_bridge_call_failed)) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                LiveControlOverlay.finish(false, ClawApplication.instance.getString(R.string.chat_agent_bridge_manual_required))
                finalize("error", ClawApplication.instance.getString(R.string.chat_agent_bridge_dialog_detected))
                busy.set(false)
                restorePersona()
                main.post { onError(ClawApplication.instance.getString(R.string.chat_agent_bridge_dialog_detected_full)) }
            }

            override fun onTextArtifact(round: Int, toolName: String, title: String, refId: String) {
                onTextArtifact?.let { main.post { it(toolName, title, refId) } }
            }
        }, untrusted)
    }

    /** 工具名 → 直观图标(未命中用通用扳手)。 */
    private fun iconFor(tool: String): String = when {
        tool.contains("screenshot") -> "📸"
        tool.contains("screen") || tool.contains("window") || tool.contains("node") || tool.contains("find") -> "🔍"
        tool.startsWith("tap") || tool.contains("click") -> "👆"
        tool.contains("long_press") -> "✊"
        tool.contains("swipe") || tool.contains("scroll") -> "👋"
        tool.contains("input") || tool.contains("text") -> "⌨️"
        tool.contains("installed_apps") || tool.contains("usage") -> "📋"
        tool.contains("open_app") || tool.contains("launch") || tool.contains("store") -> "📱"
        tool.contains("home") -> "🏠"
        tool.contains("back") -> "↩️"
        tool.contains("key") || tool.contains("recent") -> "⎋"
        tool.contains("browser") || tool.contains("navigate") -> "🌐"
        tool.contains("sms") || tool.contains("send") || tool.contains("file") -> "📤"
        tool.contains("calendar") -> "📅"
        tool.contains("clipboard") -> "📋"
        tool.contains("wait") -> "⏳"
        tool.contains("finish") -> "✅"
        else -> "🔧"
    }

    /**
     * 流式 token 批量合并器:把高频 token(LLM 50-100/s)累积后按 [STREAM_FLUSH_MS] 间隔
     * 合并 post 一次,避免每个 token 都 main.post 一次造成主线程消息队列堆积。
     *
     * 每次任务创建独立实例,任务结束后丢弃,无残留状态。
     */
    private class StreamBatcher(
        private val main: Handler,
        private val onFlush: (String) -> Unit
    ) {
        private val buf = StringBuilder()
        private val lock = Any()
        @Volatile private var pending = false

        /** 累积 token,达到 [STREAM_FLUSH_MS] 间隔后合并 flush。 */
        fun submit(token: String) {
            if (token.isEmpty()) return
            synchronized(lock) { buf.append(token) }
            if (!pending) {
                pending = true
                main.postDelayed({
                    val snap = synchronized(lock) {
                        if (buf.isEmpty()) "" else { val s = buf.toString(); buf.setLength(0); s }
                    }
                    pending = false
                    if (snap.isNotEmpty()) onFlush(snap)
                }, STREAM_FLUSH_MS)
            }
        }

        /**
         * 立即 flush 残留 token(任务结束/出错时调用)。
         *
         * 必须在 onError/onComplete 的 main.post 之前调用,确保残留 token 先于
         * finalizeStream(streamId 清空)进入 UI,避免出错后仍创建新气泡。
         */
        fun flushNow() {
            val snap = synchronized(lock) {
                if (buf.isEmpty()) "" else { val s = buf.toString(); buf.setLength(0); s }
            }
            if (snap.isNotEmpty()) main.post { onFlush(snap) }
        }
    }

    /** 流式 token 合并 flush 间隔(ms):平衡流畅度与主线程压力。 */
    private const val STREAM_FLUSH_MS = 50L
}
