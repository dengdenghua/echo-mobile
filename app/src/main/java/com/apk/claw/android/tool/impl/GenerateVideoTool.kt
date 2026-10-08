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
 * 生视频工具(Agnes 增值)。视频是异步的(生成需 1-3 分钟,Agnes 全账号限 1/min),
 * 故本工具只**提交**任务并返回 task_id,不阻塞 agent;由 agent 之后用 [CheckVideoTool]
 * 凭 task_id 轮询结果。会员免费 / 非会员扣积分由服务端处理。
 */
class GenerateVideoTool : BaseTool() {

    override fun getName(): String = "generate_video"

    override fun getDisplayName(): String = if (useChineseDescription) "生成视频" else "Generate Video"

    override fun getDescriptionEN(): String =
        "Generate a short (~5s) video from a text prompt (text-to-video). Submits the job and returns a " +
            "task_id; generation takes 1-3 min, then use check_video to poll the result."

    override fun getDescriptionCN(): String =
        "根据文字描述生成短视频(约5秒,文生视频)。提交任务返回 task_id(生成需1-3分钟),之后用 check_video 查询结果。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "prompt",
            "string",
            "Text description of the short (~5s) video to generate.",
            true,
        ),
    )

    // 提交即扣积分,非幂等:失败不自动重试(避免重复提交/计费)。
    override fun isIdempotent(): Boolean = false

    companion object {
        private const val TIMEOUT_MS = 30_000L
    }

    override fun execute(params: Map<String, Any>): ToolResult {
        currentCancellationToken()?.checkCancelled()
        val prompt = params["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return ToolResult.error("缺少 prompt(视频描述)", ToolErr.INVALID_PARAM)
        return runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) {
                MediaRepository.submitVideo(prompt).fold(
                    onSuccess = { task ->
                        ToolResult.success(
                            "视频任务已提交(task_id=${task.taskId}),正在生成(约需 1-3 分钟)。" +
                                "稍后用 check_video 工具凭此 task_id 查询;完成后会返回视频链接。",
                        )
                    },
                    onFailure = { e -> ToolResult.error(e.message ?: "提交视频失败", ToolErr.UPSTREAM) },
                )
            } ?: ToolResult.error("提交视频请求超时(30s)", ToolErr.TIMEOUT)
        }
    }
}
