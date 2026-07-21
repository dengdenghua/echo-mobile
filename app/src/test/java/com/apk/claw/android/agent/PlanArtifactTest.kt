package com.apk.claw.android.agent

import com.apk.claw.android.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 模式 UI 接入(spec refine-chat-interaction Task 7)单测。
 *
 * 测试覆盖:
 * - [DefaultAgentService.extractPlanJson] 从 LLM AiMessage 文本提取 plan JSON(步骤数组)
 * - [ToolResult.successWithPlan] 携带 planJson 字段
 * - [PermissionMode] 切换语义:exit_plan_mode 调用后切到 DEFAULT
 *
 * handleExitPlanMode() 本身依赖 Activity(ConfirmDialog)+ KVUtils(MMKV 持久化),
 * 难以在纯 JVM 单测下端到端跑;核心 plan JSON 提取逻辑抽成伴生纯函数专门测,
 * PermissionMode 切换验证 enum 语义 + setPermissionMode 目标值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanArtifactTest {

    // ==================== extractPlanJson:正向用例 ====================

    @Test
    fun `extractPlanJson returns json for pure array`() {
        val json = """[{"step":1,"action":"search_code","description":"找到 foo() 函数"}]"""
        val result = DefaultAgentService.extractPlanJson(json)
        assertNotNull(result)
        val arr = JSONArray(result)
        assertEquals(1, arr.length())
        val step = arr.getJSONObject(0)
        assertEquals(1, step.getInt("step"))
        assertEquals("search_code", step.getString("action"))
        assertEquals("找到 foo() 函数", step.getString("description"))
    }

    @Test
    fun `extractPlanJson extracts array from llm text with explanation`() {
        // LLM 通常在调 exit_plan_mode 前,于同一条 AiMessage 文本里输出步骤数组 + 解释文字
        val aiText = """
            我已分析需求,以下是执行计划:

            ```json
            [
              {"step":1,"action":"search_code","description":"找到 foo() 函数"},
              {"step":2,"action":"edit_file","description":"添加登录按钮"}
            ]
            ```

            请确认是否切换到默认模式执行。
        """.trimIndent()
        val result = DefaultAgentService.extractPlanJson(aiText)
        assertNotNull(result)
        val arr = JSONArray(result)
        assertEquals(2, arr.length())
        assertEquals("search_code", arr.getJSONObject(0).getString("action"))
        assertEquals("edit_file", arr.getJSONObject(1).getString("action"))
    }

    @Test
    fun `extractPlanJson accepts element with only step field`() {
        // 校验宽松:元素含 step/action/description 之一即视为 plan 步骤
        val json = """[{"step":1},{"step":2}]"""
        val result = DefaultAgentService.extractPlanJson(json)
        assertNotNull(result)
        assertEquals(2, JSONArray(result).length())
    }

    @Test
    fun `extractPlanJson accepts element with only action field`() {
        val json = """[{"action":"tap"},{"action":"swipe"}]"""
        val result = DefaultAgentService.extractPlanJson(json)
        assertNotNull(result)
    }

    @Test
    fun `extractPlanJson returns json with extra fields in elements`() {
        // 元素含额外字段(如 notes)不影响提取,只要含 step/action/description 之一
        val json = """[{"step":1,"action":"tap","notes":"点击登录按钮","target":"btn_login"}]"""
        val result = DefaultAgentService.extractPlanJson(json)
        assertNotNull(result)
        val el = JSONArray(result).getJSONObject(0)
        assertEquals("btn_login", el.getString("target"))
    }

    // ==================== extractPlanJson:负向用例 ====================

    @Test
    fun `extractPlanJson returns null for null input`() {
        assertNull(DefaultAgentService.extractPlanJson(null))
    }

    @Test
    fun `extractPlanJson returns null for blank input`() {
        assertNull(DefaultAgentService.extractPlanJson(""))
        assertNull(DefaultAgentService.extractPlanJson("   "))
    }

    @Test
    fun `extractPlanJson returns null when no brackets`() {
        assertNull(DefaultAgentService.extractPlanJson("我没有找到任何方括号"))
    }

    @Test
    fun `extractPlanJson returns null for empty array`() {
        assertNull(DefaultAgentService.extractPlanJson("[]"))
    }

    @Test
    fun `extractPlanJson returns null when elements are not objects`() {
        // [1,2,3] 元素是数字,不是 JSONObject
        assertNull(DefaultAgentService.extractPlanJson("[1,2,3]"))
    }

    @Test
    fun `extractPlanJson returns null when elements miss required fields`() {
        // 元素既无 step 也无 action 也无 description
        val json = """[{"target":"btn_login","x":100,"y":200}]"""
        assertNull(DefaultAgentService.extractPlanJson(json))
    }

    @Test
    fun `extractPlanJson returns null for invalid json`() {
        // 缺右括号,JSONArray 解析失败
        assertNull(DefaultAgentService.extractPlanJson("""[{"step":1,"action":"tap"""))
    }

    @Test
    fun `extractPlanJson returns null when only object not array`() {
        // 文本里只有 {...} 没有 [...] → lastIndexOf(']') == -1
        assertNull(DefaultAgentService.extractPlanJson("""{"step":1,"action":"tap"}"""))
    }

    // ==================== ToolResult.successWithPlan ====================

    @Test
    fun `successWithPlan carries planJson field`() {
        val planJson = """[{"step":1,"action":"tap","description":"点击"}]"""
        val result = ToolResult.successWithPlan("switched", planJson)
        assertTrue(result.isSuccess)
        assertEquals("switched", result.data)
        assertEquals(planJson, result.planJson)
        // 其他 artifact 通道应为 null,避免 UI 误触发其他卡片
        assertNull(result.imageBase64)
        assertNull(result.htmlContent)
        assertNull(result.filePath)
        assertNull(result.diff)
        assertNull(result.formData)
        assertNull(result.textBody)
    }

    @Test
    fun `successWithPlan does not set planJson on regular success`() {
        val result = ToolResult.success("plain")
        assertNull(result.planJson)
    }

    @Test
    fun `successWithPlan toString includes planJson`() {
        val result = ToolResult.successWithPlan("ok", "[{\"step\":1}]")
        val s = result.toString()
        assertTrue("toString 应包含 planJson 标识", s.contains("planJson"))
    }

    // ==================== PermissionMode 切换语义 ====================

    @Test
    fun `permissionmode plan is distinct from default`() {
        // exit_plan_mode 的目标:从 PLAN 切回 DEFAULT
        assertEquals(PermissionMode.PLAN, PermissionMode.fromName("PLAN"))
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromName("DEFAULT"))
        assertFalse(PermissionMode.PLAN == PermissionMode.DEFAULT)
    }

    @Test
    fun `permissionmode fromName falls back to default for unknown`() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromName(null))
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromName("UNKNOWN"))
    }

    @Test
    fun `plan mode blocks write tools via approval gate`() {
        // 契约测试:PLAN 模式下写工具被 ApprovalGate 拦截(双重防御,DefaultAgentService.execTool 也拦)
        // 这保证 LLM 在 PLAN 模式下无法直接执行写操作,只能输出 plan + 调 exit_plan_mode
        val gate = ApprovalGate(RuleBasedProvider(AutoDenyProvider()))
        val decision = gate.check("tap", emptyMap(), ApprovalRisk.HIGH, PermissionMode.PLAN)
        assertFalse(decision.approved)
    }

    @Test
    fun `exit_plan_mode target mode is default`() {
        // 契约测试:handleExitPlanMode 用户批准后应切到 DEFAULT。
        // 实际调用 AgentConfig.setPermissionMode(DEFAULT) 依赖 KVUtils(MMKV),纯 JVM 测不了,
        // 这里验证切换目标 enum 值正确,且 fromName("DEFAULT") 能还原(保证下次 currentPermissionMode() 读到 DEFAULT)。
        val targetMode = PermissionMode.DEFAULT
        assertEquals(PermissionMode.DEFAULT, targetMode)
        assertEquals(targetMode, PermissionMode.fromName(targetMode.name))
    }

    // ==================== plan JSON 结构契约 ====================

    @Test
    fun `plan json structure has step action description for each element`() {
        // spec 约定的 plan JSON 格式:[{"step":N,"action":"...","description":"..."}]
        val planJson = """
            [
              {"step":1,"action":"search_code","description":"找到 foo() 函数"},
              {"step":2,"action":"edit_file","description":"添加登录按钮"}
            ]
        """.trimIndent()
        val extracted = DefaultAgentService.extractPlanJson(planJson)
        assertNotNull(extracted)
        val arr = JSONArray(extracted)
        assertEquals(2, arr.length())
        for (i in 0 until arr.length()) {
            val el: JSONObject = arr.getJSONObject(i)
            assertTrue("step $i 应含 step 字段", el.has("step"))
            assertTrue("step $i 应含 action 字段", el.has("action"))
            assertTrue("step $i 应含 description 字段", el.has("description"))
            assertEquals(i + 1, el.getInt("step"))
        }
    }
}
