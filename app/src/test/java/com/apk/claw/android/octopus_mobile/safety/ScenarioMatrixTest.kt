package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-14 场景矩阵 —— 把本地来源闸门的**全部输入组合**逐格钉进断言。
 *
 * 为什么需要矩阵：[SourceGatePolicy.actionFor] 是「不可信来源 × 工具风险」的唯一裁决点，
 * 同时被四个维度驱动 —— 权限模式（APPROVAL/FULL_POWER）、来源可信度、工具风险等级、最小硬闸门开关。
 * 单点不变量测试（见 `SourceGatePolicyTest`）只覆盖了关键格子；一旦有人调整分支顺序或短路条件，
 * **边界格**（如「满血 + 不可信 + 高危 + 硬闸门关闭」）会被静默改变而无人发现。
 * 本测试对整个笛卡尔积逐格断言期望值。
 *
 * 维护契约 —— 新增场景必须同步扩列，否则测试立刻失败：
 * - 新增 [PermissionMode] 枚举值 → `matrix covers every permission mode` 失败；
 * - 新增 [ToolRiskPolicy] 的 RISK_* 常量 → `matrix covers every declared risk level` 失败；
 * - 新增/删除输入维度 → `matrix cell count is the full cartesian product` 失败。
 *
 * 决策语义以 [SourceGatePolicy] 的 KDoc 决策表为准；本文件任一期望值变化都必须同步改那边。
 */
class ScenarioMatrixTest {

    /** 矩阵一格：四个输入维度。data class 让失败信息可直接读出组合（含 `hardGate`）。 */
    private data class Cell(
        val mode: PermissionMode,
        val untrusted: Boolean,
        val risk: String,
        val hardGate: Boolean,
    )

    private companion object {
        /** 兜底分支探针：既不是 high 也不是 medium 的风险值。 */
        const val RISK_UNKNOWN = "unknown-xyz"

        /** [ToolRiskPolicy] 已声明的三个风险等级，用作「枚举是否扩列」的探针。 */
        val DECLARED_RISKS: Set<String> = setOf(
            ToolRiskPolicy.RISK_LOW,
            ToolRiskPolicy.RISK_MEDIUM,
            ToolRiskPolicy.RISK_HIGH,
        )

        /** 风险维度：三个已声明等级 + 一个未声明值。 */
        val RISKS: List<String> = DECLARED_RISKS.toList() + RISK_UNKNOWN

        /** 来源可信度维度。 */
        val TRUST: List<Boolean> = listOf(false, true)

        /** 最小硬闸门开关维度。 */
        val HARD_GATE: List<Boolean> = listOf(true, false)

        /** 全枚举格子数 = 模式 × 来源 × 风险 × 硬闸门。 */
        val EXPECTED_CELL_COUNT: Int =
            PermissionMode.entries.size * TRUST.size * RISKS.size * HARD_GATE.size
    }

