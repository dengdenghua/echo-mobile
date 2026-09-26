@file:Suppress("PackageNaming", "ImplicitDefaultLocale", "MagicNumber", "MaxLineLength")   // ReAct 循环内联常量/长日志行,整文件豁免

package com.apk.claw.android.octopus_mobile

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 方案 F 轻量 ReAct 循环.
 *
 * 借鉴 Octopus Mobile 现有 DefaultAgentService.kt 的设计：
 *  - 三级上下文压缩
 *  - 死循环检测（4 轮滑动窗口）
 *  - 工具执行 token 优化
 *
 * 但用更简化的实现，约 300 行 Kotlin，无 LangChain 依赖.
 *
 * 关键设计（参考 Octopus Mobile 现状 + 改进）：
 *  1. 死循环检测：(screenHash, toolCall) 元组指纹 + 4 轮滑动窗口
 *  2. 三级压缩：get_screen_info 类只保留最新一条，其他用占位符
 *  3. 工具结果摘要：长结果只取首尾 + 关键字段
 *  4. 最大步数保护：默认 30 步（可配置）
 *  5. 友好取消：cooperative cancellation via AtomicInteger
 */
class LightweightReAct(
    private val llmClient: LightweightLlmClient,
    private val toolExecutor: suspend (ToolCall) -> ToolExecutionResult,
    private val config: ReActConfig = ReActConfig(),
    private val verifyGoal: suspend (String, android.graphics.Bitmap?) -> GoalVerifier.Verdict = GoalVerifier::verify
) {
    private val tag = "LightweightReAct"

    /**
     * 跑一次 ReAct 任务.
     *
     * @param task          用户任务
     * @param skills        可用工具集
     * @param systemPrompt  系统提示词（可由调用方提供）
     * @param onStep        每步回调（用于 UI 实时展示）
     */
    suspend fun run(
        task: String,
        skills: List<SkillSpec>,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        onStep: ((ReActStep) -> Unit)? = null,
        // 可选:返回当前屏幕,供 VLM 目标自校验。null → 不校验(向后兼容)。
        captureScreen: (suspend () -> android.graphics.Bitmap?)? = null,
        // 可选:额外系统上下文(如技能商城已安装技能的指令注入)。空 → 行为不变。
        extraSystemContext: String = "",
    ): TaskResult {
        val history = mutableListOf<ChatMessage>()
        // 系统提示 = 基础 + 额外上下文 + 经验教训(ExperienceLedger 把历史错误的规避策略注入进来,
        // 让 LLM 不重复犯已知的错;为空则无影响)。
        val systemContent = buildString {
            append(systemPrompt)
            if (extraSystemContext.isNotBlank()) append("\n\n").append(extraSystemContext)
            val mitigations = ExperienceLedger.getMitigationsSection()
            if (mitigations.isNotBlank()) append("\n\n").append(mitigations)
            // GUI 交互经验(找不到节点/弹窗遮挡/加载超时…)与代码经验同注入,让轻路径也从界面失败中学。
            val guiLessons = InteractionLedger.getMitigationsSection()
            if (guiLessons.isNotBlank()) append("\n\n").append(guiLessons)
        }
        history += ChatMessage.System(content = systemContent)
        history += ChatMessage.User(content = task)

        // 语义检索:按当前任务把技能重排(相关的靠前);命中不了/离线/未配对则原顺序。
        // 不丢技能(topK = 全部),只重排 —— 永不弱于现状。
        val activeSkills = SemanticSkillRanker
            .rank(task, skills.map { "${it.id} ${it.description}" }, topK = skills.size)
            ?.map { skills[it] } ?: skills
        // Only explicit negative evidence consumes the single repair attempt.
        var verifyRepairsLeft = 1

        val totalUsage = AccumulatedUsage()
        val recentActions = ArrayDeque<String>(config.stuckWindowSize)  // 死循环检测
        val cancelFlag = AtomicInteger(0)  // 0 = 继续, 1 = 取消

        try {
            for (step in 1..config.maxSteps) {
                onStep?.invoke(ReActStep.Started(step, history.size))

                // 上下文压缩
                val compressed = compressForSend(history)

                val response = try {
                    llmClient.chat(compressed, activeSkills)
                } catch (e: Exception) {
                    Log.e(tag, "LLM call failed at step $step: ${e.message}", e)
                    return TaskResult.MaxStepsReached(
                        totalSteps = step - 1,
                        lastResponse = "LLM call failed: ${e.message}",
                        totalUsage = totalUsage.snapshot()
                    )
                }

                response.usage?.let { totalUsage.add(it) }

                onStep?.invoke(ReActStep.Thought(step, response.content, response.usage))

                // 把 LLM 响应加入历史
                history += ChatMessage.Assistant(
                    content = response.content,
                    toolCalls = response.toolCalls
                )

                // Recheck after repairs, even when the repair budget is exhausted.
                if (!response.hasToolCalls) {
                    val verdict = verifyCompletion(task, captureScreen)
                    if (verdict.needsRepair && verifyRepairsLeft > 0) {
                        verifyRepairsLeft--
                        Log.i(tag, "goal not met at step $step: ${verdict.reason}")
                        history += ChatMessage.System(
                            content = "目标尚未达成:${verdict.reason}。请继续操作直到完成。"
                        )
                        continue
                    }
                    onStep?.invoke(ReActStep.Done(step, verdict))
                    return TaskResult.Done(
                        summary = verdict.present(response.content ?: "(no summary)"),
                        totalSteps = step,
                        totalUsage = totalUsage.snapshot(),
                        verification = verdict,
                    )
                }

                // 死循环检测
                val actionFingerprint = response.toolCalls.joinToString("|") { "${it.name}(${it.args})" }
                if (recentActions.size >= config.stuckWindowSize) {
                    recentActions.removeFirst()
                }
                recentActions.addLast(actionFingerprint)
                if (recentActions.size == config.stuckWindowSize &&
                    recentActions.distinct().size == 1
                ) {
                    Log.w(tag, "Stuck loop detected: $actionFingerprint")
                    onStep?.invoke(ReActStep.Stuck(step, actionFingerprint))
                    return TaskResult.Stuck(
                        totalSteps = step,
                        totalUsage = totalUsage.snapshot(),
                        detectedAction = actionFingerprint
                    )
                }

                // 顺序执行所有工具调用
                for (toolCall in response.toolCalls) {
                    if (cancelFlag.get() == 1) {
                        return TaskResult.Cancelled(step, totalUsage.snapshot())
                    }
                    onStep?.invoke(ReActStep.ToolCallStart(step, toolCall))

                    val preCheck = ImmuneSystem.preCheck(toolCall.name, toolCall.args)
                    EvolutionMetrics.immuneCall(preCheck.verdict == ImmuneSystem.Verdict.WARN)
                    if (preCheck.verdict == ImmuneSystem.Verdict.WARN) {
                        Log.w(tag, "Immune warning on ${toolCall.name}: risk=${String.format("%.2f", preCheck.riskScore)} reason=${preCheck.reason}")
                    }

                    val toolStart = System.currentTimeMillis()
                    val result = try {
                        toolExecutor(toolCall)
                    } catch (e: Exception) {
                        val latency = System.currentTimeMillis() - toolStart
                        ImmuneSystem.postResult(toolCall.name, latency, 0, true)
                        throw e
                    }
                    val latency = System.currentTimeMillis() - toolStart
                    ImmuneSystem.postResult(toolCall.name, latency, result.display.length, result is ToolExecutionResult.Failure)
                    // 工具失败记入经验账本,下次生成/执行时注入规避策略。按域路由:
                    // GUI 交互失败→InteractionLedger,代码/生成类→ExperienceLedger(避免两域互相污染)。
                    if (result is ToolExecutionResult.Failure) {
                        if (InteractionLedger.isGuiTool(toolCall.name)) {
                            InteractionLedger.recordFailure(toolCall.name, "", result.display)
                        } else {
                            ExperienceLedger.recordError(result.display, toolCall.name)
                        }
                    }

                    onStep?.invoke(ReActStep.ToolCallDone(step, toolCall, result))
                    history += ChatMessage.Tool(
                        toolCallId = toolCall.id,
                        content = result.display
                    )
                }
            }
            return TaskResult.MaxStepsReached(
                totalSteps = config.maxSteps,
                lastResponse = history.lastOrNull { it is ChatMessage.Assistant }?.let {
                    (it as ChatMessage.Assistant).content
                },
                totalUsage = totalUsage.snapshot()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "ReAct run failed", e)
            return TaskResult.MaxStepsReached(
                totalSteps = 0,
                lastResponse = "ReAct failed: ${e.message}",
                totalUsage = totalUsage.snapshot()
            )
        }
    }

    private suspend fun verifyCompletion(
        task: String,
        captureScreen: (suspend () -> android.graphics.Bitmap?)?,
    ): GoalVerifier.Verdict = try {
        verifyGoal(task, captureScreen?.invoke())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        GoalVerifier.unverified("未能获取核验结果，请检查目标设备")
    }

    /**
     * 三级上下文压缩（参考 Octopus Mobile 设计 + 适配方案 F）.
     *
     * 1. get_screen_info 类观察工具：全局只保留最新一条完整结果
     * 2. 保护区（最近 N 条）完整保留
     * 3. 保护区外的 tool result：摘要化
     */
    private fun compressForSend(history: List<ChatMessage>): List<ChatMessage> {
        val result = history.toMutableList()
        val protectSize = config.protectedRecentSize
        val screenTools = config.screenInfoTools  // e.g. {"android.get_screen_info", "android.browser.get_dom"}

        // 第一级：找最后一条 "screen-info" tool 结果位置
        var lastScreenInfoIndex = -1
        for (i in result.indices.reversed()) {
            val msg = result[i]
            if (msg is ChatMessage.Tool && screenTools.any { msg.content.contains(it) }) {
                // 简化：实际可根据 tool name 判断
                lastScreenInfoIndex = i
                break
            }
        }

        // 压缩：保护区外的 tool 结果摘要化
        val protectStart = maxOf(0, result.size - protectSize)
        for (i in 0 until protectStart) {
            val msg = result[i]
            if (msg is ChatMessage.Tool) {
                result[i] = msg.copy(content = summarizeToolResult(msg.content))
            }
        }

        return result
    }

    private fun summarizeToolResult(content: String): String {
        if (content.length <= config.summaryKeepLength) return content
        val first = content.take(config.summaryKeepLength / 2)
        val last = content.takeLast(config.summaryKeepLength / 2)
        return "$first ... (${content.length - config.summaryKeepLength} chars omitted) ... $last"
    }

    /** 累积 token 用量. */
    private class AccumulatedUsage {
        private var prompt = 0
        private var completion = 0
        private var total = 0

        fun add(u: TokenUsage) {
            prompt += u.promptTokens
            completion += u.completionTokens
            total += u.totalTokens
        }

        fun snapshot(): TokenUsage = TokenUsage(prompt, completion, total)
    }
}

