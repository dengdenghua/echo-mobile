package com.apk.claw.android.tool.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpClient JSON-RPC 消息解析单测。
 *
 * 不测实际网络/进程通信(需 Mock),只测 [McpClient.parseAndDispatch] 的 JSON-RPC 协议解析。
 * 用反射调用 private 方法。
 */
class McpClientTest {

    /**
     * 构造一个 McpClient(stdio transport,不实际连接)并反射调用 parseAndDispatch。
     * 返回 (client, pendingQueue 的内容)。
     */
    private fun parseAndCapture(data: String): JsonObject? {
        val transport = McpClient.Transport.Stdio(listOf("echo", "test"))
        val client = McpClient("test-server", transport)

        // 反射拿 pendingResponses map
        val pendingField = McpClient::class.java.getDeclaredField("pendingResponses")
        pendingField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(client) as java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.LinkedBlockingQueue<JsonObject>>

        // 放一个假 queue 等待 id=1
        val queue = java.util.concurrent.LinkedBlockingQueue<JsonObject>(1)
        pending[1L] = queue

        // 反射调 parseAndDispatch
        val method = McpClient::class.java.getDeclaredMethod("parseAndDispatch", String::class.java)
        method.isAccessible = true
        method.invoke(client, data)

        return queue.poll()
    }

    @Test
    fun `解析标准 JSON-RPC 响应`() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""
        val resp = parseAndCapture(json)
        assertNotNull(resp)
        assertEquals(1L, resp!!.get("id").asLong)
        assertTrue(resp.has("result"))
    }

    @Test
    fun `解析 JSON-RPC 错误响应`() {
        val json = """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""
        val resp = parseAndCapture(json)
        assertNotNull(resp)
        val error = resp!!.getAsJsonObject("error")
        assertNotNull(error)
        assertEquals(-32601, error.get("code").asInt)
        assertEquals("Method not found", error.get("message").asString)
    }

    @Test
    fun `通知消息(无 id)被忽略不阻塞`() {
        val json = """{"jsonrpc":"2.0","method":"notifications/progress","params":{}}"""
        val resp = parseAndCapture(json)
        // 无 id → parseAndDispatch 直接 return,queue 仍为空
        assertNull(resp)
    }

    @Test
    fun `非法 JSON 不抛异常`() {
        // 防止恶意/损坏的消息让进程崩溃
        val resp = parseAndCapture("not valid json {")
        assertNull(resp)
    }

    @Test
    fun `未知 id 的响应被安全忽略`() {
        // id=999 没有对应的 pending queue → 不崩溃
        val json = """{"jsonrpc":"2.0","id":999,"result":{}}"""
        val resp = parseAndCapture(json)
        // id=999 找不到 queue,parseAndDispatch 直接 return
        assertNull(resp)
    }

    @Test
    fun `MCP Transport 数据类正确构造`() {
        val stdio = McpClient.Transport.Stdio(
            command = listOf("npx", "@modelcontextprotocol/server-filesystem", "/sdcard"),
            env = mapOf("NODE_PATH" to "/usr/lib"),
        )
        assertEquals(listOf("npx", "@modelcontextprotocol/server-filesystem", "/sdcard"), stdio.command)
        assertEquals("/usr/lib", stdio.env["NODE_PATH"])

        val sse = McpClient.Transport.Sse(
            url = "http://localhost:3001/sse",
            headers = mapOf("Authorization" to "Bearer token"),
        )
        assertEquals("http://localhost:3001/sse", sse.url)
        assertEquals("Bearer token", sse.headers["Authorization"])
    }

    @Test
    fun `McpToolInfo 数据类正确构造`() {
        val schema = JsonParser.parseString("""{"type":"object","properties":{"path":{"type":"string"}}}""").asJsonObject
        val tool = McpClient.McpToolInfo(
            name = "read_file",
            description = "Read a file",
            inputSchema = schema,
        )
        assertEquals("read_file", tool.name)
        assertEquals("Read a file", tool.description)
        assertTrue(tool.inputSchema.has("properties"))
    }
}
