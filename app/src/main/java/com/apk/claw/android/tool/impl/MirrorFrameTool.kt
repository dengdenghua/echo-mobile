package com.apk.claw.android.tool.impl

import android.graphics.BitmapFactory
import android.util.Base64
import com.apk.claw.android.server.ScreenCaptureManager
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.google.gson.Gson

/** A bounded JPEG frame; does not save a screenshot on every refresh. */
class MirrorFrameTool : BaseTool() {
    companion object {
        private const val MAX_WIDTH = 720
        private const val QUALITY = 45
        private const val MAX_JPEG_BYTES = 360_000
    }
    private val capture = ScreenCaptureManager()
    override fun getName() = "mirror_frame"
    override fun getDisplayName() = "手机镜像画面"
    override fun getDescriptionEN() =
        "Capture a current local phone JPEG frame. Requires screen capture or accessibility permission."
    override fun getDescriptionCN() = "获取本机当前画面，需要手机已授予屏幕采集或无障碍权限。"
    override fun getParameters(): List<ToolParameter> = emptyList()
    @Synchronized
    override fun execute(params: Map<String, Any>): ToolResult {
        val jpeg = capture.captureScaledJpeg(MAX_WIDTH, QUALITY)
            ?: return ToolResult.error("无法获取画面，请在手机开启无障碍或屏幕采集；受保护画面不可读取")
        return if (jpeg.size > MAX_JPEG_BYTES) ToolResult.error("画面过大，请稍后重试") else encode(jpeg)
    }

    private fun encode(jpeg: ByteArray): ToolResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return ToolResult.success(Gson().toJson(mapOf(
            "jpeg" to Base64.encodeToString(jpeg, Base64.NO_WRAP),
            "width" to bounds.outWidth, "height" to bounds.outHeight,
            "capturedAt" to System.currentTimeMillis(),
        )))
    }
}