    /**
     * 逐格期望值。每格必须有且仅有一条。
     *
     * 记忆法：
     * - 可信来源永远不触发来源闸门 → null；
     * - 闸门只管辖 high/medium，low 与未声明风险一律 null；
     * - APPROVAL：high → highRiskAction（CONFIRM），medium → mediumRiskAction（ALLOW）；
     * - FULL_POWER：trustAllSources=true，medium 在到达 mediumRiskAction 前就被短路为 null；
     *   high 仅在硬闸门开启时被强制 CONFIRM，关闭时回落 highRiskAction（ALLOW）。
     */
    private val expectations: Map<Cell, PermissionPolicy.RiskAction?> = mapOf(
        // ── APPROVAL（主力机，trustAllSources=false）────────────────────────────
        // 可信来源：闸门完全不介入
        Cell(PermissionMode.APPROVAL, false, ToolRiskPolicy.RISK_LOW, true) to null,
        Cell(PermissionMode.APPROVAL, false, ToolRiskPolicy.RISK_LOW, false) to null,
        Cell(PermissionMode.APPROVAL, false, ToolRiskPolicy.RISK_MEDIUM, true) to null,
        Cell(PermissionMode.APPROVAL, false, ToolRiskPolicy.RISK_MEDIUM, false) to null,
        Cell(PermissionMode.APPROVAL, false, ToolRiskPolicy.RISK_HIGH, true) to null,
        Cell(PermissionMode.APPROVAL, false, ToolRiskPolicy.RISK_HIGH, false) to null,
        Cell(PermissionMode.APPROVAL, false, RISK_UNKNOWN, true) to null,
        Cell(PermissionMode.APPROVAL, false, RISK_UNKNOWN, false) to null,
        // 不可信 + 低危/未声明：不是闸门管辖范围
        Cell(PermissionMode.APPROVAL, true, ToolRiskPolicy.RISK_LOW, true) to null,
        Cell(PermissionMode.APPROVAL, true, ToolRiskPolicy.RISK_LOW, false) to null,
        Cell(PermissionMode.APPROVAL, true, RISK_UNKNOWN, true) to null,
        Cell(PermissionMode.APPROVAL, true, RISK_UNKNOWN, false) to null,
        // 不可信 + 中危：mediumRiskAction（APPROVAL 默认 ALLOW，可被用户收紧）
        Cell(PermissionMode.APPROVAL, true, ToolRiskPolicy.RISK_MEDIUM, true)
            to PermissionPolicy.RiskAction.ALLOW,
        Cell(PermissionMode.APPROVAL, true, ToolRiskPolicy.RISK_MEDIUM, false)
            to PermissionPolicy.RiskAction.ALLOW,
        // 不可信 + 高危：highRiskAction（APPROVAL 默认 CONFIRM）；硬闸门不改变此格结果
        Cell(PermissionMode.APPROVAL, true, ToolRiskPolicy.RISK_HIGH, true)
            to PermissionPolicy.RiskAction.CONFIRM,
        Cell(PermissionMode.APPROVAL, true, ToolRiskPolicy.RISK_HIGH, false)
            to PermissionPolicy.RiskAction.CONFIRM,

        // ── FULL_POWER（群控机，trustAllSources=true）──────────────────────────
        // 可信来源：闸门完全不介入
        Cell(PermissionMode.FULL_POWER, false, ToolRiskPolicy.RISK_LOW, true) to null,
        Cell(PermissionMode.FULL_POWER, false, ToolRiskPolicy.RISK_LOW, false) to null,
        Cell(PermissionMode.FULL_POWER, false, ToolRiskPolicy.RISK_MEDIUM, true) to null,
        Cell(PermissionMode.FULL_POWER, false, ToolRiskPolicy.RISK_MEDIUM, false) to null,
        Cell(PermissionMode.FULL_POWER, false, ToolRiskPolicy.RISK_HIGH, true) to null,
        Cell(PermissionMode.FULL_POWER, false, ToolRiskPolicy.RISK_HIGH, false) to null,
        Cell(PermissionMode.FULL_POWER, false, RISK_UNKNOWN, true) to null,
        Cell(PermissionMode.FULL_POWER, false, RISK_UNKNOWN, false) to null,
        // 不可信 + 低危/未声明：不是闸门管辖范围
        Cell(PermissionMode.FULL_POWER, true, ToolRiskPolicy.RISK_LOW, true) to null,
        Cell(PermissionMode.FULL_POWER, true, ToolRiskPolicy.RISK_LOW, false) to null,
        Cell(PermissionMode.FULL_POWER, true, RISK_UNKNOWN, true) to null,
        Cell(PermissionMode.FULL_POWER, true, RISK_UNKNOWN, false) to null,
        // 不可信 + 中危：被 trustAllSources 短路（mediumRiskAction 对满血模式无效）
        Cell(PermissionMode.FULL_POWER, true, ToolRiskPolicy.RISK_MEDIUM, true) to null,
        Cell(PermissionMode.FULL_POWER, true, ToolRiskPolicy.RISK_MEDIUM, false) to null,
        // 不可信 + 高危：硬闸门开 → 强制 CONFIRM；关 → 回落 highRiskAction（ALLOW）★唯一受硬闸门影响的格子
        Cell(PermissionMode.FULL_POWER, true, ToolRiskPolicy.RISK_HIGH, true)
            to PermissionPolicy.RiskAction.CONFIRM,
        Cell(PermissionMode.FULL_POWER, true, ToolRiskPolicy.RISK_HIGH, false)
            to PermissionPolicy.RiskAction.ALLOW,
    )

