package com.apk.claw.android.tool.impl

import com.apk.claw.android.media.MediaRepository
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.tool.ToolErr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 查询生视频任务结果。配合 [GenerateVideoTool]:agent 提交后凭 task_id 轮询,
 * 直到状态完成拿到视频链接。只读、幂等(可安全多次查询)。
 */
class CheckVideoTool : BaseTool() {

    override fun getName(): String = "check_video"

    override fun getDisplayName(): String = if (useChineseDescription) "查询视频" else "Check Video"

    override fun getDescriptionEN(): String =
        "Check the status/result of a text-to-video task by its task_id (from generate_video). " +
            "Returns the video URL when completed."

    override fun getDescriptionCN(): String =
        "根据 task_id(来自 generate_video)查询视频生成任务的状态/结果,完成时返回视频链接。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "task_id",
            "string",
            "The video task id returned by generate_video.",
            true,
        ),
    )

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }

    override fun execute(params: Map<String, Any>): ToolResult {
        currentCancellationToken()?.checkCancelled()
        val taskId = params["task_id"]?.toString()?.trim().orEmpty()
        if (taskId.isEmpty()) return ToolResult.error("缺少 task_id", ToolErr.INVALID_PARAM)
        return runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) {
                MediaRepository.pollVideo(taskId).fold(
                    onSuccess = { t ->
                        when {
                            t.isDone && !t.url.isNullOrEmpty() -> ToolResult.success("视频已生成:${t.url}")
                            t.isDone -> ToolResult.success("视频已完成(task_id=${t.taskId}),但暂未取到下载链接。")
                            t.isFailed -> ToolResult.error(t.error ?: "视频生成失败", ToolErr.UPSTREAM)
                            else -> {
                                val pct = if (t.progress > 0) ",${t.progress}%" else ""
                                ToolResult.success("视频仍在生成中(状态:${t.status}$pct),请稍后再查。")
                            }
                        }
                    },
                    onFailure = { e -> ToolResult.error(e.message ?: "查询视频失败", ToolErr.UPSTREAM) },
                )
            } ?: ToolResult.error("查询视频状态超时(15s)", ToolErr.TIMEOUT)
        }
    }
}
