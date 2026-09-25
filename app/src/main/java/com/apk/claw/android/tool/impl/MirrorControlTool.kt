package com.apk.claw.android.tool.impl

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.blankj.utilcode.util.ScreenUtils

/** Always controls this phone, regardless of the agent's selected remote target. */
class MirrorControlTool : BaseTool() {
    companion object {
        private const val ASPECT_TOLERANCE = 0.03
        private const val MAX_TEXT_CHARS = 8000
        private const val TAP_MS = 100L
        private const val MIN_TAP_MS = 50L
        private const val SWIPE_MS = 300L
        private const val MAX_GESTURE_MS = 1500L
    }
    override fun getName() = "mirror_control"
    override fun getDisplayName() = "手机镜像操作"
    override fun getDescriptionEN() =
        "Control this phone: tap/swipe with normalized coordinates, text in focused field, back/home/recents."
    override fun getDescriptionCN() = "操作本机镜像：点击、滑动、向焦点文本框输入、返回、主页和最近任务。"
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "tap/swipe/text/edit/back/home/recents", true),
        ToolParameter("command", "string",
            "insert/delete_backward/delete_forward/move_left/move_right/select_all/enter", false),
        ToolParameter("x", "number", "Normalized x, 0..1", false),
        ToolParameter("y", "number", "Normalized y, 0..1", false),
        ToolParameter("end_x", "number", "Normalized end x, 0..1", false),
        ToolParameter("end_y", "number", "Normalized end y, 0..1", false),
        ToolParameter("duration", "integer", "Gesture duration in ms", false),
        ToolParameter("text", "string", "Text to replace focused field", false),
        ToolParameter("aspect", "number", "Aspect ratio of displayed frame", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult = try {
        val service = ClawAccessibilityService.getInstance() ?: error("请在手机开启无障碍权限")
        val width = ScreenUtils.getScreenWidth()
        val height = ScreenUtils.getScreenHeight()
        val aspect = (params["aspect"] as? Number)?.toDouble() ?: 0.0
        require(kotlin.math.abs(aspect - width.toDouble() / height) < ASPECT_TOLERANCE) { "屏幕方向已改变，请等待新画面" }
        val success = act(service, params, width, height)
        if (success) ToolResult.success("{\"applied\":true}") else ToolResult.error("手机未完成操作，请检查焦点与权限")
    } catch (error: IllegalArgumentException) {
        ToolResult.error(error.message ?: "操作参数无效")
    } catch (error: IllegalStateException) {
        ToolResult.error(error.message ?: "操作不可用")
    }

    private fun coordinate(params: Map<String, Any>, key: String, size: Int): Int {
        val value = (params[key] as? Number)?.toDouble() ?: error("Missing coordinate")
        require(value.isFinite() && value in 0.0..1.0) { "Coordinate outside screen" }
        return (value * (size - 1)).toInt()
    }

    private fun act(service: ClawAccessibilityService, params: Map<String, Any>, width: Int, height: Int): Boolean =
        when (requireString(params, "action")) {
            "tap" -> service.performTap(
                coordinate(params, "x", width), coordinate(params, "y", height),
                optionalLong(params, "duration", TAP_MS).coerceIn(MIN_TAP_MS, MAX_GESTURE_MS),
            )
            "swipe" -> service.performSwipe(
                coordinate(params, "x", width), coordinate(params, "y", height),
                coordinate(params, "end_x", width), coordinate(params, "end_y", height),
                optionalLong(params, "duration", SWIPE_MS).coerceIn(TAP_MS, MAX_GESTURE_MS),
            )
            "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "text" -> input(service, requireString(params, "text"))
            "edit" -> edit(service, params)
            else -> error("Unsupported mirror action")
        }

    private fun input(service: ClawAccessibilityService, text: String): Boolean {
        require(text.length <= MAX_TEXT_CHARS) { "Text too long" }
        val root = service.rootInActiveWindow ?: error("No active window")
        val node = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
            ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
        } finally { node.recycle() }
    }

    private fun edit(service: ClawAccessibilityService, params: Map<String, Any>): Boolean {
        val root = service.rootInActiveWindow ?: error("No active window")
        val node = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
            ?: error("请先点击手机上的文本框")
        return try {
            check(node.refresh()) { "输入焦点已改变，请重新点击手机文本框" }
            require(node.isEditable && !node.isPassword) { "此输入框不支持直接键盘编辑，请在手机上输入" }
            val command = requireString(params, "command")
            if (command == "enter") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    error("此系统不支持远程确认，请点击手机按钮")
                }
            } else {
                val before = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                val result = MirrorTextEdit.apply(
                    before, node.textSelectionStart, node.textSelectionEnd, command,
                    if (command == "insert") requireString(params, "text") else "",
                )
                require(result.text.length <= MAX_TEXT_CHARS) { "Text too long" }
                val changed = before == result.text || node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text)
                    },
                )
                changed && restoreSelection(node, result)
            }
        } finally { node.recycle() }
    }

}

private fun restoreSelection(node: AccessibilityNodeInfo, result: MirrorTextEdit.Result): Boolean {
    if (!node.refresh()) return false
    // TextView may return false for ACTION_SET_SELECTION when SET_TEXT already put the cursor here.
    return (node.textSelectionStart == result.start && node.textSelectionEnd == result.end) ||
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.end)
        })
}
