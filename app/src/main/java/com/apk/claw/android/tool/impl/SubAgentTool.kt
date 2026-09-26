package com.apk.claw.android.tool.impl

import com.apk.claw.android.octopus_mobile.GoalVerifier
import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentServiceFactory
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 子 Agent 工具 —— 让主 Agent 派生子 Agent 执行复杂子任务。
 *
 * 设计理念（参考 Mobile-Agent v2 多 Agent 分工）：
 * - 主 Agent 负责任务分解和高层规划
 * - 子 Agent 负责具体子任务的逐步执行（独立 ReAct 循环）
 * - 子 Agent 结果返回给主 Agent，主 Agent 据此决策下一步
 *
 * 实现细节：
 * - 子 Agent 是独立的 DefaultAgentService 实例（有自己的 running 锁和 executor）
 * - 子 Agent 使用简化系统提示词（聚焦执行，不含完整规则集）
 * - 子 Agent 跳过 TaskCheckpoint（避免与主 Agent 的 checkpoint 冲突）
 * - 子 Agent 的 callback 不转发到 UI（静默执行）
 * - 子 Agent 最大迭代数默认 20（可通过参数调整）
 * - 子 Agent 共享主 Agent 的 LLM 配置和工具集
 */
class SubAgentTool : BaseTool() {

    companion object {
        private const val TAG = "SubAgentTool"
        private const val DEFAULT_MAX_ITERATIONS = 20
        private const val MAX_ITERATIONS_LIMIT = 50
        private const val SUB_AGENT_TIMEOUT_MS = 120_000L  // 2 分钟超时
        private const val LOG_TASK_PREVIEW_CHARS = 80
        private const val LOG_CONTENT_PREVIEW_CHARS = 60
        private const val MS_PER_SECOND = 1000

        /** 子 Agent 专用简化系统提示词 */
        private const val SUB_AGENT_SYSTEM_PROMPT = """## ROLE
你是一个执行具体子任务的 Android 自动化子助手。你收到主助手分配的一个明确子任务，需要高效完成它。

## 执行协议
1. 感知 —— 每轮自动收到屏幕截图，或调用 get_screen_info 获取无障碍树
2. 思考 —— 分析当前屏幕状态，决定下一步操作
3. 行动 —— 调用工具执行操作
4. 完成 —— 子任务完成后立即调用 finish(summary) 返回结果

## 核心规则
- 专注完成分配的子任务，不要扩展到其他任务
- 操作前先观察屏幕状态（截图或 get_screen_info）
- 遇到弹窗先关闭，遇到登录/付费墙立即 finish 报告
- 同一步骤连续 3 次失败 → back 回退或 finish 报告无法完成
- 完成后 finish 的 summary 要描述具体做了什么、结果是什么
"""
    }

    override fun getName(): String = "spawn_subagent"

