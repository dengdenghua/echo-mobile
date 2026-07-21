package com.apk.claw.android.tool.impl

import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.code.CodeIndex
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.ui.compose.screen.ChatMessage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [SearchCodeTool] 结果格式 + [handleSearchCodeResult] helper 单元测试。
 *
 * spec refine-chat-interaction Task 5:search_code 工具结果须为结构化 JSON,
 * 顶层含 file/startLine/endLine/snippet 4 字段(top-1 命中),供 UI 生成 CODE_SNIPPET Artifact。
 *
 * 覆盖:
 *  1. 有命中时,result.data 是 JSON,包含 file/startLine/endLine/snippet 4 字段
 *  2. 无命中时,result.data 是 JSON,totalMatches=0
 *  3. handleSearchCodeResult 从 ToolResult 解析出 CODE_SNIPPET Artifact(kind/title/payloadRef)
 *  4. handleSearchCodeResult 对失败/空结果返回 null
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestClawApplication::class)
class SearchCodeResultFormatTest {

    private lateinit var rootDir: File

    @Before
    fun setUp() {
        CodeIndex.resetForTest()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("code_index.db")
        rootDir = createTempDir(prefix = "search_code_test")
    }

    @After
    fun tearDown() {
        CodeIndex.resetForTest()
        rootDir.deleteRecursively()
    }

    @Test
    fun `result data is json with file startLine endLine snippet when matches found`() {
        // 准备:写一个含 "login" 符号的 .kt 文件
        File(rootDir, "LoginViewModel.kt").writeText(
            """
            package com.example.app

            class LoginViewModel {
                fun login(user: String, password: String): Boolean {
                    return user.isNotEmpty() && password.isNotEmpty()
                }
            }
            """.trimIndent(),
        )

        val tool = SearchCodeTool()
        val result = tool.execute(
            mapOf(
                "query" to "login",
                "path" to rootDir.absolutePath,
            ),
        )

        assertTrue("tool should succeed: ${result.error}", result.isSuccess)
        val data = result.data
        assertNotNull("data must not be null", data)

        // 核心断言:data 是 JSON,含 4 字段
        val json = JSONObject(data!!)
        assertTrue("has file field", json.has("file"))
        assertTrue("has startLine field", json.has("startLine"))
        assertTrue("has endLine field", json.has("endLine"))
        assertTrue("has snippet field", json.has("snippet"))

        val file = json.getString("file")
        val startLine = json.getInt("startLine")
        val endLine = json.getInt("endLine")
        val snippet = json.getString("snippet")

        assertEquals("LoginViewModel.kt", file)
        assertTrue("startLine > 0: $startLine", startLine > 0)
        assertTrue("endLine >= startLine: $startLine-$endLine", endLine >= startLine)
        assertTrue("snippet contains login: $snippet", snippet.contains("login"))
        assertFalse("snippet not empty", snippet.isEmpty())

        // 额外字段:totalMatches >= 1
        val totalMatches = json.optInt("totalMatches", 0)
        assertTrue("totalMatches >= 1: $totalMatches", totalMatches >= 1)
    }

    @Test
    fun `result data is json with totalMatches zero when no match`() {
        // 准备:写一个不含 "payment" 的文件
        File(rootDir, "LoginViewModel.kt").writeText(
            """
            class LoginViewModel {
                fun login(user: String): Boolean = true
            }
            """.trimIndent(),
        )

        val tool = SearchCodeTool()
        val result = tool.execute(
            mapOf(
                "query" to "payment_processor_refund",
                "path" to rootDir.absolutePath,
            ),
        )

        // 无命中时工具仍返回 success(只是 totalMatches=0)
        assertTrue("tool should still succeed on no-match: ${result.error}", result.isSuccess)
        val json = JSONObject(result.data!!)
        assertEquals("totalMatches should be 0", 0, json.optInt("totalMatches", -1))
    }

    @Test
    fun `handleSearchCodeResult returns CODE_SNIPPET artifact from successful result`() {
        // 构造一个模拟的 search_code 成功结果(JSON 含 4 字段)
        val json = JSONObject()
        json.put("file", "app/src/main/java/Foo.kt")
        json.put("startLine", 42)
        json.put("endLine", 58)
        json.put("snippet", "fun foo() {\n    println(\"hello\")\n}")
        json.put("totalMatches", 3)
        val toolResult = ToolResult.success(json.toString())

        val artifact = handleSearchCodeResult(toolResult, "session_123")
        assertNotNull("artifact should not be null", artifact)
        assertEquals(
            "kind should be CODE_SNIPPET",
            ChatMessage.ArtifactKind.CODE_SNIPPET,
            artifact!!.kind,
        )
        assertEquals("title should be file:startLine-endLine", "app/src/main/java/Foo.kt:42-58", artifact.title)
        // payloadRef 含 sessionId 保证跨会话唯一
        assertTrue("payloadRef contains sessionId: ${artifact.payloadRef}",
            artifact.payloadRef.contains("session_123"))
        assertTrue("payloadRef ends with _code: ${artifact.payloadRef}",
            artifact.payloadRef.endsWith("_code"))
    }

    @Test
    fun `handleSearchCodeResult returns null for failed result`() {
        val toolResult = ToolResult.error("search failed")
        val artifact = handleSearchCodeResult(toolResult, "session_123")
        assertNull("failed result should yield null artifact", artifact)
    }

    @Test
    fun `handleSearchCodeResult returns null for no-match result`() {
        val json = JSONObject()
        json.put("totalMatches", 0)
        json.put("message", "No code chunks matched the query.")
        val toolResult = ToolResult.success(json.toString())

        val artifact = handleSearchCodeResult(toolResult, "session_123")
        assertNull("no-match result should yield null artifact", artifact)
    }

    @Test
    fun `handleSearchCodeResult returns null for non-json data`() {
        val toolResult = ToolResult.success("this is plain text, not json")
        val artifact = handleSearchCodeResult(toolResult, "session_123")
        assertNull("non-json data should yield null artifact", artifact)
    }
}
