package com.apk.claw.android.tool

import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ToolRegistry.executeToolsBatch] 单元测试。
 *
 * 覆盖:
 *  - 空调用列表返回空结果
 *  - 3 个只读工具并行执行,返回 3 个结果(顺序与输入一致)
 *  - 写工具(tap)+ 只读工具,写工具走串行路径
 *  - 有 dependsOn 的调用走串行路径
 *  - 并发安全性 — results 数组与 calls 顺序一一对应(无错位)
 *
 * 用 `wait` 工具(只读 + LOW 风险 + 在 [BaseTool.READONLY_TOOLS] 中)做并行只读探针,
 * 不同 `duration_ms` 让结果文本可区分("Waited for Nms"),便于校验顺序对齐。
 * 用 `tap` 工具(MEDIUM 风险 + 非只读)做写工具探针,测试环境无无障碍服务,会返回 error 结果,
 * 但 error 也是合法 ToolResult,不影响顺序断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class)
class ExecuteToolsBatchTest {

    @Before
    fun setUp() {
        ToolRegistry.guardrail.reset()
        ToolRegistry.safetyGate = null
        ToolRegistry.turnScorer = null
        ToolRegistry.eventBus = null
        ToolRegistry.approvalGate = null
        KVUtils.setToolDisabled("wait", false)
        KVUtils.setToolDisabled("tap", false)
        KVUtils.setToolDisabled("finish", false)
        ToolAuditLog.clear()
        ToolRegistry.clearBrowserEngine()
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
    }

    @After
    fun tearDown() {
        com.apk.claw.android.octopus_mobile.ToolAuditLog.resetSecretCacheForTest()
        com.apk.claw.android.utils.KVUtils.resetForTest()
    }

    // ── 测试用例 1:空调用列表 ───────────────────────────────

    @Test
    fun `empty calls list returns empty results`() {
        val results = ToolRegistry.executeToolsBatch(emptyList())
        assertNotNull(results)
        assertTrue("empty calls should return empty list", results.isEmpty())
    }

    // ── 测试用例 2:3 个只读工具并行执行 ────────────────────

    @Test
    fun `three readonly tools execute in parallel and return results in order`() {
        val calls = listOf(
            ToolCall("wait", mapOf("duration_ms" to 0L)),
            ToolCall("wait", mapOf("duration_ms" to 1L)),
            ToolCall("wait", mapOf("duration_ms" to 2L)),
        )
        val results = ToolRegistry.executeToolsBatch(calls)

        assertEquals("result count must match call count", 3, results.size)
        // wait 工具返回 "Waited for Nms" 文本,验证顺序与输入一致
        assertTrue("call 0 should succeed", results[0].isSuccess)
        assertTrue("call 1 should succeed", results[1].isSuccess)
        assertTrue("call 2 should succeed", results[2].isSuccess)
        assertEquals("Waited for 0ms", results[0].data)
        assertEquals("Waited for 1ms", results[1].data)
        assertEquals("Waited for 2ms", results[2].data)
    }

    // ── 测试用例 3:1 个写工具 + 1 个只读工具 ───────────────

    @Test
    fun `write tool and readonly tool both return results in order`() {
        // tap 在测试环境无无障碍服务,会返回 error 结果(MEDIUM 风险 → 走串行路径)
        // wait 是只读 LOW → 走并行路径
        val calls = listOf(
            ToolCall("tap", mapOf("x" to 100, "y" to 200)),
            ToolCall("wait", mapOf("duration_ms" to 0L)),
        )
        val results = ToolRegistry.executeToolsBatch(calls)

        assertEquals(2, results.size)
        // tap 期望失败(无无障碍),但仍是合法 ToolResult,且占位 0
        assertNotNull("tap result must be present at index 0", results[0])
        // wait 期望成功,占位 1
        assertTrue("wait result at index 1 should succeed", results[1].isSuccess)
        assertEquals("Waited for 0ms", results[1].data)
        // 关键不变量:顺序与 calls 一致(tap 在前 wait 在后,即使 tap 失败也不抢占 wait 的位置)
        assertEquals("tap", "tap", calls[0].name)
        assertEquals("wait", "wait", calls[1].name)
    }

    // ── 测试用例 4:有 dependsOn 的调用走串行路径 ───────────

    @Test
    fun `call with dependsOn goes through serial path and returns result`() {
        // wait 本是只读 LOW → 并行;但加 dependsOn=[0] 后强制走串行路径
        val calls = listOf(
            ToolCall("wait", mapOf("duration_ms" to 0L)),
            ToolCall("wait", mapOf("duration_ms" to 1L), dependsOn = listOf(0)),
        )
        val results = ToolRegistry.executeToolsBatch(calls)

        assertEquals(2, results.size)
        assertTrue("dependency target (call 0) should succeed", results[0].isSuccess)
        assertTrue("dependent call (call 1) should succeed", results[1].isSuccess)
        assertEquals("Waited for 0ms", results[0].data)
        assertEquals("Waited for 1ms", results[1].data)
    }

    // ── 测试用例 5:并发安全性 — 顺序一一对应 ───────────────

    @Test
    fun `concurrent execution returns results aligned with calls order`() {
        // 故意让 duration 顺序与完成顺序不同,验证 results 仍按 calls 顺序对齐。
        // 线程池大小 4,5 个调用会复用线程;即使完成顺序为 5/10/20/30/50ms,
        // results[k] 必须对应 calls[k].duration_ms。
        val durations = listOf(10L, 50L, 20L, 5L, 30L)
        val calls = durations.map { ToolCall("wait", mapOf("duration_ms" to it)) }
        val results = ToolRegistry.executeToolsBatch(calls)

        assertEquals("result count must match call count", durations.size, results.size)
        for ((i, d) in durations.withIndex()) {
            assertTrue("result[$i] should succeed", results[i].isSuccess)
            assertEquals(
                "result[$i] must align with calls[$i] (duration=$d), not with completion order",
                "Waited for ${d}ms",
                results[i].data,
            )
        }
    }
}
