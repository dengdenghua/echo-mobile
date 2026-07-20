package com.apk.claw.android.octopus_mobile.workflow

import android.content.Context
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 工作流数据模型：多步骤编排。
 *
 * 对标 Operit 工作流：把多个工具/prompt 组合成流程。
 * MVP 先做数据模型 + 顺序执行引擎，UI 后续补。
 *
 * 与 RoutineStore 的区别：
 * - Routine 是单步指令重放（语义重放）
 * - Workflow 是多步串联（DAG），支持步骤间变量传递
 */
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val steps: List<WorkflowStep>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0L,
    val runCount: Int = 0,
    /** 定时触发：小时（24 小时制，null = 未定时） */
    val scheduleHour: Int? = null,
    /** 定时触发：分钟（null = 未定时） */
    val scheduleMinute: Int? = null,
    /** 是否每天重复；false = 仅触发一次后清除 */
    val scheduleDaily: Boolean = false,
) {
    /** 是否已设定定时 */
    val isScheduled: Boolean get() = scheduleHour != null && scheduleMinute != null
}

/**
 * 工作流步骤。
 *
 * 三种类型：
 * - TOOL: 直接调用工具（如 take_screenshot）
 * - PROMPT: 让 Agent 执行自然语言指令（复用 Agent loop）
 * - CONDITION: 条件分支（根据上一步结果决定走哪个分支）
 */
data class WorkflowStep(
    val id: String = UUID.randomUUID().toString(),
    val type: StepType,
    val name: String,
    /** TOOL: 工具名；PROMPT: 自然语言指令；CONDITION: 条件表达式 */
    val content: String,
    /** 工具参数（key-value） */
    val params: Map<String, String> = emptyMap(),
    /** 输出变量名：本步结果存入 [WorkflowContext.variables]，供后续步骤引用 */
    val outputVar: String? = null,
    /** 条件分支：type=CONDITION 时使用，key=条件表达式，value=跳转的 stepId */
    val branches: Map<String, String> = emptyMap(),
    /** 超时毫秒，0=不限 */
    val timeoutMs: Long = 0L,
) {
    enum class StepType { TOOL, PROMPT, CONDITION }
}

/** 工作流执行上下文：步骤间变量传递 */
class WorkflowContext {
    val variables: MutableMap<String, String> = mutableMapOf()
    val logs: MutableList<StepLog> = mutableListOf()

    data class StepLog(
        val stepId: String,
        val stepName: String,
        val success: Boolean,
        val output: String,
        val durationMs: Long,
        val error: String? = null,
    )

    fun resolveTemplate(text: String): String {
        // 替换 ${varName} 为上下文变量值
        val regex = Regex("\\$\\{(\\w+)}")
        return regex.replace(text) { m ->
            variables[m.groupValues[1]] ?: m.value
        }
    }
}

/**
 * 工作流存储：CRUD + 持久化（KVUtils）。
 */
object WorkflowStore {
    private const val KEY = "workflows_v1"
    private const val MAX_KEEP = 50
    private val gson = Gson()

    fun all(context: Context): List<Workflow> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Workflow>>() {}.type
            gson.fromJson<List<Workflow>>(json, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): Workflow? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, workflow: Workflow): Workflow {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == workflow.id }
        if (idx >= 0) list[idx] = workflow else list.add(0, workflow)
        persist(context, list)
        return workflow
    }

    fun delete(context: Context, id: String): Boolean {
        val list = all(context).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) persist(context, list)
        return removed
    }

    /** 导出为 JSON 字符串 */
    fun export(workflow: Workflow): String = gson.toJson(workflow)

    /** 从 JSON 导入 */
    fun import(context: Context, json: String): Workflow? {
        return try {
            val wf = gson.fromJson(json, Workflow::class.java)
            val newWf = wf.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(), lastRunAt = 0L, runCount = 0)
            save(context, newWf)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persist(context: Context, list: List<Workflow>) {
        val trimmed = if (list.size > MAX_KEEP) list.subList(0, MAX_KEEP) else list
        KVUtils.putString(KEY, gson.toJson(trimmed))
    }
}

/**
 * 工作流执行结果。
 */
data class WorkflowResult(
    val success: Boolean,
    val logs: List<WorkflowContext.StepLog>,
    val finalOutput: String?,
    val durationMs: Long,
    val error: String? = null,
)
