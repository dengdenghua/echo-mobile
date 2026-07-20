package com.apk.claw.android.octopus_mobile.uitree

import android.graphics.Rect
import com.apk.claw.android.utils.XLog

/**
 * stableId 解析工具：把 stableId 转成屏幕坐标。
 *
 * 供 TapTool / LongPressTool / SwipeTool 等坐标类工具复用：
 * 工具收到 stableId 参数后调本类的 [resolveCenter] / [resolveBounds]，
 * 不用各自实现一遍 UiTree 拉取 + 扁平化 + 查找逻辑。
 *
 * stableId 命中规则：精确匹配 [UiNode.stableId]。
 * 找不到时返回 null，调用方决定降级策略（报错 / 回退坐标 / 等）。
 */
object StableIdResolver {
    private const val TAG = "StableIdResolver"

    data class Result(val x: Int, val y: Int, val bounds: Rect, val node: UiNode)

    /**
     * 解析单个 stableId 到节点中心点。
     * @return 找不到返回 null；找到返回 [Result]（含 bounds 与中心点）
     */
    fun resolveCenter(stableId: String): Result? {
        val tree = runCatching { UiTreeCoordinator.getTree(false) }.getOrNull()
        if (tree?.root == null) {
            XLog.w(TAG, "UiTree unavailable for stableId=$stableId")
            return null
        }
        val node = tree.flatten().firstOrNull { it.stableId == stableId } ?: return null
        val b = node.bounds
        return Result(x = b.centerX(), y = b.centerY(), bounds = b, node = node)
    }

    /**
     * 解析两个 stableId 到起止点（用于 swipe：起点节点 center → 终点节点 center）。
     * @return 任一找不到返回 null；都找到返回 (startX, startY, endX, endY)
     */
    fun resolveTwoPoints(
        startStableId: String?,
        endStableId: String?,
    ): Pair<Result, Result>? {
        if (startStableId.isNullOrBlank() || endStableId.isNullOrBlank()) return null
        val s = resolveCenter(startStableId) ?: return null
        val e = resolveCenter(endStableId) ?: return null
        return s to e
    }
}
