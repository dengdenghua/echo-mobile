package com.apk.claw.android.octopus_mobile.uitree

import android.content.Context
import android.graphics.Rect
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.RemoteActions
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuShellService
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * UI 树提供者接口：统一三通道的差异。
 *
 * 实现方负责把底层 API（AccessibilityNodeInfo / uiautomator XML / 远程 HTTP）
 * 转换成结构化 [UiTree]。
 */
interface UiTreeProvider {
    /** 通道名："a11y" | "shizuku" | "remote" */
    val source: String

    /** 是否可用（如 Shizuku 未绑定则 false，A11y 服务未开启则 false） */
    fun isAvailable(): Boolean

    /** 获取 UI 树。[full]=true 包含所有节点，false 仅包含有意义节点 */
    fun getTree(full: Boolean): UiTree?
}

/** 无障碍服务通道：基于 AccessibilityNodeInfo，最详细，但系统对话框/自绘 UI 可能拿不到 */
class A11yTreeProvider : UiTreeProvider {
    override val source: String = "a11y"

    override fun isAvailable(): Boolean = ClawAccessibilityService.getInstance() != null

    override fun getTree(full: Boolean): UiTree? {
        val svc = ClawAccessibilityService.getInstance() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        val dm = ClawApplication.instance.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val tree = UiTree(
            root = UiNode.fromA11y(root, w, h),
            screenWidth = w,
            screenHeight = h,
            packageName = root.packageName?.toString() ?: "",
            capturedAt = System.currentTimeMillis(),
            source = source,
        )
        // 项目硬约束：AccessibilityNodeInfo 必须 recycle 防泄漏
        try { root.recycle() } catch (_: Throwable) {}
        return if (full) tree else tree.prune()
    }
}

/** Shizuku 通道：基于 uiautomator dump，可补足 A11y 拿不到的系统对话框/自绘 UI */
class ShizukuTreeProvider : UiTreeProvider {
    override val source: String = "shizuku"

    override fun isAvailable(): Boolean = ShizukuShellService.getScreenSize() != null

    override fun getTree(full: Boolean): UiTree? {
        val xml = ShizukuShellService.uiAutomatorDump() ?: return null
        val (w, h) = ShizukuShellService.getScreenSize() ?: return null
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val root = parseXml(parser, w, h) ?: return null
        val tree = UiTree(
            root = root,
            screenWidth = w,
            screenHeight = h,
            packageName = root.packageName,
            capturedAt = System.currentTimeMillis(),
            source = source,
        )
        return if (full) tree else tree.prune()
    }

    /** 解析 uiautomator XML 为 UiNode 树 */
    private fun parseXml(parser: XmlPullParser, screenW: Int, screenH: Int): UiNode? {
        var event = parser.eventType
        var root: UiNode? = null
        // stack 同时持有节点和它的可变子节点列表（builder 模式）
        val stack = ArrayDeque<Pair<UiNode, MutableList<UiNode>>>()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "node") {
                        val node = parseNode(parser, screenW, screenH)
                        if (stack.isEmpty()) {
                            root = node
                        } else {
                            stack.last().second.add(node)
                        }
                        stack.addLast(node to mutableListOf())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "node" && stack.isNotEmpty()) {
                        val (node, children) = stack.removeLast()
                        // children 非空时用 copy 填入
                        if (children.isNotEmpty() && stack.isNotEmpty()) {
                            val newChild = node.copy(children = children)
                            // 用新节点替换父节点 children 列表中的旧引用
                            val parentChildren = stack.last().second
                            val idx = parentChildren.indexOf(node)
                            if (idx >= 0) parentChildren[idx] = newChild
                        } else if (children.isNotEmpty()) {
                            root = node.copy(children = children)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return root
    }

    private fun parseNode(parser: XmlPullParser, screenW: Int, screenH: Int): UiNode {
        fun attr(name: String): String = parser.getAttributeValue(null, name) ?: ""
        val boundsStr = attr("bounds").removePrefix("[").removeSuffix("]")
        val parts = boundsStr.split("][")
        val bounds = if (parts.size == 2) {
            val (l, t) = parts[0].split(",").map { it.trim().toIntOrNull() ?: 0 }
            val (r, b) = parts[1].split(",").map { it.trim().toIntOrNull() ?: 0 }
            Rect(l, t, r, b)
        } else Rect()
        val cls = attr("class").ifEmpty { "View" }
        val txt = attr("text")
        val dsc = attr("content-desc")
        val vid = attr("resource-id")
        val pkg = attr("package")
        val clickable = attr("clickable") == "true"
        val longClickable = attr("long-clickable") == "true"
        val scrollable = attr("scrollable") == "true"
        val editable = attr("editable") == "true"
        val checked = attr("checked") == "true"
        val enabled = attr("enabled") != "false"
        val focused = attr("focused") == "true"
        val selected = attr("selected") == "true"
        val acts = mutableListOf<String>().apply {
            if (clickable) add("CLICK")
            if (longClickable) add("LONG_CLICK")
            if (scrollable) add("SCROLL")
            if (editable) add("SET_TEXT")
        }
        return UiNode(
            stableId = "${vid.ifEmpty { cls.substringAfterLast('.') }}:${Integer.toHexString(txt.hashCode())}:${bounds.toShortString()}",
            className = cls,
            text = txt,
            desc = dsc,
            viewId = vid,
            packageName = pkg,
            bounds = bounds,
            normBounds = RectF(
                if (screenW > 0) bounds.left.toFloat() / screenW else 0f,
                if (screenH > 0) bounds.top.toFloat() / screenH else 0f,
                if (screenW > 0) bounds.right.toFloat() / screenW else 0f,
                if (screenH > 0) bounds.bottom.toFloat() / screenH else 0f,
            ),
            isClickable = clickable,
            isLongClickable = longClickable,
            isScrollable = scrollable,
            isEditable = editable,
            isChecked = checked,
            isEnabled = enabled,
            isFocused = focused,
            isSelected = selected,
            actions = acts,
            children = emptyList(),
        )
    }
}

/** 远程通道：通过 HTTP 拉取对端设备的 UI 树（已是字符串格式，这里重新解析为 UiTree） */
class RemoteTreeProvider(private val device: DeviceInfo) : UiTreeProvider {
    override val source: String = "remote"

