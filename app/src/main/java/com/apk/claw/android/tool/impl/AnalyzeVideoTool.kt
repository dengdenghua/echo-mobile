package com.apk.claw.android.tool.impl

import com.apk.claw.android.octopus_mobile.VideoAnalyzer
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.io.File

/**
 * analyze_video —— 视频理解工具.
 *
 * 从视频文件提取 N 个关键帧,批量喂给视觉模型,返回对视频内容的文字分析.
 *
 * **使用场景**:
 *  - "这段视频在讲什么"、"视频里出现了哪些文字"、"视频第几秒出现红色物体"
 *  - 用户发来的短视频、录屏、监控片段
 *
 * **限制**:
 *  - 仅支持本机路径(/sdcard/xxx.mp4 或 file:// content://)
 *  - 帧数上限 8,时长上限 60s(超过则只取前 60s)
 *  - 远程 URL 需先下载到本地再分析
 *
 * 复用 [VideoAnalyzer] + [VisionAnalyzer] 的 VLM 配置(视觉模型三项)。
 */
class AnalyzeVideoTool : BaseTool() {

    override fun getName(): String = "analyze_video"

    override fun getDisplayName(): String = "分析视频"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "path",
            "string",
            "视频文件的本地路径,如 /sdcard/Download/demo.mp4 或 content://media/...。远程 URL 需先下载到本地。",
            true,
        ),
        ToolParameter(
            "question",
            "string",
            "要让视觉模型回答的问题,如「视频在讲什么」「视频里有哪些文字」「第几秒出现红色物体」。留空则返回视频整体概述。",
            false,
        ),
        ToolParameter(
            "frame_count",
            "integer",
            "提取的关键帧数量,1-8。默认 4。帧越多信息越全但消耗 token 越多。",
            false,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!VideoAnalyzer.isConfigured()) {
            return ToolResult.error("未配置视觉模型,请到 设置 → 模型配置 填写视觉模型(或让主模型支持图片)")
        }

        val path = requireString(params, "path").trim()
        if (path.isEmpty()) {
            return ToolResult.error("视频路径不能为空")
        }
        // 简单存在性检查(本地路径);content:// URI 不校验
        if (!path.startsWith("content://") && !path.startsWith("file://")) {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return ToolResult.error("视频文件不存在: $path")
            }
            if (!file.canRead()) {
                return ToolResult.error("视频文件无法读取(权限不足): $path")
            }
        }

        val question = optionalString(
            params, "question",
            "请描述这段视频的内容,包括主要场景、出现的文字、关键事件及其大致时间点。",
        )
        val frameCount = optionalInt(params, "frame_count", VideoAnalyzer.DEFAULT_FRAME_COUNT)

        return try {
            val analysis = VideoAnalyzer.analyzeVideoBlocking(path, question, frameCount)
            ToolResult.success(analysis)
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "参数错误")
        } catch (e: Exception) {
            ToolResult.error("视频分析失败: ${e.message}")
        }
    }

    override fun getDescriptionEN(): String =
        "Analyze a local video file with a vision model. Extracts N keyframes " +
        "(default 4, max 8) and feeds them to the VLM in order. Use for: \"what's in " +
        "this video\", \"what text appears\", \"at what second does X happen\". " +
        "Local paths only (/sdcard/xxx.mp4 or content://); remote URLs must be downloaded first."

    override fun getDescriptionCN(): String =
        "用视觉模型分析本地视频文件。提取 N 个关键帧(默认 4 帧,上限 8)按时间顺序喂给 VLM。" +
        "用于「视频在讲什么」「视频里有哪些文字」「第几秒出现 X」等场景。" +
        "仅支持本地路径(/sdcard/xxx.mp4 或 content://),远程 URL 需先下载。"
}
