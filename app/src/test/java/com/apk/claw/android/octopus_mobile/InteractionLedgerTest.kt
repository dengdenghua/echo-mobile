@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * InteractionLedger(GUI 经验账本)测试 —— 纯 JVM,不依赖 Robolectric。
 *
 * 覆盖:GUI 失败记录→注入含对应规避;非 GUI 工具不记录(域隔离);同模式归并计数;持久化 round-trip。
 * 存储走 init(filesDir) 留存的目录(非 ClawApplication.instance),故可纯 JVM 跑。
 */
class InteractionLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        InteractionLedger.reset()
        EvolutionMetrics.reset()
        InteractionLedger.init(tmp.root)
    }

    @After
    fun tearDown() {
        InteractionLedger.reset()
    }

    @Test
    fun `gui failure recorded and mitigation injected`() {
        InteractionLedger.recordFailure("tap", "{\"text\":\"结算\"}", "找不到节点: 结算")
        assertEquals(1, InteractionLedger.size())

        val section = InteractionLedger.getMitigationsSection()
        assertTrue("应含 GUI 经验标题", section.contains("操作经验"))
        assertTrue("应含 node_not_found 的标题", section.contains("目标控件当前不在屏上"))
        assertTrue("应给出 scroll_to_find 规避", section.contains("scroll_to_find"))
    }

    @Test
    fun `non-gui tool failure is ignored`() {
        // generate_app / run_code 属代码域,不该进 GUI 账本
        InteractionLedger.recordFailure("generate_app", "", "TypeError: x is not a function")
        InteractionLedger.recordFailure("run_code", "", "SyntaxError: Unexpected token")
        assertEquals("非 GUI 工具失败不入账", 0, InteractionLedger.size())
        assertTrue("无教训时注入段为空", InteractionLedger.getMitigationsSection().isEmpty())
    }

    @Test
    fun `same pattern merges into one lesson`() {
        InteractionLedger.recordFailure("tap", "{\"text\":\"A\"}", "element not found")
        InteractionLedger.recordFailure("tap", "{\"text\":\"B\"}", "no such element")
        InteractionLedger.recordFailure("swipe", "{}", "cannot find target")
        // 三次都归到 node_not_found,应合并为 1 条
        assertEquals("同模式归并", 1, InteractionLedger.size())
    }

    @Test
    fun `distinct patterns kept separate`() {
        InteractionLedger.recordFailure("tap", "", "找不到节点")           // node_not_found
        InteractionLedger.recordFailure("open_app", "", "系统弹窗遮挡")     // dialog_blocking
        InteractionLedger.recordFailure("input_text", "", "输入框没有焦点") // input_focus
        assertEquals(3, InteractionLedger.size())
    }

    @Test
    fun `lessons survive reload from disk`() {
        InteractionLedger.recordFailure("tap", "{\"text\":\"提交\"}", "load timeout, still loading")
        assertEquals(1, InteractionLedger.size())

        // 模拟进程重启:清空内存 + 从同一目录重新 init(读回持久化文件)
        InteractionLedger.reset()
        InteractionLedger.init(tmp.root)

        assertEquals("持久化后应读回", 1, InteractionLedger.size())
        assertTrue(InteractionLedger.getMitigationsSection().contains("页面尚未加载完成"))
    }

    @Test
    fun `blank error is not recorded`() {
        InteractionLedger.recordFailure("tap", "{}", "")
        assertEquals(0, InteractionLedger.size())
    }

    @Test
    fun `isGuiTool classifies correctly`() {
        assertTrue(InteractionLedger.isGuiTool("tap"))
        assertTrue(InteractionLedger.isGuiTool("browser_click"))
        assertFalse(InteractionLedger.isGuiTool("generate_app"))
        assertFalse(InteractionLedger.isGuiTool("run_code"))
    }

    @Test
    fun `snapshot returns recorded lessons for display`() {
        InteractionLedger.recordFailure("tap", "", "找不到节点")
        InteractionLedger.recordFailure("open_app", "", "系统弹窗遮挡")
        val snap = InteractionLedger.snapshot()
        assertEquals(2, snap.size)
        assertTrue("展示项含标题", snap.any { it.title.contains("目标控件") })
        assertTrue("展示项含规避策略", snap.all { it.mitigation.isNotBlank() })
    }

    @Test
    fun `clearLessons empties but keeps ledger usable`() {
        InteractionLedger.recordFailure("tap", "", "找不到节点")
        assertEquals(1, InteractionLedger.size())
        InteractionLedger.clearLessons()
        assertEquals(0, InteractionLedger.size())
        // 清空后仍可继续记录(区别于 reset 会废掉账本)
        InteractionLedger.recordFailure("swipe", "", "加载超时")
        assertEquals(1, InteractionLedger.size())
    }

    @Test
    fun `manual rule is stored injected and marked`() {
        InteractionLedger.addManualRule("打开淘宝先关弹窗再操作")
        assertEquals(1, InteractionLedger.size())
        val snap = InteractionLedger.snapshot()
        assertTrue("规矩应标记为 manual", snap.any { it.manual && it.title.contains("淘宝") })
        // 应注入到 prompt,带【用户规矩】前缀
        val section = InteractionLedger.getMitigationsSection()
        assertTrue(section.contains("【用户规矩】"))
        assertTrue(section.contains("打开淘宝先关弹窗再操作"))
    }

    @Test
    fun `manual rule dedups and can be removed`() {
        InteractionLedger.addManualRule("别点广告")
        InteractionLedger.addManualRule("别点广告")   // 幂等
        assertEquals(1, InteractionLedger.size())
        InteractionLedger.removeManualRule("别点广告")
        assertEquals(0, InteractionLedger.size())
    }

    @Test
    fun `manual rule skips near-duplicates`() {
        InteractionLedger.addManualRule("订机票")
        InteractionLedger.addManualRule("帮我订机票")   // 近似(含填充词)→ 跳过
        InteractionLedger.addManualRule("  订机票  ")   // 空白变体 → 跳过
        assertEquals("近似规矩去重,只留 1 条", 1, InteractionLedger.size())
        // 真不同的规矩仍能加
        InteractionLedger.addManualRule("查快递物流")
        assertEquals(2, InteractionLedger.size())
    }

    @Test
    fun `disabled manual rule is kept but not injected`() {
        InteractionLedger.addManualRule("打开淘宝先关弹窗")
        assertTrue(InteractionLedger.getMitigationsSection().contains("打开淘宝先关弹窗"))

        InteractionLedger.setManualRuleEnabled("打开淘宝先关弹窗", false)
        // 停用后:保留在账本(snapshot 可见)但不再注入 prompt
        assertEquals(1, InteractionLedger.size())
        assertTrue(InteractionLedger.snapshot().any { it.title.contains("淘宝") && !it.enabled })
        assertFalse("停用的规矩不注入", InteractionLedger.getMitigationsSection().contains("打开淘宝先关弹窗"))

        InteractionLedger.setManualRuleEnabled("打开淘宝先关弹窗", true)
        assertTrue("重新启用后又注入", InteractionLedger.getMitigationsSection().contains("打开淘宝先关弹窗"))
    }

    @Test
    fun `clearLessons keeps manual rules but drops learned lessons`() {
        InteractionLedger.addManualRule("先登录再下单")
        InteractionLedger.recordFailure("tap", "", "找不到节点")   // 自动学到的
        assertEquals(2, InteractionLedger.size())
        InteractionLedger.clearLessons()
        val snap = InteractionLedger.snapshot()
        assertEquals("只剩用户规矩", 1, snap.size)
        assertTrue(snap[0].manual)
        assertTrue(snap[0].title.contains("先登录"))
    }
}
