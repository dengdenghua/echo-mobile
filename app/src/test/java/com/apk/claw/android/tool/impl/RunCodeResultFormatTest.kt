package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 RunCodeTool 输出结构化为 TEXT Artifact 所需的 ToolResult.textBody 字段。
 *
 * 对应 spec refine-chat-interaction Task 8:run_code 输出走 Artifact。
 * 纯 JVM 测试 —— Rhino 沙箱不依赖 Android 框架(参考 [ScriptSandboxTest])。
 *
 * 覆盖:
 *  - 成功执行:textBody 非空,与 data(LLM-facing)一致,可被 UI 渲染为 TEXT 类 Artifact
 *  - 多行输出:textBody 完整保留 stdout 全文(供右侧栏 TextDetailPane 展示)
 *  - 失败执行:textBody 为 null(只包装成功输出,失败保留 errorCode/errorLine)
 *  - 空输出:textBody 为 null(无输出不生成 Artifact 卡片)
 */
class RunCodeResultFormatTest {

    private val tool = RunCodeTool()

    @Test
    fun `tool name and basic contract unchanged`() {
        // 约束:不修改 run_code 工具名/参数/风险等级
        assertEquals("run_code", tool.getName())
        val params = tool.getParameters()
        assertTrue("code param required", params.any { it.name == "code" && it.required })
        assertTrue("timeout_ms param optional", params.any { it.name == "timeout_ms" && !it.required })
    }

    @Test
    fun `successful execution wraps stdout into textBody`() {
        val result = tool.execute(mapOf("code" to "print('hello artifact');"))
        assertTrue("expected success, got: ${result.error}", result.isSuccess)
        // data 仍照常喂给 LLM
        assertTrue("data should contain stdout: ${result.data}", result.data?.contains("hello artifact") == true)
        // textBody 由 RunCodeTool 包装,供 UI 生成 TEXT Artifact
        assertNotNull("textBody must be non-null on success", result.textBody)
        assertEquals("textBody should mirror stdout", result.data, result.textBody)
    }

    @Test
    fun `multiline stdout is fully preserved in textBody`() {
        val code = """
            for (var i = 1; i <= 5; i++) {
                print("line " + i);
            }
        """.trimIndent()
        val result = tool.execute(mapOf("code" to code))
        assertTrue("expected success, got: ${result.error}", result.isSuccess)
        val body = result.textBody
        assertNotNull("textBody must be non-null", body)
        // 多行输出完整保留(右侧栏 TextDetailPane 显示全文)
        assertTrue("textBody should contain all 5 lines: $body",
            body!!.lines().count { it.startsWith("line ") } == 5)
        // data 与 textBody 一致(都来自同一份 stdout)
        assertEquals(result.data, body)
    }

    @Test
    fun `long stdout exceeding 500 chars is preserved in textBody for collapsed rendering`() {
        // 生成 600+ 字符的输出,验证 textBody 完整保留(UI 据此决定 collapsed=true)
        val code = """
            var s = "";
            for (var i = 0; i < 60; i++) {
                s += "0123456789";
            }
            print(s);
        """.trimIndent()
        val result = tool.execute(mapOf("code" to code))
        assertTrue("expected success, got: ${result.error}", result.isSuccess)
        val body = result.textBody
        assertNotNull("textBody must be non-null for long output", body)
        assertTrue("textBody should exceed 500 chars (collapsed=true case): ${body!!.length}",
            body.length > 500)
    }

    @Test
    fun `script error does not populate textBody`() {
        // 失败结果不包装 textBody —— 保留 errorCode/errorLine 给 LLM 自动修复循环
        val result = tool.execute(mapOf("code" to "undefinedVar.foo;"))
        assertFalse("expected script error, got success: ${result.data}", result.isSuccess)
        assertNull("textBody must be null on error", result.textBody)
        // 仍保留错误分类码(供 Agent 决策)
        assertNotNull("errorCode should be present on script error", result.errorCode)
    }

    @Test
    fun `code too long error does not populate textBody`() {
        val longCode = "x".repeat(100_001)
        val result = tool.execute(mapOf("code" to longCode))
        assertFalse("expected param error", result.isSuccess)
        assertNull("textBody must be null on param error", result.textBody)
    }

    @Test
    fun `ToolResult_successWithText_helper_populates_textBody`() {
        // 直接验证 ToolResult.successWithText 工厂方法(textBody 通道的契约)
        val r: ToolResult = ToolResult.successWithText("ok", "body content")
        assertTrue(r.isSuccess)
        assertEquals("ok", r.data)
        assertEquals("body content", r.textBody)
        // 其他产物字段保持 null
        assertNull(r.imageBase64)
        assertNull(r.htmlContent)
        assertNull(r.filePath)
        assertNull(r.diff)
        assertNull(r.formData)
    }

    @Test
    fun `ToolResult_success_does_not_populate_textBody`() {
        // 向后兼容:普通 success() 不应误填 textBody
        val r = ToolResult.success("plain")
        assertNull("textBody must default to null", r.textBody)
    }
}
