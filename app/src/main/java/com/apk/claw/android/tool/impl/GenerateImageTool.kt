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
 * 生图工具(Agnes 增值)。agent 在对话里按需调用:用户说"画/生成一张…"→出图。
 * 走服务端中转端点([MediaRepository]),会员免费 / 非会员扣积分由服务端处理。
 */
class GenerateImageTool : BaseTool() {

    override fun getName(): String = "generate_image"

    override fun getDisplayName(): String = if (useChineseDescription) "生成图片" else "Generate Image"

    override fun getDescriptionEN(): String =
        "Generate an image from a text prompt (text-to-image). Use when the user asks to draw, paint, or " +
            "generate a picture / image / illustration / poster / avatar. Returns an image URL."

    override fun getDescriptionCN(): String =
        "根据文字描述生成图片(文生图)。当用户要求画/生成/做一张图片、插画、海报、头像等时使用。返回图片链接。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "prompt",
            "string",
            "Text description of the image to generate (Chinese or English). Be specific about subject, style and lighting.",
            true,
        ),
        ToolParameter(
            "size",
            "string",
            "Optional image size, e.g. '1024x1024' (default), '1280x720', '720x1280'.",
            false,
        ),
    )

    // 生成会扣积分、产出新内容,非幂等:失败不自动重试(避免重复计费)。
    override fun isIdempotent(): Boolean = false

    companion object {
        private const val TIMEOUT_MS = 60_000L
    }

    override fun execute(params: Map<String, Any>): ToolResult {
        currentCancellationToken()?.checkCancelled()
        val prompt = params["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return ToolResult.error("缺少 prompt(图片描述)", ToolErr.INVALID_PARAM)
        val sizeRaw = params["size"]?.toString()?.trim()
        val size = if (sizeRaw.isNullOrEmpty()) "1024x1024" else sizeRaw
        return runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) {
                MediaRepository.generateImage(prompt, size).fold(
                    onSuccess = { img ->
                        ToolResult.success("已生成图片。\n![image](${img.url})\n图片链接:${img.url}")
                    },
                    onFailure = { e -> ToolResult.error(e.message ?: "生图失败", ToolErr.UPSTREAM) },
                )
            } ?: ToolResult.error("生成图片请求超时(60s)", ToolErr.TIMEOUT)
        }
    }
}
