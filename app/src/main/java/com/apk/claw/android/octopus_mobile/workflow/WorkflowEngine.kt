package com.apk.claw.android.octopus_mobile.workflow

import android.content.Context
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ChatMessage
import com.apk.claw.android.octopus_mobile.LightweightLlmClient
import com.apk.claw.android.octopus_mobile.LlmConfig
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 工作流执行引擎：顺序执行步骤，支持变量传递和条件分支。
 *
 * MVP 实现：
 * - TOOL 步骤：调用 ToolRegistry.executeTool（走完整安全闸门：来源闸门/SafetyGate/CircuitBreaker/Guardrail/DryRunGate/审计）
 * - PROMPT 步骤：通过 [onPrompt] 回调调 LLM；调用方未注入时用 [defaultPromptCallback] 跑一次性 chat
 * - CONDITION 步骤：简单条件判断（支持 ==/!=/>/</contains）
 *
 * 不做的事（后续迭代）：
 * - 并行步骤
 * - 循环
 * - 复杂表达式（MVEL/SpEL）
 */
class WorkflowEngine(
    private val context: Context,
    /** PROMPT 步骤的执行回调：返回 AI 的回答文本。默认用 [defaultPromptCallback] 调 LightweightLlmClient。 */
    private val onPrompt: suspend (String) -> String = defaultPromptCallback(context),
    /**
     * 是否将工具调用标记为"不可信来源"。
     * - 定时触发（[com.apk.claw.android.service.WorkflowAlarmReceiver]）无人值守 → true，HIGH/MEDIUM 工具走 ApprovalFlow；
     * - 用户手动触发（[com.apk.claw.android.ui.featurescreens.WorkflowsActivity]）用户在场 → false，正常放行。
     */
    private val untrustedSource: Boolean = false,
) {
    private val tag = "WorkflowEngine"

    suspend fun execute(workflow: Workflow): WorkflowResult {
        val ctx = WorkflowContext()
        val startMs = System.currentTimeMillis()
        val steps = workflow.steps
        XLog.i(tag, "Workflow start: ${workflow.name} (${steps.size} steps)")

        // Branches may jump back, so a run is capped: a loop that never meets its exit condition
        // stops with an error instead of running (and spending model calls) forever.
        val budget = steps.size * MAX_RUNS_PER_STEP
        var cursor = 0
        var executed = 0
        var failure: String? = null
        while (failure == null && cursor in steps.indices) {
            val step = steps[cursor]
            val stepStart = System.currentTimeMillis()
            if (executed++ >= budget) {
                failure = context.getString(R.string.workflow_err_loop, budget)
            } else {
                try {
                    cursor = runStep(steps, cursor, ctx, stepStart)
                } catch (e: Throwable) {
                    failure = logFailure(step, e, ctx, stepStart)
                }
            }
        }

        return WorkflowResult(
            success = failure == null,
            logs = ctx.logs,
            finalOutput = if (failure == null) ctx.variables.values.lastOrNull() else null,
            durationMs = System.currentTimeMillis() - startMs,
            error = failure,
        )
    }

    /** Logs a failed step and returns the run's error line. */
    private fun logFailure(step: WorkflowStep, e: Throwable, ctx: WorkflowContext, stepStart: Long): String {
        XLog.e(tag, "Step failed: ${step.name}", e)
        val reason = e.message ?: e.javaClass.simpleName
        ctx.logs.add(WorkflowContext.StepLog(
            stepId = step.id,
            stepName = step.name,
            success = false,
            output = "",
            durationMs = System.currentTimeMillis() - stepStart,
            error = reason,
        ))
        return context.getString(R.string.workflow_err_step_failed, step.name, reason)
    }

    /** Runs the step at [cursor], logs it and returns the index of the step to run next. */
    private suspend fun runStep(steps: List<WorkflowStep>, cursor: Int, ctx: WorkflowContext, stepStart: Long): Int {
        val step = steps[cursor]
        var next = cursor + 1
        val output = when (step.type) {
            WorkflowStep.StepType.TOOL -> executeTool(step, ctx)
            WorkflowStep.StepType.PROMPT -> executePrompt(step, ctx)
            WorkflowStep.StepType.CONDITION -> {
                val target = evaluateCondition(step, ctx)
                if (target == null) {
                    context.getString(R.string.workflow_log_branch_none)
                } else {
                    next = WorkflowBranches.resolve(steps, target)
                        ?: error(context.getString(R.string.workflow_err_branch_target, target))
                    context.getString(R.string.workflow_log_branch_to, WorkflowBranches.describe(steps, next))
                }
            }
        }
        // 存输出到变量（条件步骤只决定去向，不产生输出）
        if (step.type != WorkflowStep.StepType.CONDITION) step.outputVar?.let { ctx.variables[it] = output }
        ctx.logs.add(WorkflowContext.StepLog(
            stepId = step.id,
            stepName = step.name,
            success = true,
            output = output.take(LOG_OUTPUT_MAX),
            durationMs = System.currentTimeMillis() - stepStart,
        ))
        return next
    }

    /** 执行工具步骤 —— 走 ToolRegistry.executeTool 完整安全闸门（不绕过） */
    private fun executeTool(step: WorkflowStep, ctx: WorkflowContext): String {
        val toolName = ctx.resolveTemplate(step.content)
        // 模板替换后的参数；非 String 值（Int/Boolean 等）原样保留
        val params: Map<String, Any> = step.params
            .mapValues { (_, v) -> ctx.resolveTemplate(v) as Any }

        // 定时触发等无人值守场景标记为不可信来源，HIGH/MEDIUM 工具走 ApprovalFlow；
        // 手动触发用户在场则按本地可信来源放行。
        val result = if (untrustedSource) {
            ToolRegistry.withUntrustedSource {
                ToolRegistry.getInstance().executeTool(toolName, params)
            }
        } else {
            ToolRegistry.getInstance().executeTool(toolName, params)
        }
        check(result.isSuccess) { result.error ?: context.getString(R.string.workflow_err_tool_failed, toolName) }
        return result.data ?: ""
    }

    /** 执行 PROMPT 步骤（调用 Agent） */
    private suspend fun executePrompt(step: WorkflowStep, ctx: WorkflowContext): String {
        val prompt = ctx.resolveTemplate(step.content)
        return onPrompt(prompt)
    }

    /** The target of the first branch whose condition holds, in the order written; null when none does. */
    private fun evaluateCondition(step: WorkflowStep, ctx: WorkflowContext): String? =
        step.branches.entries.firstOrNull { (expr, _) -> WorkflowCondition.holds(expr, ctx) }?.value

    companion object {
        /** How many times one step may run in a single run before the loop guard stops it. */
        const val MAX_RUNS_PER_STEP = 50
        private const val LOG_OUTPUT_MAX = 500

        /**
         * 默认 PROMPT 步骤回调：用 [LightweightLlmClient] 跑一次性 chat。
         *
         * 适用于无 Agent 上下文的场景（如定时触发）：
         * - 不带工具规格（PROMPT 步骤是纯文本生成，不应触发工具调用）
         * - 不带历史（每步独立，工作流的"上下文"由 ${var} 变量传递）
         * - 温度 0.3，1024 token 上限，足够大多数摘要/分类/抽取任务
         *
         * 调用方显式注入 onPrompt 时（如 ChatAgentBridge 内复用 Agent 上下文）优先用调用方版本。
         */
        fun defaultPromptCallback(context: Context): suspend (String) -> String = { prompt ->
            val apiKey = KVUtils.getLlmApiKey().trim()
            check(apiKey.isNotEmpty()) { context.getString(R.string.workflow_err_no_api_key) }
            val base = KVUtils.getLlmBaseUrl().trim().ifEmpty { "https://api.deepseek.com/v1" }
            val model = KVUtils.getLlmModelName().trim().ifEmpty { "deepseek-chat" }
            val apiUrl = if (base.endsWith("/chat/completions")) base
            else base.trimEnd('/') + "/chat/completions"

            val config = LlmConfig(
                apiUrl = apiUrl,
                apiKey = apiKey,
                model = model,
                temperature = 0.3,
                maxTokens = 1024,
            )
            val resp = LightweightLlmClient(config).chat(
                messages = listOf(ChatMessage.User(content = prompt)),
                skills = emptyList(),
            )
            resp.content?.trim().orEmpty().ifEmpty { context.getString(R.string.workflow_prompt_empty_reply) }
        }
    }
}
