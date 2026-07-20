package com.apk.claw.android.octopus_mobile.workflow

import android.content.Context
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
        XLog.i(tag, "Workflow start: ${workflow.name} (${workflow.steps.size} steps)")

        // stepId → index 映射，用于条件跳转
        val stepIndex = workflow.steps.withIndex().associate { (i, s) -> s.id to i }

        var cursor = 0
        while (cursor in workflow.steps.indices) {
            val step = workflow.steps[cursor]
            val stepStart = System.currentTimeMillis()

            try {
                val output = when (step.type) {
                    WorkflowStep.StepType.TOOL -> executeTool(step, ctx)
                    WorkflowStep.StepType.PROMPT -> executePrompt(step, ctx)
                    WorkflowStep.StepType.CONDITION -> {
                        // 条件分支：返回目标 stepId 或空
                        val target = evaluateCondition(step, ctx)
                        ctx.logs.add(WorkflowContext.StepLog(
                            stepId = step.id,
                            stepName = step.name,
                            success = true,
                            output = "branch -> ${target ?: "next"}",
                            durationMs = System.currentTimeMillis() - stepStart,
                        ))
                        if (target != null) {
                            cursor = stepIndex[target] ?: (cursor + 1)
                        } else {
                            cursor++
                        }
                        continue
                    }
                }

                // 存输出到变量
                step.outputVar?.let { ctx.variables[it] = output }
                ctx.logs.add(WorkflowContext.StepLog(
                    stepId = step.id,
                    stepName = step.name,
                    success = true,
                    output = output.take(500),  // 日志截断
                    durationMs = System.currentTimeMillis() - stepStart,
                ))
                cursor++
            } catch (e: Throwable) {
                XLog.e(tag, "Step failed: ${step.name}", e)
                ctx.logs.add(WorkflowContext.StepLog(
                    stepId = step.id,
                    stepName = step.name,
                    success = false,
                    output = "",
                    durationMs = System.currentTimeMillis() - stepStart,
                    error = e.message ?: e.javaClass.simpleName,
                ))
                return WorkflowResult(
                    success = false,
                    logs = ctx.logs,
                    finalOutput = null,
                    durationMs = System.currentTimeMillis() - startMs,
                    error = "Step '${step.name}' failed: ${e.message}",
                )
            }
        }

        val finalOutput = ctx.variables.values.lastOrNull()
        return WorkflowResult(
            success = true,
            logs = ctx.logs,
            finalOutput = finalOutput,
            durationMs = System.currentTimeMillis() - startMs,
        )
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
        if (!result.isSuccess) {
            throw IllegalStateException(result.error ?: "Tool $toolName failed")
        }
        return result.data ?: ""
    }

    /** 执行 PROMPT 步骤（调用 Agent） */
    private suspend fun executePrompt(step: WorkflowStep, ctx: WorkflowContext): String {
        val prompt = ctx.resolveTemplate(step.content)
        return onPrompt(prompt)
    }

    /** 简单条件求值：支持 ${var} == "value" / != / contains / > / < */
    private fun evaluateCondition(step: WorkflowStep, ctx: WorkflowContext): String? {
        for ((expr, target) in step.branches) {
            val resolved = ctx.resolveTemplate(expr)
            if (evalSimpleExpr(resolved)) return target
        }
        return null
    }

    private fun evalSimpleExpr(expr: String): Boolean {
        // 简易表达式求值：支持 == / != / contains / > / <
        val trimmed = expr.trim()
        return when {
            trimmed.contains("==") -> {
                val (l, r) = trimmed.split("==", limit = 2).map { it.trim() }
                l == r
            }
            trimmed.contains("!=") -> {
                val (l, r) = trimmed.split("!=", limit = 2).map { it.trim() }
                l != r
            }
            trimmed.contains("contains") -> {
                val (l, r) = trimmed.split("contains", limit = 2).map { it.trim() }
                l.contains(r)
            }
            trimmed.contains(">") -> {
                val (l, r) = trimmed.split(">", limit = 2).map { it.trim() }
                (l.toDoubleOrNull() ?: 0.0) > (r.toDoubleOrNull() ?: 0.0)
            }
            trimmed.contains("<") -> {
                val (l, r) = trimmed.split("<", limit = 2).map { it.trim() }
                (l.toDoubleOrNull() ?: 0.0) < (r.toDoubleOrNull() ?: 0.0)
            }
            else -> trimmed.isNotEmpty() && trimmed != "false" && trimmed != "0"
        }
    }

    companion object {
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
            check(apiKey.isNotEmpty()) { "未配置 LLM API Key，PROMPT 步骤无法执行" }
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
            resp.content?.trim().orEmpty().ifEmpty { "(模型未返回内容)" }
        }
    }
}
