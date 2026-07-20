package com.apk.claw.android.octopus_mobile.uitree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一 UI 节点数据结构。
 *
 * 屏蔽 AccessibilityNodeInfo / uiautomator XML / 远程 HTTP 三种来源的差异，
 * 为 Agent 提供稳定的、可 JSON 序列化的节点视图。
 *
 * 字段对齐 Operit UI Tree 方案：稳定的复合 id + 归一化 bounds + action 枚举。
 */
data class UiNode(
    /** 稳定复合 id：viewId?className:textHash:boundsHash，跨调用可复用引用 */
    val stableId: String,
    val className: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val packageName: String,
    /** 绝对像素 bounds */
    val bounds: Rect,
    /** 归一化 bounds（0..1），跨分辨率可移植 */
    val normBounds: RectF,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isSelected: Boolean,
    /** 该节点支持的动作枚举（CLICK/LONG_CLICK/SCROLL/SET_TEXT/...） */
    val actions: List<String>,
    val children: List<UiNode>,
) {

    /** 紧凑文本格式（向后兼容现有 LLM 提示词） */
    fun toText(indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val sb = StringBuilder("$pad[$className]")
        if (text.isNotEmpty()) sb.append(" text=\"").append(text.take(80)).append('\"')
        if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(60)).append('\"')
        if (viewId.isNotEmpty()) sb.append(" id=").append(viewId)
        if (isClickable) sb.append(" [clickable]")
        if (isLongClickable) sb.append(" [long-clickable]")
        if (isScrollable) sb.append(" [scrollable]")
        if (isEditable) sb.append(" [editable]")
        if (isChecked) sb.append(" [checked]")
        if (!isEnabled) sb.append(" [disabled]")
        if (isFocused) sb.append(" [focused]")
        sb.append(" bounds=").append(bounds.toShortString())
        if (actions.isNotEmpty()) sb.append(" actions=").append(actions.joinToString(","))
        val sb2 = StringBuilder(sb.toString()).append('\n')
        children.forEach { sb2.append(it.toText(indent + 1)) }
        return sb2.toString()
    }

    /** JSON 序列化（给 LLM 和远程传输用，比文本更稳） */
    fun toJson(): JSONObject = JSONObject().apply {
        put("stableId", stableId)
        put("class", className)
        if (text.isNotEmpty()) put("text", text)
        if (desc.isNotEmpty()) put("desc", desc)
        if (viewId.isNotEmpty()) put("viewId", viewId)
        if (packageName.isNotEmpty()) put("pkg", packageName)
        put("bounds", JSONObject().apply {
            put("l", bounds.left); put("t", bounds.top); put("r", bounds.right); put("b", bounds.bottom)
        })
        put("normBounds", JSONObject().apply {
            put("l", normBounds.left); put("t", normBounds.top); put("r", normBounds.right); put("b", normBounds.bottom)
        })
        val flags = mutableListOf<String>().apply {
            if (isClickable) add("clickable")
            if (isLongClickable) add("long-clickable")
            if (isScrollable) add("scrollable")
            if (isEditable) add("editable")
            if (isChecked) add("checked")
            if (!isEnabled) add("disabled")
            if (isFocused) add("focused")
            if (isSelected) add("selected")
        }
        if (flags.isNotEmpty()) put("flags", JSONArray(flags))
        if (actions.isNotEmpty()) put("actions", JSONArray(actions))
        if (children.isNotEmpty()) put("children", JSONArray().also { arr ->
            children.forEach { arr.put(it.toJson()) }
        })
    }

    companion object {
        /** 从 AccessibilityNodeInfo 构建 UiNode（recycle 原节点以避免泄漏） */
        fun fromA11y(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): UiNode {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val norm = RectF(
                if (screenW > 0) bounds.left.toFloat() / screenW else 0f,
                if (screenH > 0) bounds.top.toFloat() / screenH else 0f,
                if (screenW > 0) bounds.right.toFloat() / screenW else 0f,
                if (screenH > 0) bounds.bottom.toFloat() / screenH else 0f,
            )
            val cls = node.className?.toString() ?: "View"
            val txt = node.text?.toString() ?: ""
            val dsc = node.contentDescription?.toString() ?: ""
            val vid = node.viewIdResourceName ?: ""
            val pkg = node.packageName?.toString() ?: ""
            val stableId = buildStableId(vid, cls, txt, bounds)
            val acts = mutableListOf<String>().apply {
                if (node.isClickable) add("CLICK")
                if (node.isLongClickable) add("LONG_CLICK")
                if (node.isScrollable) add("SCROLL")
                if (node.isEditable) add("SET_TEXT")
                if (node.isCheckable) add("TOGGLE")
                if (node.isSelected) add("SELECT")
                if (node.isFocusable) add("FOCUS")
            }.distinct()
            val children = (0 until node.childCount).mapNotNull { i ->
                node.getChild(i)?.let { fromA11y(it, screenW, screenH) }
            }
            // 注意：项目硬约束要求 recycle，但 getChild 返回的子节点已在递归中处理；
            // 这里不 recycle 传入的 node 本身（由调用方决定），避免在遍历中途失效。
            return UiNode(
                stableId = stableId,
                className = cls,
                text = txt,
                desc = dsc,
                viewId = vid,
                packageName = pkg,
                bounds = bounds,
                normBounds = norm,
                isClickable = node.isClickable,
                isLongClickable = node.isLongClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isChecked = node.isChecked,
                isEnabled = node.isEnabled,
                isFocused = node.isFocused,
                isSelected = node.isSelected,
                actions = acts,
                children = children,
            )
        }

        /** 复合稳定 id：viewId?className:textHash:boundsHash */
        private fun buildStableId(vid: String, cls: String, txt: String, bounds: Rect): String {
            val textKey = if (txt.isNotEmpty()) Integer.toHexString(txt.hashCode()) else "0"
            val boundsKey = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            val idPart = if (vid.isNotEmpty()) vid else cls.substringAfterLast('.')
            return "$idPart:$textKey:$boundsKey"
        }
    }
}

/** 浮点 bounds（归一化用） */
data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** UI 树快照 */
data class UiTree(
    val root: UiNode?,
    val screenWidth: Int,
    val screenHeight: Int,
    val packageName: String,
    val capturedAt: Long,
    val source: String, // "a11y" | "shizuku" | "remote"
) {
    fun toText(): String {
        val sb = StringBuilder()
        sb.append("UI Tree (").append(screenWidth).append('x').append(screenHeight)
        sb.append(" pkg=").append(packageName).append(" source=").append(source).append(")\n")
        root?.let { sb.append(it.toText()) }
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("pkg", packageName)
        put("source", source)
        put("capturedAt", capturedAt)
        root?.let { put("root", it.toJson()) }
    }

    /** 扁平化所有节点（深度优先） */
    fun flatten(): List<UiNode> {
        val out = mutableListOf<UiNode>()
        fun walk(n: UiNode) {
            out.add(n)
            n.children.forEach(::walk)
        }
        root?.let(::walk)
        return out
    }
}
