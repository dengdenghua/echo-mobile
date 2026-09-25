package com.apk.claw.android.tool.impl

/** Edits use UTF-16 selection offsets supplied by Android; deletion preserves surrogate pairs. */
internal object MirrorTextEdit {
    data class Result(val text: String, val start: Int, val end: Int)

    fun apply(text: String, start: Int, end: Int, command: String, inserted: String = ""): Result {
        require(start in 0..text.length && end in 0..text.length) { "应用未提供光标位置，请在手机上重新选中文本框" }
        val left = minOf(start, end)
        val right = maxOf(start, end)
        fun replace(from: Int, to: Int, value: String) =
            Result(text.replaceRange(from, to, value), from + value.length, from + value.length)
        return when (command) {
            "insert" -> replace(left, right, inserted)
            "delete_backward" -> replace(leftEdge(text, left, right), right, "")
            "delete_forward" -> replace(left, rightEdge(text, left, right), "")
            "move_left" -> leftEdge(text, left, right).let { Result(text, it, it) }
            "move_right" -> rightEdge(text, left, right).let { Result(text, it, it) }
            "select_all" -> Result(text, 0, text.length)
            else -> error("Unsupported text edit")
        }
    }
    private fun leftEdge(text: String, left: Int, right: Int) =
        if (left == right && left > 0) text.offsetByCodePoints(left, -1) else left
    private fun rightEdge(text: String, left: Int, right: Int) =
        if (left == right && right < text.length) text.offsetByCodePoints(right, 1) else right
}
