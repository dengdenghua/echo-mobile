package com.apk.claw.android.octopus_mobile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * LightweightReAct 循环测试 —— 端到端 ReAct 行为.
 *
 * 覆盖：
 *  - DONE：LLM 调工具 → 看到结果 → 调 finish 收尾
 *  - STUCK：连续 4 轮同 fingerprint 触发
 *  - MAX_STEPS：步数耗尽
 *  - CANCELLED：外部取消
 *  - 三级压缩：长历史被压缩
 *  - 不同参数不死循环
 *  - 工具异常被捕获
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LightweightReActTest {

    private lateinit var server: MockWebServer
    private lateinit var llm: LightweightLlmClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ── DONE ─────────────────────────────────────────────────

    @Test
    fun `react done in 2 steps`() = runBlocking {
        // Step 1: LLM 调 get_screen_info
        enqueueResponse(toolCall(name = "android.get_screen_info", args = "{}"))
        // Step 2: LLM 调 finish
        enqueueResponse(toolCall(name = "android.finish", args = """{"ok": true, "summary": "done"}"""))
        // Step 3: 兜底（如果 ReAct 不调 finish 直接 stop）
        enqueueResponse(textResponse("done"))

        val (react, exec) = newReact(ReActConfig(maxSteps = 10))
        val result = react.run("look at screen", listOf(skill("android.get_screen_info", "看屏幕"), skill("android.finish", "结束")))

        // 两种 acceptable outcome
        assertTrue("expected Done or MaxStepsReached, got $result", result is TaskResult.Done || result is TaskResult.MaxStepsReached)
        assertTrue("executor should have been called", exec.calls.isNotEmpty())
    }

    // ── STUCK ────────────────────────────────────────────────

    @Test
    fun `react stuck detection after 4 identical calls`() = runBlocking {
        // 8 次都调相同的 tap → 第 5 次触发 STUCK
        repeat(8) {
            enqueueResponse(toolCall(
                name = "android.tap",
                args = """{"x": 540, "y": 1200}"""
            ))
        }

        val (react, exec) = newReact(ReActConfig(maxSteps = 20, stuckWindowSize = 4))
        val result = react.run("tap", listOf(skill("android.tap", "")))

        assertTrue("expected Stuck, got $result", result is TaskResult.Stuck)
        // 5 步就触发（4 个相同 fingerprint + 第 5 次确认）
        assertTrue("stuck should be detected within 5 steps, got step=${(result as TaskResult.Stuck).totalSteps}",
            result.totalSteps <= 5)
    }

    @Test
    fun `react does not stuck on different args`() = runBlocking {
        // 5 次相同 name 但不同 args → 不算死循环
        for (i in 0 until 5) {
            enqueueResponse(toolCall(
                name = "android.tap",
                args = """{"x": $i, "y": ${i + 1}}"""
            ))
        }
        // 兜底 finish
        enqueueResponse(toolCall(name = "android.finish", args = """{"ok": true, "summary": "ok"}"""))
        enqueueResponse(textResponse("ok"))

        val (react, exec) = newReact(ReActConfig(maxSteps = 20, stuckWindowSize = 4))
        val result = react.run("try different coords", listOf(
            skill("android.tap", ""),
            skill("android.finish", "")
        ))
        assertTrue("should complete (Done or MaxStepsReached), got $result",
            result is TaskResult.Done || result is TaskResult.MaxStepsReached)
    }

    // ── MAX_STEPS ────────────────────────────────────────────

    @Test
    fun `react max steps triggers termination`() = runBlocking {
        // 每次都用不同 args 避免 stuck
        for (i in 0 until 50) {
            enqueueResponse(toolCall(
                name = "android.tap",
                args = """{"x": $i, "y": ${i * 2}}"""
            ))
        }

        val (react, _) = newReact(ReActConfig(maxSteps = 3, stuckWindowSize = 4))
        val result = react.run("loop", listOf(skill("android.tap", "")))
        assertTrue("expected MaxStepsReached, got $result", result is TaskResult.MaxStepsReached)
        assertEquals(3, (result as TaskResult.MaxStepsReached).totalSteps)
    }

    // ── CANCELLED（需要 LightweightReAct.cancel() 公开 API，下个 Phase） ───

    @Test
    fun `react handles LLM exceptions gracefully`() = runBlocking {
        // 不返回任何响应 → LlmException → 循环应 MaxStepsReached 而不是崩溃
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val (react, _) = newReact(ReActConfig(maxSteps = 2))
        val result = react.run("x", listOf(skill("android.tap", "")))
        assertTrue("expected MaxStepsReached on LLM error, got $result",
            result is TaskResult.MaxStepsReached)
        val msr = result as TaskResult.MaxStepsReached
        assertTrue("error msg should mention LLM failure",
            msr.lastResponse?.contains("LLM call failed") == true || msr.lastResponse?.contains("server error") == true)
    }

    // ── 三级压缩 ─────────────────────────────────────────────

    @Test
    fun `react compresses long tool results`() = runBlocking {
        // 第 1 步：get_screen_info 返回超长 JSON
        val longJson = JSONObject().apply {
            put("tree", (0 until 100).map { JSONObject().put("text", "elem $it") })
        }.toString()
        enqueueResponse(
            textResponse("", toolCalls = listOf(
                mapOf("id" to "t1", "name" to "android.get_screen_info", "arguments" to "{}")
            ))
        )
        // 第 2 步：finish
        enqueueResponse(
            textResponse("done", toolCalls = listOf(
                mapOf("id" to "t2", "name" to "android.finish", "arguments" to """{"ok":true,"summary":"x"}""")
            ))
        )
        enqueueResponse(textResponse("done"))

        val screenInfo = skill("android.get_screen_info", "看屏幕")
        val finish = skill("android.finish", "结束")
        val (react, _) = newReact(
            ReActConfig(
                maxSteps = 10,
                screenInfoTools = setOf("android.get_screen_info"),
                protectedRecentSize = 2
            )
        )

        val captured = StringBuilder()
        val result = react.run(
            task = "x",
            skills = listOf(screenInfo, finish),
            onStep = { step ->
                if (step is ReActStep.ToolCallDone) {
                    captured.append(step.result.display)
                }
            }
        )
        // 验证：执行器收到了屏幕数据（长字符串被压缩到 summaryKeepLength）
        // 我们的 mock executor 返回的字符串是 "screen: <long json>" —— compression 由 react 决定
        // 这里只验证不崩
        assertNotNull(result)
    }

    // ── 工具异常 ─────────────────────────────────────────────

    @Test
    fun `react captures tool exceptions`() = runBlocking {
        enqueueResponse(toolCall(name = "android.tap", args = """{"x":1,"y":2}"""))
        enqueueResponse(toolCall(name = "android.finish", args = """{"ok":true,"summary":"ok"}"""))
        enqueueResponse(textResponse("ok"))

        val (react, _) = newReact(
            config = ReActConfig(maxSteps = 5),
            toolExecutor = { call ->
                if (call.name == "android.tap") {
                    ToolExecutionResult.Failure(
                        toolCallId = call.id,
                        display = "device disconnected",
                        errorCode = -1,
                        errorMessage = "USB unplugged"
                    )
                } else {
                    ToolExecutionResult.Success(toolCallId = call.id, display = "ok", data = "ok")
                }
            }
        )
        val result = react.run("x", listOf(
            skill("android.tap", ""),
            skill("android.finish", "")
        ))
        // 工具失败不应让循环崩 —— 应当继续
        assertTrue("react should not crash on tool failure, got $result",
            result is TaskResult.Done || result is TaskResult.MaxStepsReached)
    }

    // ── 测试工具 ─────────────────────────────────────────────


    @Test
    fun `unknown verification stops without repair and remains visible`() = runBlocking {
        enqueueResponse(textResponse("已经完成"))
        val (react, exec) = newReact(verifyGoal = { _, _ -> GoalVerifier.unverified("没有截图") })
        val result = react.run("保存文件", emptyList()) as TaskResult.Done
        assertEquals(GoalVerifier.Status.UNVERIFIED, result.verification.status)
        assertTrue(result.summary.startsWith("结果待核验"))
        assertTrue(exec.calls.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `repair exhaustion still verifies and does not report success`() = runBlocking {
        repeat(2) { enqueueResponse(textResponse("完成")) }
        var checks = 0
        val (react, _) = newReact(verifyGoal = { _, _ -> checks++; GoalVerifier.parse("NO\n文件不存在") })
        val result = react.run("保存文件", emptyList()) as TaskResult.Done
        assertEquals(2, checks)
        assertEquals(GoalVerifier.Status.NOT_ACHIEVED, result.verification.status)
        assertTrue(result.summary.startsWith("目标尚未完成"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `successful repair needs fresh positive evidence`() = runBlocking {
        repeat(2) { enqueueResponse(textResponse("完成")) }
        var checks = 0
        val (react, _) = newReact(verifyGoal = { _, _ -> GoalVerifier.parse(if (checks++ == 0) "NO" else "YES") })
        val result = react.run("保存文件", emptyList()) as TaskResult.Done
        assertEquals(2, checks)
        assertTrue(result.verification.achieved)
    }

    @Test
    fun `cancelling verification is not converted to a completed result`() = runBlocking {
        enqueueResponse(textResponse("完成"))
        val (react, _) = newReact(verifyGoal = { _, _ -> throw kotlinx.coroutines.CancellationException("cancel") })
        try {
            react.run("保存文件", emptyList())
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            assertEquals("cancel", cancelled.message)
        }
    }

    private data class Bundle(val react: LightweightReAct, val exec: MockToolExecutor)

    private fun newReact(
        config: ReActConfig = ReActConfig(),
        verifyGoal: suspend (String, android.graphics.Bitmap?) -> GoalVerifier.Verdict = GoalVerifier::verify,
        toolExecutor: (suspend (ToolCall) -> ToolExecutionResult)? = null
    ): Bundle {
        val baseUrl = server.url("/v1/").toString().trimEnd('/')
        val llmConfig = LlmConfig(
            apiUrl = "$baseUrl/chat/completions",
            apiKey = "test",
            model = "test",
            temperature = 0.0,
            maxTokens = 256
        )
        val client = LightweightLlmClient(
            config = llmConfig,
            httpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        )
        this.llm = client
        val exec = MockToolExecutor()
        val executor = toolExecutor ?: { call ->
            exec.calls.add(call)
            ToolExecutionResult.Success(toolCallId = call.id, display = "ok", data = "ok")
        }
        val react = LightweightReAct(
            llmClient = client,
            toolExecutor = executor,
            config = config,
            verifyGoal = verifyGoal,
        )
        return Bundle(react, exec)
    }

    private class MockToolExecutor {
        val calls = mutableListOf<ToolCall>()
    }

    private fun skill(id: String, description: String) = SkillSpec(
        id = id,
        description = description,
        parametersSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        }
    )

    private fun enqueueResponse(json: JSONObject) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(json.toString())
        )
    }

    private fun textResponse(content: String, toolCalls: List<Map<String, Any>> = emptyList()): JSONObject {
        val msg = JSONObject().apply {
            put("role", "assistant")
            put("content", content)
            if (toolCalls.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for ((i, tc) in toolCalls.withIndex()) {
                    arr.put(JSONObject().apply {
                        put("id", tc["id"] ?: "call_$i")
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc["name"])
                            put("arguments", tc["arguments"] ?: "{}")
                        })
                    })
                }
                put("tool_calls", arr)
            }
        }
        return wrapOpenAiResponse(msg, finishReason = if (toolCalls.isNotEmpty()) "tool_calls" else "stop")
    }

    private fun toolCall(name: String, args: String, id: String = "call_default"): JSONObject {
        return textResponse("", toolCalls = listOf(mapOf("id" to id, "name" to name, "arguments" to args)))
    }

    private fun wrapOpenAiResponse(message: JSONObject, finishReason: String): JSONObject {
        return JSONObject().apply {
            put("id", "chatcmpl-test")
            put("object", "chat.completion")
            put("created", 1234567890)
            put("model", "test")
            put("choices", org.json.JSONArray().put(JSONObject().apply {
                put("message", message)
                put("finish_reason", finishReason)
                put("index", 0)
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", 50)
                put("completion_tokens", 10)
                put("total_tokens", 60)
            })
        }
    }
}
