package com.apk.claw.android.octopus_mobile.uitree

/**
 * UI 节点选择器 DSL。
 *
 * 替代之前只有 findNodesByText / findNodesById 的查询方式，
 * 支持复合条件、层级关系、坐标命中。
 *
 * 用法：
 * ```
 * tree.find(byText("登录") and byClickable())
 * tree.find(byId("com.x:id/btn") or byDesc("确认"))
 * tree.findAt(x, y)  // 按坐标命中
 * tree.first(byTextContains("跳过"))?.center()
 * ```
 */
data class UiSelector(
    val textEquals: String? = null,
    val textContains: String? = null,
    val descEquals: String? = null,
    val descContains: String? = null,
    val idEquals: String? = null,
    val idContains: String? = null,
    val classNameEquals: String? = null,
    val classNameContains: String? = null,
    val clickable: Boolean? = null,
    val longClickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val editable: Boolean? = null,
    val checked: Boolean? = null,
    val enabled: Boolean? = null,
    val packageNameEquals: String? = null,
) {
    fun matches(node: UiNode): Boolean {
        textEquals?.let { if (node.text != it) return false }
        textContains?.let { if (!node.text.contains(it)) return false }
        descEquals?.let { if (node.desc != it) return false }
        descContains?.let { if (!node.desc.contains(it)) return false }
        idEquals?.let { if (node.viewId != it) return false }
        idContains?.let { if (!node.viewId.contains(it)) return false }
        classNameEquals?.let { if (node.className != it) return false }
        classNameContains?.let { if (!node.className.contains(it)) return false }
        clickable?.let { if (node.isClickable != it) return false }
        longClickable?.let { if (node.isLongClickable != it) return false }
        scrollable?.let { if (node.isScrollable != it) return false }
        editable?.let { if (node.isEditable != it) return false }
        checked?.let { if (node.isChecked != it) return false }
        enabled?.let { if (node.isEnabled != it) return false }
        packageNameEquals?.let { if (node.packageName != it) return false }
        return true
    }

    /** AND 组合（默认所有字段叠加即 AND） */
    fun and(other: UiSelector): UiSelector = copy(
        textEquals = other.textEquals ?: textEquals,
        textContains = other.textContains ?: textContains,
        descEquals = other.descEquals ?: descEquals,
        descContains = other.descContains ?: descContains,
        idEquals = other.idEquals ?: idEquals,
        idContains = other.idContains ?: idContains,
        classNameEquals = other.classNameEquals ?: classNameEquals,
        classNameContains = other.classNameContains ?: classNameContains,
        clickable = other.clickable ?: clickable,
        longClickable = other.longClickable ?: longClickable,
        scrollable = other.scrollable ?: scrollable,
        editable = other.editable ?: editable,
        checked = other.checked ?: checked,
        enabled = other.enabled ?: enabled,
        packageNameEquals = other.packageNameEquals ?: packageNameEquals,
    )
}

/** DSL 构造器 */
fun byText(text: String) = UiSelector(textEquals = text)
fun byTextContains(text: String) = UiSelector(textContains = text)
fun byDesc(desc: String) = UiSelector(descEquals = desc)
fun byDescContains(desc: String) = UiSelector(descContains = desc)
fun byId(id: String) = UiSelector(idEquals = id)
fun byIdContains(id: String) = UiSelector(idContains = id)
fun byClassName(cls: String) = UiSelector(classNameEquals = cls)
fun byClickable(v: Boolean = true) = UiSelector(clickable = v)
fun byScrollable(v: Boolean = true) = UiSelector(scrollable = v)
fun byEditable(v: Boolean = true) = UiSelector(editable = v)
fun byChecked(v: Boolean = true) = UiSelector(checked = v)
fun byEnabled(v: Boolean = true) = UiSelector(enabled = v)
fun byPackage(pkg: String) = UiSelector(packageNameEquals = pkg)

/** OR 组合：匹配任一 selector */
fun anyOf(vararg selectors: UiSelector): (UiNode) -> Boolean = { node ->
    selectors.any { it.matches(node) }
}

/** UiTree / UiNode 查询扩展 */
fun UiTree.find(selector: UiSelector): List<UiNode> = flatten().filter { selector.matches(it) }

fun UiTree.find(predicate: (UiNode) -> Boolean): List<UiNode> = flatten().filter(predicate)

fun UiTree.first(selector: UiSelector): UiNode? = flatten().firstOrNull { selector.matches(it) }

fun UiTree.first(predicate: (UiNode) -> Boolean): UiNode? = flatten().firstOrNull(predicate)

/** 按坐标命中（屏幕绝对像素） */
fun UiTree.findAt(x: Int, y: Int): UiNode? {
    // 返回包含该坐标的最深节点
    var best: UiNode? = null
    var bestArea = Long.MAX_VALUE
    fun walk(n: UiNode) {
        val b = n.bounds
        if (x in b.left..b.right && y in b.top..b.bottom) {
            val area = (b.right - b.left).toLong() * (b.bottom - b.top)
            if (area < bestArea) {
                bestArea = area
                best = n
            }
        }
        n.children.forEach(::walk)
    }
    root?.let(::walk)
    return best
}

/** 节点中心点（绝对像素） */
fun UiNode.center(): Pair<Int, Int> = ((bounds.left + bounds.right) / 2) to ((bounds.top + bounds.bottom) / 2)

/** 节点归一化中心点（0..1） */
fun UiNode.normCenter(): Pair<Float, Float> = normBounds.centerX to normBounds.centerY