    @Test
    fun `matrix cell count is the full cartesian product`() {
        assertEquals(EXPECTED_CELL_COUNT, allCells().size)
        assertEquals(
            "期望表必须与全枚举一一对应（多一条/少一条都说明矩阵没跟上代码）",
            EXPECTED_CELL_COUNT,
            expectations.size,
        )
    }

    @Test
    fun `every cell matches its declared expectation`() {
        for (cell in allCells()) {
            assertTrue("矩阵缺少格子（新增维度后忘了扩列？）：$cell", expectations.containsKey(cell))
            assertEquals("格子期望不符：$cell", expectations[cell], actionFor(cell))
        }
    }

    @Test
    fun `matrix covers every permission mode`() {
        assertEquals(
            "新增权限模式后必须同步扩列本矩阵",
            PermissionMode.entries.toSet(),
            expectations.keys.map { it.mode }.toSet(),
        )
    }

    @Test
    fun `matrix covers every declared risk level plus an unknown one`() {
        val covered = expectations.keys.map { it.risk }.toSet()
        assertTrue(
            "缺少已声明的风险等级：" +
                "${DECLARED_RISKS - covered}",
            covered.containsAll(
                listOf(ToolRiskPolicy.RISK_LOW, ToolRiskPolicy.RISK_MEDIUM, ToolRiskPolicy.RISK_HIGH),
            ),
        )
        assertTrue("必须保留一个未声明风险值以锁定兜底分支", RISK_UNKNOWN in covered)
    }

    @Test
    fun `matrix covers both source trust values and both hard gate values`() {
        assertEquals(setOf(false, true), expectations.keys.map { it.untrusted }.toSet())
        assertEquals(setOf(false, true), expectations.keys.map { it.hardGate }.toSet())
    }

    @Test
    fun `hard gate changes exactly one cell`() {
        // 硬闸门的作用域必须精确：只有「FULL_POWER + 不可信 + 高危」会因它而变。
        // 这条断言防止未来把硬闸门误扩到 APPROVAL 或中危格（那会改变既定语义）。
        val differing = allCells()
            .filter { it.hardGate }
            .filter { on -> actionFor(on) != actionFor(on.copy(hardGate = false)) }
        assertEquals(
            "硬闸门的作用域必须恰好是「满血 + 不可信 + 高危」一格",
            listOf(
                Cell(
                    PermissionMode.FULL_POWER,
                    untrusted = true,
                    risk = ToolRiskPolicy.RISK_HIGH,
                    hardGate = true,
                ),
            ),
            differing,
        )
    }

    @Test
    fun `full power short circuits medium risk by design`() {
        // trustAllSources=true 时中危在抵达 mediumRiskAction 之前就被短路为放行，
        // 因此 FULL_POWER 下把 mediumRiskAction 收紧为 BLOCK 不会生效。这是设计语义而非缺陷；
        // 本断言把它显式化，避免后来者误以为 mediumRiskAction 对满血模式有效。
        val tightened = PermissionPolicy.FULL_POWER
            .copy(mediumRiskAction = PermissionPolicy.RiskAction.BLOCK)
        assertNull(
            SourceGatePolicy.actionFor(
                tightened,
                untrustedSource = true,
                riskLevel = ToolRiskPolicy.RISK_MEDIUM,
            ),
        )
    }

    /** 该格对应的策略：预设 + 硬闸门开关。 */
    private fun policyOf(cell: Cell): PermissionPolicy =
        PermissionPolicy.forMode(cell.mode).let {
            if (cell.hardGate) it else it.copy(untrustedHighRiskHardGate = false)
        }

    /** 该格的实际裁决：走被测纯函数。 */
    private fun actionFor(cell: Cell): PermissionPolicy.RiskAction? =
        SourceGatePolicy.actionFor(policyOf(cell), cell.untrusted, cell.risk)

    /** 四维笛卡尔积全枚举。 */
    private fun allCells(): List<Cell> = buildList {
        for (mode in PermissionMode.entries) {
            for (untrusted in TRUST) {
                for (risk in RISKS) {
                    for (hardGate in HARD_GATE) {
                        add(Cell(mode, untrusted, risk, hardGate))
                    }
                }
            }
        }
    }
}