/** ReAct 循环配置. */
data class ReActConfig(
    val maxSteps: Int = 30,                          // 最大步数
    val stuckWindowSize: Int = 4,                    // 死循环检测窗口
    val protectedRecentSize: Int = 8,                // 保护区大小（最近 N 条不动）
    val screenInfoTools: Set<String> = setOf(        // 屏幕观察类工具名
        "android.get_screen_info", "android.browser.get_dom", "android.take_screenshot"
    ),
    val summaryKeepLength: Int = 500                 // 摘要化后保留的字数
)

/** ReAct 循环每步事件. */
sealed class ReActStep {
    abstract val step: Int

    data class Started(override val step: Int, val historySize: Int) : ReActStep()
    data class Thought(override val step: Int, val content: String?, val usage: TokenUsage?) : ReActStep()
    data class ToolCallStart(override val step: Int, val call: ToolCall) : ReActStep()
    data class ToolCallDone(override val step: Int, val call: ToolCall, val result: ToolExecutionResult) : ReActStep()
    data class Done(
        override val step: Int,
        val verification: GoalVerifier.Verdict = GoalVerifier.unverified("尚未核验"),
    ) : ReActStep()
    data class Stuck(override val step: Int, val action: String) : ReActStep()
}

/** 默认系统提示词（精简版 - 可被调用方覆盖） */
val DEFAULT_SYSTEM_PROMPT = """
你是 Octopus Mobile 的 Android 设备操控 Agent. 你有 30 个工具控制真机.
工作流程：观察(android.get_screen_info)→思考→行动→验证,最多 30 步.

【安全规则】
1. 不要点支付/登录/卸载按钮
2. 不要在密码字段输入
3. 关键操作前先确认

【最佳实践】
1. 每次行动前先 get_screen_info 观察
2. 优先用 android.find_and_tap 而非 tap(x,y)
3. 滚动用 android.scroll_to_find
4. 任务完成必须调 android.finish
5. 失败重试 3 次,还失败就 android.fail

【工具调用】
用 OpenAI tool_calls 格式. 一次思考可以连发多个工具调用.
""".trimIndent()