    override fun isAvailable(): Boolean = device != null

    override fun getTree(full: Boolean): UiTree? {
        // 远端返回的是字符串格式树，我们包装为最小 UiTree（无结构化节点）
        // 真正的结构化对端需升级 ConfigServer 返回 JSON
        val text = RemoteActions.screenTree(device, full) ?: return null
        return UiTree(
            root = null, // 远端文本格式暂不解析为结构化节点
            screenWidth = 0,
            screenHeight = 0,
            packageName = "",
            capturedAt = System.currentTimeMillis(),
            source = source,
        )
    }
}

/**
 * UI 树协调器：按优先级 fallback。
 *
 * 优先级：A11y（最详细）→ Shizuku uiautomator（补足系统对话框）→ Remote（远程设备）
 *
 * 替代之前散落在 ClawAccessibilityService.performTap 等方法里的硬编码 fallback。
 */
object UiTreeCoordinator {
    private val providers = listOf(A11yTreeProvider(), ShizukuTreeProvider())

    /** 按 fallback 顺序获取 UI 树 */
    fun getTree(full: Boolean = false): UiTree? {
        for (p in providers) {
            if (!p.isAvailable()) continue
            val tree = runCatching { p.getTree(full) }.getOrNull()
            if (tree != null && tree.root != null) return tree
        }
        return null
    }

    /** 获取远程设备 UI 树 */
    fun getRemoteTree(device: DeviceInfo, full: Boolean = false): UiTree? {
        if (device == null) return null
        return runCatching { RemoteTreeProvider(device).getTree(full) }.getOrNull()
    }

    /** 按格式输出：text（默认，向后兼容）/ json（结构化，给 LLM 更稳） */
    fun format(tree: UiTree?, format: String = "text"): String {
        if (tree == null) return "(no ui tree available)"
        return when (format) {
            "json" -> tree.toJson().toString()
            else -> tree.toText()
        }
    }
}

/** UiTree 扩展：精简模式（跳过不可见、无意义节点） */
private fun UiTree.prune(): UiTree {
    fun pruneNode(n: UiNode): UiNode? {
        // 保留：有 text/desc、可交互、可滑、进度条、或子树有保留值
        val meaningful = n.text.isNotEmpty() || n.desc.isNotEmpty() ||
            n.isClickable || n.isLongClickable || n.isScrollable || n.isEditable ||
            n.className.contains("ProgressBar") || n.className.contains("SeekBar")
        val prunedChildren = n.children.mapNotNull(::pruneNode)
        if (!meaningful && prunedChildren.isEmpty()) return null
        return n.copy(children = prunedChildren)
    }
    return copy(root = root?.let(::pruneNode))
}
