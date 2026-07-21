package com.apk.claw.android.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 协议层离线单测 —— 纯 JUnit 4,无 Robolectric / 无 Android 依赖。
 *
 * 覆盖:
 *  - initialize 返回正确协议版本 + serverInfo
 *  - tools/list 返回数组(空 / 非空)
 *  - tools/call 未知工具 → 由 provider 返回失败(isError=true)
 *  - tools/call 高危工具被 ApprovalGate 拒绝
 *  - tools/call 高危工具被 ApprovalGate 放行后正常执行
 *  - 格式错误的 JSON → -32700 PARSE_ERROR(经 [McpSession] 处理)
 *  - initialize 前调用其他方法 → -32002 INITIALIZE_REQUIRED(经 [McpSession] 处理)
 *  - 未知方法 → -32601 METHOD_NOT_FOUND
 */
class McpDispatcherTest {

    private val gson = Gson()

    // ── 测试用 mock 后端 ──────────────────────────────────────

    /** 简单 provider:暴露两个工具,echo 和 send_sms。 */
    private class FakeProvider : McpToolRegistryProvider {
        var lastCall: Pair<String, Map<String, Any>>? = null

        override fun listTools(): List<McpToolInfo> = listOf(
            McpToolInfo(
                "echo",
                "回显输入文本",
                JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("text", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "要回显的文本")
                        })
                    })
                    add("required", com.google.gson.JsonArray().apply { add("text") })
                },
            ),
            McpToolInfo(
                "send_sms",
                "发送短信(高危)",
                JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("to", JsonObject().apply { addProperty("type", "string") })
                        add("body", JsonObject().apply { addProperty("type", "string") })
                    })
                },
            ),
        )

        override fun executeTool(name: String, args: Map<String, Any>): McpToolResult {
            lastCall = name to args
            return when (name) {
                "echo" -> McpToolResult(true, "echo: ${args["text"]}", null)
                "send_sms" -> McpToolResult(true, "sms sent to ${args["to"]}", null)
                else -> McpToolResult(false, "", "unknown tool: $name")
            }
        }
    }

    /** 自动允许的审批闸门(测试用,真实场景禁止使用)。 */
    private class AutoApproveGate : McpApprovalGate {
        override fun requestApproval(toolName: String, args: Map<String, Any>): ApprovalResult =
            ApprovalResult(true, "test auto-approve")
    }

    /** 自动拒绝的审批闸门。 */
    private class AutoDenyGate : McpApprovalGate {
        override fun requestApproval(toolName: String, args: Map<String, Any>): ApprovalResult =
            ApprovalResult(false, "test auto-deny")
    }

    private fun newDispatcher(
        provider: McpToolRegistryProvider = FakeProvider(),
        gate: McpApprovalGate = AutoDenyGate(),
    ): JsonRpcDispatcher = JsonRpcDispatcher(provider, gate, "octopus-mobile-test", "0.0.1")

    private fun req(method: String, id: Any? = 1, params: JsonObject? = null): JsonRpcRequest =
        JsonRpcRequest("2.0", id, method, params)

    // ── JsonRpcDispatcher 直接测试 ─────────────────────────────

    @Test
    fun `initialize returns protocol version and server info`() {
        val d = newDispatcher()
        val resp = d.dispatch(req("initialize"))

        assertEquals("2.0", resp.jsonrpc)
        assertEquals(1, resp.id)
        assertNull(resp.error)
        val result = resp.result!!
        assertEquals(MCP_PROTOCOL_VERSION, result.get("protocolVersion").asString)
        assertNotNull(result.getAsJsonObject("capabilities"))
        val serverInfo = result.getAsJsonObject("serverInfo")
        assertEquals("octopus-mobile-test", serverInfo.get("name").asString)
        assertEquals("0.0.1", serverInfo.get("version").asString)
    }

    @Test
    fun `tools list returns tools array`() {
        val d = newDispatcher()
        val resp = d.dispatch(req("tools/list"))

        assertNull(resp.error)
        val tools = resp.result!!.getAsJsonArray("tools")
        assertEquals(2, tools.size())

        val echo = tools[0].asJsonObject
        assertEquals("echo", echo.get("name").asString)
        assertEquals("回显输入文本", echo.get("description").asString)
        val schema = echo.getAsJsonObject("inputSchema")
        assertEquals("object", schema.get("type").asString)
    }

    @Test
    fun `tools list returns empty array when no provider`() {
        val d = JsonRpcDispatcher(NoopMcpToolRegistryProvider(), AutoDenyGate())
        val resp = d.dispatch(req("tools/list"))

        val tools = resp.result!!.getAsJsonArray("tools")
        assertEquals(0, tools.size())
    }

    @Test
    fun `tools call unknown tool returns error result from provider`() {
        val provider = FakeProvider()
        val d = newDispatcher(provider = provider)
        val params = JsonObject().apply {
            addProperty("name", "nonexistent_tool")
            add("arguments", JsonObject())
        }
        val resp = d.dispatch(req("tools/call", params = params))

        // MCP 协议:tools/call 失败不返回 JSON-RPC error,而是 result.isError=true
        assertNull(resp.error)
        val result = resp.result!!
        assertTrue(result.get("isError").asBoolean)
        val content = result.getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertTrue(content[0].asJsonObject.get("text").asString.contains("unknown tool"))
    }

    @Test
    fun `tools call normal tool executes successfully`() {
        val provider = FakeProvider()
        val d = newDispatcher(provider = provider)
        val params = JsonObject().apply {
            addProperty("name", "echo")
            add("arguments", JsonObject().apply { addProperty("text", "hello") })
        }
        val resp = d.dispatch(req("tools/call", params = params))

        assertNull(resp.error)
        val result = resp.result!!
        assertFalse(result.get("isError").asBoolean)
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertEquals("echo: hello", text)
        assertEquals("echo" to mapOf("text" to "hello"), provider.lastCall)
    }

    @Test
    fun `tools call high risk tool is denied by approval gate`() {
        val provider = FakeProvider()
        val d = newDispatcher(provider = provider, gate = AutoDenyGate())
        val params = JsonObject().apply {
            addProperty("name", "send_sms")
            add("arguments", JsonObject().apply {
                addProperty("to", "10086")
                addProperty("body", "hello")
            })
        }
        val resp = d.dispatch(req("tools/call", params = params))

        // 审批拒绝 → tool 没被执行
        assertNull(provider.lastCall)
        val result = resp.result!!
        assertTrue(result.get("isError").asBoolean)
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertTrue(text.contains("denied"))
    }

    @Test
    fun `tools call high risk tool succeeds when approved`() {
        val provider = FakeProvider()
        val d = newDispatcher(provider = provider, gate = AutoApproveGate())
        val params = JsonObject().apply {
            addProperty("name", "send_sms")
            add("arguments", JsonObject().apply {
                addProperty("to", "10086")
                addProperty("body", "hello")
            })
        }
        val resp = d.dispatch(req("tools/call", params = params))

        // 审批通过 → 工具被执行
        assertEquals("send_sms", provider.lastCall?.first)
        val result = resp.result!!
        assertFalse(result.get("isError").asBoolean)
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertEquals("sms sent to 10086", text)
    }

    @Test
    fun `unknown method returns method not found error`() {
        val d = newDispatcher()
        val resp = d.dispatch(req("nonexistent_method"))

        assertNotNull(resp.error)
        assertEquals(JsonRpcErrors.METHOD_NOT_FOUND, resp.error!!.code)
        assertTrue(resp.error!!.message.contains("nonexistent_method"))
    }

    @Test
    fun `ping returns empty result`() {
        val d = newDispatcher()
        val resp = d.dispatch(req("ping"))

        assertNull(resp.error)
        assertNotNull(resp.result)
    }

    @Test
    fun `notifications initialized returns null id`() {
        val d = newDispatcher()
        val resp = d.dispatch(req("notifications/initialized", id = null))

        // 通知:响应 id 为 null
        assertNull(resp.id)
        assertNull(resp.result)
        assertNull(resp.error)
    }

    // ── McpSession 集成测试(覆盖 parse error / initialize required) ──

    @Test
    fun `malformed JSON returns parse error`() {
        val session = McpSession()
        val dispatcher = newDispatcher()
        val raw = "{ this is not valid json"
        val respText = session.handleMessage(raw, dispatcher)

        assertNotNull(respText)
        val resp = JsonParser.parseString(respText).asJsonObject
        assertEquals("2.0", resp.get("jsonrpc").asString)
        val error = resp.getAsJsonObject("error")
        assertEquals(JsonRpcErrors.PARSE_ERROR, error.get("code").asInt)
        assertTrue(error.get("message").asString.contains("Parse error"))
    }

    @Test
    fun `calling tools list before initialize returns initialize required`() {
        val session = McpSession()
        val dispatcher = newDispatcher()
        val raw = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        val respText = session.handleMessage(raw, dispatcher)

        assertNotNull(respText)
        val resp = JsonParser.parseString(respText).asJsonObject
        val error = resp.getAsJsonObject("error")
        assertEquals(JsonRpcErrors.INITIALIZE_REQUIRED, error.get("code").asInt)
    }

    @Test
    fun `initialize then tools list works end to end`() {
        val session = McpSession()
        val dispatcher = newDispatcher()

        // 1. initialize
        val initRaw = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val initRespText = session.handleMessage(initRaw, dispatcher)
        assertNotNull(initRespText)
        val initResp = JsonParser.parseString(initRespText).asJsonObject
        assertEquals(MCP_PROTOCOL_VERSION, initResp.getAsJsonObject("result").get("protocolVersion").asString)
        assertTrue(session.initialized)
        assertEquals(MCP_PROTOCOL_VERSION, session.protocolVersion)

        // 2. tools/list
        val listRaw = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        val listRespText = session.handleMessage(listRaw, dispatcher)
        assertNotNull(listRespText)
        val listResp = JsonParser.parseString(listRespText).asJsonObject
        val tools = listResp.getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(2, tools.size())
    }

    @Test
    fun `notification before initialize returns null response`() {
        val session = McpSession()
        val dispatcher = newDispatcher()
        // notifications/initialized 不需要先 initialize
        val raw = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val respText = session.handleMessage(raw, dispatcher)
        assertNull(respText)
    }

    @Test
    fun `tools call missing name param returns invalid params`() {
        val d = newDispatcher()
        val params = JsonObject().apply {
            // 没有 name 字段
            add("arguments", JsonObject())
        }
        val resp = d.dispatch(req("tools/call", params = params))

        assertNotNull(resp.error)
        assertEquals(JsonRpcErrors.INVALID_PARAMS, resp.error!!.code)
    }
}
