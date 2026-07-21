package com.apk.claw.android.ui.compose.screen.detail

import com.apk.claw.android.ui.compose.screen.ChatMessage
import com.apk.claw.android.ui.compose.screen.parseCodeSnippetTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * refine-chat-interaction Task 4:5 个 DetailPane 实现的纯 JUnit 测试。
 *
 * 不测 Compose 渲染(那需 Robolectric),只测:
 * - [parseCodeSnippetTitle] 解析 CODE_SNIPPET Artifact title 的格式
 * - 各 DetailPane 的 title 属性(可在非 Composable 上下文读取)
 *
 * Compose 渲染验证(扁平 UI、无渐变、OctopusShape 圆角等)由 INV-U5 静态检查覆盖,
 * 不在单测范围内。
 */
class DetailPanesTest {

    // ── parseCodeSnippetTitle ──

    @Test
    fun `parseCodeSnippetTitle parses simple file with line range`() {
        val (file, start, end) = parseCodeSnippetTitle("file.kt:42-58")
        assertEquals("file.kt", file)
        assertEquals(42, start)
        assertEquals(58, end)
    }

    @Test
    fun `parseCodeSnippetTitle parses path with directory separators`() {
        val (file, start, end) = parseCodeSnippetTitle("path/to/Foo.java:1-100")
        assertEquals("path/to/Foo.java", file)
        assertEquals(1, start)
        assertEquals(100, end)
    }

    @Test
    fun `parseCodeSnippetTitle parses single-character file extension`() {
        val (file, start, end) = parseCodeSnippetTitle("a.b:5-7")
        assertEquals("a.b", file)
        assertEquals(5, start)
        assertEquals(7, end)
    }

    @Test
    fun `parseCodeSnippetTitle parses single-line range start equals end`() {
        val (file, start, end) = parseCodeSnippetTitle("Foo.kt:10-10")
        assertEquals("Foo.kt", file)
        assertEquals(10, start)
        assertEquals(10, end)
    }

    @Test
    fun `parseCodeSnippetTitle returns empty triple for invalid input without colon`() {
        val (file, start, end) = parseCodeSnippetTitle("invalid")
        assertEquals("", file)
        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `parseCodeSnippetTitle returns empty triple for input without dash`() {
        val (file, start, end) = parseCodeSnippetTitle("Foo.kt:42")
        assertEquals("", file)
        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `parseCodeSnippetTitle returns empty triple for non-numeric line range`() {
        val (file, start, end) = parseCodeSnippetTitle("Foo.kt:abc-def")
        assertEquals("", file)
        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `parseCodeSnippetTitle returns empty triple for empty input`() {
        val (file, start, end) = parseCodeSnippetTitle("")
        assertEquals("", file)
        assertEquals(0, start)
        assertEquals(0, end)
    }

    // ── DetailPane.title 属性 ──

    @Test
    fun `PlanDetailPane title is 计划详情`() {
        val pane: DetailPane = PlanDetailPane(planJson = "[]") {}
        assertEquals("计划详情", pane.title)
    }

    @Test
    fun `PlanDetailPane can be constructed with empty json`() {
        val pane: DetailPane = PlanDetailPane(planJson = "") {}
        assertEquals("计划详情", pane.title)
    }

    @Test
    fun `CodeDetailPane title is file colon start-end`() {
        val pane: DetailPane = CodeDetailPane(
            file = "app/Foo.kt",
            startLine = 42,
            endLine = 58,
            snippet = "val x = 1",
        )
        assertEquals("app/Foo.kt:42-58", pane.title)
    }

    @Test
    fun `CodeDetailPane title with single-line range`() {
        val pane: DetailPane = CodeDetailPane("Bar.java", 10, 10, "// hi")
        assertEquals("Bar.java:10-10", pane.title)
    }

    @Test
    fun `DiffDetailPane title is 代码改动`() {
        val pane: DetailPane = DiffDetailPane(diff = "")
        assertEquals("代码改动", pane.title)
    }

    @Test
    fun `TextDetailPane title preserves caller-supplied title`() {
        val pane: DetailPane = TextDetailPane(title = "commit abc123", body = "")
        assertEquals("commit abc123", pane.title)
    }

    @Test
    fun `TextDetailPane title with arbitrary string`() {
        val cases = listOf("run_code stdout", "PR #42", "", "  带空白  ")
        cases.forEach { expected ->
            val pane: DetailPane = TextDetailPane(expected, "")
            assertEquals("TextDetailPane.title 应原样保留传入字符串", expected, pane.title)
        }
    }

    @Test
    fun `ToolsDetailPane title is 工具执行详情`() {
        val call = ChatMessage.ToolCall(
            icon = "🔧",
            toolName = "search_code",
            args = "{}",
            result = null,
        )
        val pane: DetailPane = ToolsDetailPane(calls = listOf(call))
        assertEquals("工具执行详情", pane.title)
    }

    @Test
    fun `ToolsDetailPane accepts empty call list`() {
        val pane: DetailPane = ToolsDetailPane(calls = emptyList())
        assertEquals("工具执行详情", pane.title)
    }

    @Test
    fun `all 5 DetailPane implementations share DetailPane interface`() {
        val planPane: DetailPane = PlanDetailPane("[]") {}
        val codePane: DetailPane = CodeDetailPane("f.kt", 1, 2, "")
        val diffPane: DetailPane = DiffDetailPane("")
        val textPane: DetailPane = TextDetailPane("t", "")
        val toolsPane: DetailPane = ToolsDetailPane(emptyList())

        listOf(planPane, codePane, diffPane, textPane, toolsPane).forEach {
            assertNotNull("DetailPane 实例应可被实例化", it)
        }
    }
}
