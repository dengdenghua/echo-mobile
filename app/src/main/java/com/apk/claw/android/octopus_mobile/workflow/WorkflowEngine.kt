package com.apk.claw.android.octopus_mobile.workflow

import android.content.Context
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.XLog

/**
 * 工作流执行引擎：顺序执行步骤，支持变量传递和条件分支。
 *
 * MVP 实现：
 * - TOOL 步骤：调用 ToolRegistry 执行工具
 * - PROMPT 步骤：标记为待 Agent 执行（由调用方注入回调）
 * - CONDITION 步骤：简单条件判断（支持 ==/!=/>/</contains）
 *
 * 不做的事（后续迭代）：
 * - 并行步骤
 * - 循环
 * - 复杂表达式（MVEL/SpEL）
 */
class WorkflowEngine(
    private val context: Context,
    /** PROMPT 步骤的执行回调：返回 AI 的回答文本 */
    private val onPrompt: suspend (String) -> String = { throw UnsupportedOperationException("PROMPT step needs onPrompt callback") },
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

    /** 执行工具步骤 */
    private fun executeTool(step: WorkflowStep, ctx: WorkflowContext): String {
        val toolName = ctx.resolveTemplate(step.content)
        val tool = ToolRegistry.getInstance().getTool(toolName)
            ?: throw IllegalStateException("Tool not found: $toolName")

        // 解析参数（模板替换）
        val params = step.params.mapValues { (_, v) -> ctx.resolveTemplate(v) }
            .mapKeys { it.key }
        @Suppress("UNCHECKED_CAST")
        val paramsMap: Map<String, Any> = params.mapValues { it.value as Any }

        val result = tool.execute(paramsMap)
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
}