    override fun getDisplayName(): String = if (useChineseDescription) "子Agent" else "Sub-Agent"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "task",
            "string",
            "The specific sub-task for the sub-agent to execute. Should be a clear, self-contained instruction.",
            true
        ),
        ToolParameter(
            "max_iterations",
            "integer",
            "Maximum iterations for the sub-agent (default 20, max 50). Keep small for focused tasks.",
            false
        )
    )

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun execute(params: Map<String, Any>): ToolResult {
        val task = requireString(params, "task")
        val maxIterations = (params["max_iterations"] as? Number)?.toInt()
            ?.coerceIn(1, MAX_ITERATIONS_LIMIT) ?: DEFAULT_MAX_ITERATIONS

        XLog.i(
            TAG,
            "Spawning sub-agent for task: ${task.take(LOG_TASK_PREVIEW_CHARS)}... (maxIterations=$maxIterations)"
        )

        // 获取主 Agent 的 LLM 配置（通过 TaskOrchestrator 全局引用）
        val orchestrator = com.apk.claw.android.TaskOrchestrator.current
        if (orchestrator == null) {
            return ToolResult.error("无法获取 Agent 配置：TaskOrchestrator 未初始化")
        }

        val parentConfig = orchestrator.getCurrentAgentConfig()
        val childConfig = parentConfig.copy(
            systemPrompt = SUB_AGENT_SYSTEM_PROMPT,
            maxIterations = maxIterations,
            dynamicPromptSuffix = "",  // 子 Agent 不需要主 Agent 的教训
            memoryPromptSuffix = "",   // 子 Agent 不需要跨会话记忆
            skipCheckpoint = true,     // 子 Agent 跳过 checkpoint 避免与主 Agent 冲突
        )

        // 创建子 Agent 实例
        val subAgent = AgentServiceFactory.create()
        subAgent.initialize(childConfig)

        // 用 CountDownLatch + AtomicReference 同步等待子 Agent 完成
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Pair<Boolean, String>>(null)  // (isSuccess, message)

        val callback = object : AgentCallback {
            override fun onLoopStart(round: Int) {
                XLog.d(TAG, "[sub-agent] round $round")
            }

            override fun onContent(round: Int, content: String) {
                XLog.d(TAG, "[sub-agent] content: ${content.take(LOG_CONTENT_PREVIEW_CHARS)}...")
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "[sub-agent] tool: $toolName")
            }

            override fun onToolResult(
                round: Int, toolId: String, toolName: String,
                parameters: String, result: ToolResult,
            ) {
                XLog.d(TAG, "[sub-agent] tool result: ${if (result.isSuccess) "ok" else "fail"}")
            }

            private var goalVerdict = GoalVerifier.unverified("尚未核验")

            override fun onGoalVerification(round: Int, verdict: GoalVerifier.Verdict) {
                goalVerdict = verdict
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                XLog.i(
                    TAG,
                    "[sub-agent] completed: ${finalAnswer.take(LOG_CONTENT_PREVIEW_CHARS)}... (tokens=$totalTokens)"
                )
                val evidence = org.json.JSONObject()
                    .put("execution_completed", true)
                    .put("goal_status", goalVerdict.status.name)
                    .put("goal_reason", goalVerdict.reason)
                    .put("response", finalAnswer)
                resultRef.set(true to evidence.toString())
                latch.countDown()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "[sub-agent] error: ${error.message}")
                resultRef.set(false to (error.message ?: "unknown error"))
                latch.countDown()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "[sub-agent] system dialog blocked")
                resultRef.set(false to "系统弹窗阻断，子 Agent 无法继续")
                latch.countDown()
            }
        }

        // 在当前线程执行子 Agent（工具执行线程是 Agent executor 的单线程）
        try {
            subAgent.executeTask(task, callback, untrusted = true)

            // 等待子 Agent 完成（带超时保护）
            if (!latch.await(SUB_AGENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                XLog.w(TAG, "[sub-agent] timed out after ${SUB_AGENT_TIMEOUT_MS}ms")
                subAgent.cancel()
                val timeoutSec = SUB_AGENT_TIMEOUT_MS / MS_PER_SECOND
                return ToolResult.error("子 Agent 超时（${timeoutSec}s），可能任务过于复杂。请尝试拆分为更小的子任务。")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "[sub-agent] execution failed", e)
            return ToolResult.error("子 Agent 执行失败: ${e.message}")
        } finally {
            runCatching { subAgent.shutdown() }
        }

        val (success, message) = resultRef.get() ?: return ToolResult.error("子 Agent 未返回结果")

        return if (success) {
            ToolResult.success(message)
        } else {
            ToolResult.error("子 Agent 报告失败: $message")
        }
    }

    override fun isIdempotent(): Boolean = false

    override fun getDescriptionEN(): String = """
        Spawn a sub-agent to execute a complex sub-task independently.

        The sub-agent runs its own ReAct loop (observe → think → act) with a simplified prompt
        focused on execution. It shares the same tools and LLM config as the main agent but
        has its own message history and iteration budget.

        Use this when:
        - A task has multiple independent phases (e.g. "search for X, then compose an email about it")
        - A sub-task requires many steps that would consume too many iterations of the main loop
        - You want to isolate a risky exploration without polluting the main context

        The sub-agent's final answer is returned as this tool's result.

        Parameters:
        - task: Clear, self-contained sub-task description
        - max_iterations: Max iterations for sub-agent (default 20, max 50)
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        派生子 Agent 独立执行复杂子任务。

        子 Agent 运行自己的 ReAct 循环（感知 → 思考 → 行动），使用简化的执行导向提示词。
        它共享主 Agent 的工具和 LLM 配置，但有独立的消息历史和迭代预算。

        适用场景：
        - 任务有多个独立阶段（如"搜索 X，然后写一封关于它的邮件"）
        - 子任务需要很多步骤，会消耗主循环过多迭代
        - 想隔离有风险的探索，不污染主上下文

        子 Agent 的最终结果作为本工具的返回值。

        参数：
        - task: 清晰、自包含的子任务描述
        - max_iterations: 子 Agent 最大迭代数（默认 20，上限 50）
    """.trimIndent()
}
