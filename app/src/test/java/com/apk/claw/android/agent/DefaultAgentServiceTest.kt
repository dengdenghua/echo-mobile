package com.apk.claw.android.agent

import com.apk.claw.android.octopus_mobile.GoalVerifier
import java.lang.reflect.Proxy
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.LinkedList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAgentServiceTest {

    @Test
    fun `completion emits structured verdict before answer and only verified goals permit caching`() {
        val stateClass = DefaultAgentService::class.java.declaredClasses.first { it.simpleName == "AgentLoopState" }
        val constructor = stateClass.getDeclaredConstructor(
            MutableList::class.java, Int::class.javaPrimitiveType, String::class.java,
        ).apply { isAccessible = true }
        val verdictField = stateClass.getDeclaredField("goalVerdict").apply { isAccessible = true }
        val successField = stateClass.getDeclaredField("taskSucceeded").apply { isAccessible = true }
        val complete = DefaultAgentService::class.java.getDeclaredMethod(
            "completeWithVerification", stateClass, AgentCallback::class.java, String::class.java,
        ).apply { isAccessible = true }
        GoalVerifier.Status.entries.forEach { status ->
            val events = mutableListOf<String>()
            val verdict = GoalVerifier.Verdict(status, "证据")
            val callback = Proxy.newProxyInstance(
                AgentCallback::class.java.classLoader, arrayOf(AgentCallback::class.java),
            ) {
                _, method, args ->
                when (method.name) {
                    "onGoalVerification" -> {
                        assertEquals(verdict, args[1])
                        events.add("verification")
                    }
                    "onComplete" -> {
                        assertEquals(verdict.present("模型反馈"), args[1])
                        events.add("completion")
                    }
                }
                null
            } as AgentCallback
            val state = constructor.newInstance(mutableListOf<ChatMessage>(), 2, "保存文件")
            verdictField.set(state, verdict)
            complete.invoke(DefaultAgentService(), state, callback, "模型反馈")
            assertEquals(listOf("verification", "completion"), events)
            assertEquals(status == GoalVerifier.Status.ACHIEVED, successField.getBoolean(state))
        }
    }

    // ==================== 反射辅助方法 ====================

    /** 通过反射创建私有数据类 RoundFingerprint 实例 */
    private fun createFingerprint(screenHash: Int, toolCall: String): Any {
        val clazz = DefaultAgentService::class.java
            .getDeclaredClasses()
            .first { it.simpleName == "RoundFingerprint" }
        val constructor = clazz.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(screenHash, toolCall)
    }

    /** 通过反射调用私有方法 isStuckInLoop */
    private fun invokeIsStuckInLoop(service: DefaultAgentService, history: LinkedList<Any>): Boolean {
        val method = DefaultAgentService::class.java
            .getDeclaredMethod("isStuckInLoop", LinkedList::class.java)
        method.isAccessible = true
        return method.invoke(service, history) as Boolean
    }

    // ==================== RoundFingerprint 相等性测试 ====================

    @Test
    fun `RoundFingerprint equals when same screenHash and toolCall`() {
        val fp1 = createFingerprint(12345, "tap:100,200")
        val fp2 = createFingerprint(12345, "tap:100,200")
        assertEquals(fp1, fp2)
        assertEquals(fp1.hashCode(), fp2.hashCode())
    }

    @Test
    fun `RoundFingerprint not equals when different screenHash`() {
        val fp1 = createFingerprint(12345, "tap:100,200")
        val fp2 = createFingerprint(99999, "tap:100,200")
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `RoundFingerprint not equals when different toolCall`() {
        val fp1 = createFingerprint(12345, "tap:100,200")
        val fp2 = createFingerprint(12345, "swipe:up")
        assertNotEquals(fp1, fp2)
    }

    // ==================== isStuckInLoop 窗口判断测试 ====================

    @Test
    fun `isStuckInLoop returns false when history is empty`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        assertFalse(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns false when window not full`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        // LOOP_DETECT_WINDOW = 4，3 个相同指纹不够触发
        repeat(3) { history.add(createFingerprint(12345, "tap:100,200")) }
        assertFalse(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns true when all fingerprints equal within window`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        repeat(4) { history.add(createFingerprint(12345, "tap:100,200")) }
        assertTrue(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns false when fingerprints differ`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        history.add(createFingerprint(12345, "tap:100,200"))
        history.add(createFingerprint(12345, "tap:100,200"))
        history.add(createFingerprint(12345, "tap:100,200"))
        history.add(createFingerprint(99999, "swipe:up"))
        assertFalse(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns true when window exceeds size and all equal`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        // 5 个相同指纹（超过窗口大小 4），应触发
        repeat(5) { history.add(createFingerprint(777, "observe:get_screen_info")) }
        assertTrue(invokeIsStuckInLoop(service, history))
    }

    // ==================== compressHistoryForSend 测试 ====================

    /** 通过反射调用私有方法 compressHistoryForSend */
    private fun invokeCompressHistoryForSend(service: DefaultAgentService, messages: MutableList<ChatMessage>) {
        val method = DefaultAgentService::class.java
            .getDeclaredMethod("compressHistoryForSend", MutableList::class.java)
        method.isAccessible = true
        method.invoke(service, messages)
    }

    @Test
    fun `compress replaces old get_screen_info with placeholder`() {
        val service = DefaultAgentService()
        val messages = mutableListOf<ChatMessage>(
            ToolExecutionResultMessage.from(
                ToolExecutionRequest.builder().id("t1").name("get_screen_info").arguments("{}").build(),
                "{\"isSuccess\":true,\"data\":\"screen data 1\"}"
            ),
            ToolExecutionResultMessage.from(
                ToolExecutionRequest.builder().id("t2").name("get_screen_info").arguments("{}").build(),
                "{\"isSuccess\":true,\"data\":\"screen data 2\"}"
            ),
            ToolExecutionResultMessage.from(
                ToolExecutionRequest.builder().id("t3").name("get_screen_info").arguments("{}").build(),
                "{\"isSuccess\":true,\"data\":\"screen data 3\"}"
            ),
        )
        invokeCompressHistoryForSend(service, messages)

        // 前两条替换为占位符,最后一条完整保留
        assertEquals("[屏幕信息已省略]", (messages[0] as ToolExecutionResultMessage).text())
        assertEquals("[屏幕信息已省略]", (messages[1] as ToolExecutionResultMessage).text())
        assertEquals(
            "{\"isSuccess\":true,\"data\":\"screen data 3\"}",
            (messages[2] as ToolExecutionResultMessage).text()
        )
    }

    @Test
    fun `compress replaces old auto-screenshots with placeholder`() {
        val service = DefaultAgentService()
        val messages = mutableListOf<ChatMessage>(
            UserMessage.from("[自动截屏] 这是当前屏幕状态，请据此决策。"),
            UserMessage.from("[自动截屏] 这是当前屏幕状态，请据此决策。"),
            UserMessage.from("[自动截屏] 这是当前屏幕状态，请据此决策。"),
        )
        invokeCompressHistoryForSend(service, messages)

        // 前两条替换为占位符,最后一条完整保留
        assertEquals("[早期自动截屏已省略]", (messages[0] as UserMessage).singleText())
        assertEquals("[早期自动截屏已省略]", (messages[1] as UserMessage).singleText())
        assertEquals("[自动截屏] 这是当前屏幕状态，请据此决策。", (messages[2] as UserMessage).singleText())
    }

    @Test
    fun `compress keeps recent 3 rounds intact`() {
        val service = DefaultAgentService()
        val messages = mutableListOf<ChatMessage>()
        val originalResults = mutableListOf<String>()

        // 构造 5 轮 AiMessage + ToolResult(使用 tap 工具,避免被 get_screen_info 全局替换逻辑干扰)
        repeat(5) { i ->
            messages.add(AiMessage.from("Round ${i + 1} thinking"))
            val req = ToolExecutionRequest.builder()
                .id("t${i + 1}")
                .name("tap")
                .arguments("{\"x\":100,\"y\":200}")
                .build()
            // 构造 >100 字符的结果,确保 compressToolResultMessage 会实际压缩
            val longData = "d".repeat(150)
            val resultJson = "{\"isSuccess\":true,\"data\":\"$longData\"}"
            originalResults.add(resultJson)
            messages.add(ToolExecutionResultMessage.from(req, resultJson))
        }
        invokeCompressHistoryForSend(service, messages)

        // 保护区外(第 1、2 轮)的 ToolResult 应被压缩为摘要
        val compressed1 = (messages[1] as ToolExecutionResultMessage).text()
        val compressed2 = (messages[3] as ToolExecutionResultMessage).text()
        assertNotEquals(originalResults[0], compressed1)
        assertNotEquals(originalResults[1], compressed2)
        assertTrue(compressed1.startsWith("✓ "))

        // 保护区(最近 3 轮)的 ToolResult 应保持不变
        assertEquals(originalResults[2], (messages[5] as ToolExecutionResultMessage).text())
        assertEquals(originalResults[3], (messages[7] as ToolExecutionResultMessage).text())
        assertEquals(originalResults[4], (messages[9] as ToolExecutionResultMessage).text())
    }
}
