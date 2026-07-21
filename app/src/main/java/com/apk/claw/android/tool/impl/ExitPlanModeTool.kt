package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * exit_plan_mode 工具 —— LLM 在 PLAN 模式下完成方案输出后调用此工具,
 * 经用户确认后由 DefaultAgentService 拦截处理切换到 DEFAULT 模式继续执行。
 *
 * 实际切换逻辑由 DefaultAgentService 在工具调用前拦截 tool name 实现,
 * 这里 execute 只返回成功占位,正常情况下不会被走到。
 */
class ExitPlanModeTool : BaseTool() {
    override fun getName(): String = "exit_plan_mode"

    override fun getDisplayName(): String = "Exit Plan Mode"

    override fun getDescriptionEN(): String =
        "Exit plan mode and switch to default mode after user confirmation. " +
            "Call this after you have presented a complete plan to the user."

    override fun getDescriptionCN(): String =
        "退出规划模式,经用户确认后切换到默认模式。在向用户呈现完整方案后调用此工具。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        // 实际切换由 DefaultAgentService 拦截处理,这里只返回成功占位
        return ToolResult.success("exit_plan_mode requested")
    }
}
