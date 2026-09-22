package com.apk.claw.android.tool.impl.mobile

import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.tool.ToolParameter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MobileTools 单元测试 —— 参数验证层.
 *
 * 覆盖：
 *  - 工具名 / 显示名 / 参数定义正确
 *  - 坐标 / stableId 二选一的参数契约
 *  - 缺参时返回可读错误
 *  - 四条执行通道(远程/Shizuku/A11y/Root)全不可用时返回 error
 *
 * 注意：这些工具统一走 UiActionRouter，最终依赖 ClawAccessibilityService / Shizuku / Root
 * 等系统级能力，在 Robolectric 单元测试环境中无法真正执行点击/滑动/长按。
 * 此处只验证参数契约和基础行为。
 * getDisplayName() 读取字符串资源，因此需要 Robolectric 提供的真实 Resources。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class)
class MobileToolsTest {

    // ── TapTool ───────────────────────────────────────────

    @Test
    fun `TapTool name is tap`() {
        val tool = TapTool()
        assertEquals("tap", tool.getName())
    }

    @Test
    fun `TapTool exposes coordinates and stableId params`() {
        val tool = TapTool()
        val params = tool.getParameters()
        // 坐标(x,y) 与 stableId 二选一,所以都不是 required —— 缺参由 execute() 给出可读错误。
        assertEquals(listOf("x", "y", "stableId"), params.map { it.name })
        assertTrue("x/y/stableId 都应可省略(二选一)", params.none { it.isRequired })
    }

    @Test
    fun `TapTool reports missing coordinates`() {
        val tool = TapTool()
        val result = tool.execute(emptyMap())
        assertFalse(result.isSuccess)
        assertTrue("应指出缺 x: ${result.error}", result.error!!.contains("Missing required parameter: x"))
    }

    @Test
    fun `TapTool reports failure when no action channel is available`() {
        val tool = TapTool()
        // 参数齐全时会走 UiActionRouter(远程→Shizuku→A11y→Root),单测环境四条通道都不存在。
        val result = tool.execute(mapOf("x" to 100, "y" to 200))
        assertFalse(result.isSuccess)
        assertTrue("应说明所有通道都失败: ${result.error}", result.error!!.contains("所有通道都失败"))
    }

    @Test
    fun `TapTool returns error when x missing`() {
        val tool = TapTool()
        val result = tool.execute(mapOf("y" to 200))
        // 在无障碍服务未运行时，参数验证之前的可访问性检查会先返回 error
        assertFalse(result.isSuccess)
    }

    // ── SwipeTool ─────────────────────────────────────────

    @Test
    fun `SwipeTool name is swipe`() {
        val tool = SwipeTool()
        assertEquals("swipe", tool.getName())
    }

    @Test
    fun `SwipeTool exposes coordinates and stableId params`() {
        val tool = SwipeTool()
        val params = tool.getParameters()
        // 坐标 4 个 + stableId 2 个 + duration_ms。坐标与 stableId 二选一(可混用),
        // 因此没有任何参数被标 required —— 缺参由 execute() 给出可读错误(见下一个用例)。
        assertEquals(7, params.size)
        assertEquals(
            listOf("start_x", "start_y", "end_x", "end_y", "start_stableId", "end_stableId", "duration_ms"),
            params.map { it.name },
        )
        assertTrue("坐标与 stableId 都应可省略(二选一)", params.none { it.isRequired })
    }

    @Test
    fun `SwipeTool reports missing coordinates`() {
        val tool = SwipeTool()
        val result = tool.execute(emptyMap())
        assertFalse(result.isSuccess)
        assertTrue(
            "错误信息应指引 start_x / start_stableId: ${result.error}",
            result.error!!.contains("start_x") || result.error!!.contains("start_stableId"),
        )
    }

    @Test
    fun `SwipeTool reports failure when no action channel is available`() {
        val tool = SwipeTool()
        val result = tool.execute(mapOf(
            "start_x" to 100, "start_y" to 200,
            "end_x" to 300, "end_y" to 400
        ))
        assertFalse(result.isSuccess)
        assertTrue("应说明所有通道都失败: ${result.error}", result.error!!.contains("所有通道都失败"))
    }

    // ── LongPressTool ─────────────────────────────────────

    @Test
    fun `LongPressTool name is long_press`() {
        val tool = LongPressTool()
        assertEquals("long_press", tool.getName())
    }

    @Test
    fun `LongPressTool exposes coordinates stableId and duration`() {
        val tool = LongPressTool()
        val params = tool.getParameters()
        assertEquals(listOf("x", "y", "stableId", "duration_ms"), params.map { it.name })
        assertTrue("x/y/stableId 都应可省略(二选一)", params.none { it.isRequired })
    }

    @Test
    fun `LongPressTool reports failure when no action channel is available`() {
        val tool = LongPressTool()
        val result = tool.execute(mapOf("x" to 100, "y" to 200))
        assertFalse(result.isSuccess)
        assertTrue("应说明所有通道都失败: ${result.error}", result.error!!.contains("所有通道都失败"))
    }

    // ── ScrollToFindTool ──────────────────────────────────

    @Test
    fun `ScrollToFindTool name is scroll_to_find`() {
        val tool = ScrollToFindTool()
        assertEquals("scroll_to_find", tool.getName())
    }

    @Test
    fun `ScrollToFindTool has text parameter`() {
        val tool = ScrollToFindTool()
        val params = tool.getParameters()
        assertTrue(params.any { it.name == "text" })
    }

    // ── 通用参数验证 ──────────────────────────────────────

    @Test
    fun `all mobile tools have non-empty descriptions`() {
        val tools = listOf(TapTool(), SwipeTool(), LongPressTool(), ScrollToFindTool())
        for (tool in tools) {
            assertTrue("${tool.getName()} EN desc empty", tool.getDescriptionEN().isNotBlank())
            assertTrue("${tool.getName()} CN desc empty", tool.getDescriptionCN().isNotBlank())
        }
    }

    @Test
    fun `all mobile tools have display names`() {
        val tools = listOf(TapTool(), SwipeTool(), LongPressTool(), ScrollToFindTool())
        for (tool in tools) {
            assertTrue("${tool.getName()} display name empty", tool.getDisplayName().isNotBlank())
        }
    }
}
