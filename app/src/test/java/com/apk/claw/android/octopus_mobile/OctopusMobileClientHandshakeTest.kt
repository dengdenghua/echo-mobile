package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.async
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OctopusMobileClientHandshakeTest {

    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OctopusMobileClient>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        KVUtils.setInsecureOctopusRuntimeAllowed(false)
    }

    @After
    fun tearDown() {
        clients.forEach { it.disconnect() }
        clients.clear()
        server.shutdown()
        KVUtils.setInsecureOctopusRuntimeAllowed(false)
        KVUtils.resetForTest()
    }

    @Test
    fun `client only becomes online after explicit hello ack`() {
        val online = CountDownLatch(1)
        val helloSeen = CountDownLatch(1)
        val socketClosed = enqueueRuntimeSocket { webSocket, text ->
            val hello = JSONObject(text)
            helloSeen.countDown()
            webSocket.send("""{"jsonrpc":"2.0","method":"noise","id":"noise-1"}""")
            webSocket.send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", hello.getString("id"))
                    .put("result", JSONObject().put("registered", true))
                    .toString(),
            )
        }

        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        assertTrue(online.await(2, TimeUnit.SECONDS))
        assertEquals(ConnectionState.ONLINE, client.currentState())
        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `client stays handshaking when server sends unrelated message`() {
        val helloSeen = CountDownLatch(1)
        val socketClosed = enqueueRuntimeSocket { webSocket, _ ->
            helloSeen.countDown()
            webSocket.send("""{"jsonrpc":"2.0","method":"noise","id":"noise-1"}""")
        }

        val client = newClient()
        client.connect()

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(ConnectionState.HELLO_SENT, client.currentState())
        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `tool execute before hello ack is rejected`() {
        val helloSeen = CountDownLatch(1)
        // 服务端在回 hello ack 之前就抢发 tool/execute
        val socketClosed = enqueueRuntimeSocket { webSocket, _ ->
            helloSeen.countDown()
            webSocket.send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "tool/execute")
                    .put("id", "evil-1")
                    .put("tool", "run_code")
                    .put("args", JSONObject().put("code", "1"))
                    .toString(),
            )
        }

        val client = newClient()
        val dispatched = CountDownLatch(1)
        client.onToolExecute = { dispatched.countDown() }
        client.connect()

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        // 握手未确认 → tool/execute 不应被派发
        assertEquals(false, dispatched.await(500, TimeUnit.MILLISECONDS))
        assertEquals(ConnectionState.HELLO_SENT, client.currentState())
        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `remote cleartext runtime is blocked before websocket opens`() {
        // 公网明文 ws:// 必须在握手前被 MobileRuntimeSecurity 拦下。
        // 203.0.113.0/24 是 RFC 5737 文档示例段,代表"非私有、非环回"的公网地址。
        // LAN(192.168.x.x 等)已被放行用于本地开发,不能用作"被拦截"用例。
        val client = OctopusMobileClient("ws://203.0.113.42:8765", "test-device")
        client.connect()
        assertEquals(ConnectionState.DISCONNECTED, client.currentState())
    }

    @Test
    fun `runtime nested tool params are dispatched and wrong device is rejected`() {
        val dispatched = CountDownLatch(1)
        val online = CountDownLatch(1)
        enqueueRuntimeSocket { webSocket, text ->
            val hello = JSONObject(text)
            webSocket.send(JSONObject().put("id", hello.getString("id"))
                .put("result", JSONObject().put("registered", true)).toString())
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        client.onToolExecute = {
            assertEquals("android.get_screen_info", it.name)
            assertEquals("call-1", it.id)
            dispatched.countDown()
        }
        client.handleIncomingMessage("""{"jsonrpc":"2.0","method":"tool/execute","id":"call-1",
            "params":{"id":"call-1","tentacle_id":"test-device","tool":"android.get_screen_info","args":{}}}""")
        assertTrue(dispatched.await(2, TimeUnit.SECONDS))
        client.onToolExecute = { throw AssertionError("dispatched to wrong device") }
        client.handleIncomingMessage("""{"method":"tool/execute","id":"wrong",
            "params":{"tentacle_id":"another-device","tool":"android.tap","args":{}}}""")
    }

    @Test
    fun `task workspace uses paired socket and reads durable server records`() {
        val online = CountDownLatch(1)
        val closed = enqueueRuntimeSocket { socket, text ->
            val request = JSONObject(text)
            val result = if (request.getString("method") == "device/hello") {
                JSONObject().put("registered", true)
            } else {
                assertEquals("task/workspace/list", request.getString("method"))
                JSONObject().put("tasks", org.json.JSONArray().put(
                    JSONObject().put("id", "same-task").put("status", "succeeded"),
                ))
            }
            socket.send(JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id"))
                .put("result", result).toString())
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        val response = kotlinx.coroutines.runBlocking { client.taskWorkspace("list") }
        assertEquals("same-task", response.getAsJsonArray("tasks")[0].asJsonObject.get("id").asString)
        client.disconnect()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `result review carries task revision through paired socket`() = kotlinx.coroutines.runBlocking {
        val online = CountDownLatch(1)
        enqueueRuntimeSocket { socket, text ->
            val request = JSONObject(text)
            val result = if (request.getString("method") == "device/hello") {
                JSONObject().put("registered", true)
            } else {
                assertEquals("task/workspace/review_result", request.getString("method"))
                val args = request.getJSONObject("params")
                assertEquals("same-task", args.getString("id"))
                assertEquals("result-revision", args.getString("revision"))
                assertEquals("not_achieved", args.getString("outcome"))
                JSONObject().put("id", "same-task").put("result_review",
                    JSONObject().put("outcome", "not_achieved").put("reviewed_by", "device:test-device"))
            }
            socket.send(JSONObject().put("id", request.getString("id")).put("result", result).toString())
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        val result = client.taskWorkspace("review_result", mapOf(
            "id" to "same-task", "revision" to "result-revision", "outcome" to "not_achieved",
        ))
        assertEquals("not_achieved", result.getAsJsonObject("result_review").get("outcome").asString)
    }

    @Test
    fun `heartbeat binds identity and peer calls receive real result`() = kotlinx.coroutines.runBlocking {
        val online = CountDownLatch(1)
        val heartbeat = CountDownLatch(1)
        enqueueRuntimeSocket { webSocket, text ->
            val message = JSONObject(text)
            when (message.getString("method")) {
                "device/hello" -> webSocket.send(JSONObject().put("id", message.getString("id"))
                    .put("result", JSONObject().put("registered", true)).toString())
                "device/heartbeat" -> {
                    assertEquals("test-device", message.getJSONObject("params").getString("tentacle_id"))
                    heartbeat.countDown()
                }
                "device/call" -> {
                    assertEquals("vm-1", message.getJSONObject("params").getString("target_device_id"))
                    webSocket.send(JSONObject().put("id", message.getString("id"))
                        .put("result", JSONObject().put("success", true).put("data", "from-vm")).toString())
                }
            }
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        client.send(EnvelopeFactory.heartbeat("", null, 80, false, null))
        assertTrue(heartbeat.await(2, TimeUnit.SECONDS))
        val result = client.executePeerTool("vm-1", "device.info", emptyMap())
        assertEquals("from-vm", result.get("data").asString)
    }

    @Test
    fun `nested failed task result is not reported as success`() = kotlinx.coroutines.runBlocking {
        val online = CountDownLatch(1)
        enqueueRuntimeSocket { webSocket, text ->
            val message = JSONObject(text)
            if (message.getString("method") == "device/hello") {
                webSocket.send(JSONObject().put("id", message.getString("id"))
                    .put("result", JSONObject().put("registered", true)).toString())
            } else if (message.getString("method") == "task/execute") {
                webSocket.send(JSONObject().put("method", "task/result")
                    .put("params", JSONObject().put("task_id", message.getString("id"))
                        .put("success", false).put("response", "device unavailable")).toString())
            }
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        val result = client.executeRemoteTask("test", IntentClassifier.classify("test"))
        assertEquals(RemoteTaskResult.Failure("device unavailable"), result)
    }

    @Test
    fun `reconfigured client reconnects with new endpoint token and capabilities`() {
        val online = CountDownLatch(1)
        val reconnected = CountDownLatch(1)
        enqueueRuntimeSocket { webSocket, text ->
            val hello = JSONObject(text)
            webSocket.send(JSONObject().put("id", hello.getString("id"))
                .put("result", JSONObject().put("registered", true)).toString())
        }
        val url = server.url("/first").toString().replace("http://", "ws://")
        val client = OctopusMobileClient(url, "test-device", "old-token") {
            listOf("android.get_screen_info")
        }.also { clients.add(it) }
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        assertEquals("/first", server.takeRequest(2, TimeUnit.SECONDS)?.path)

        enqueueRuntimeSocket { webSocket, text ->
            val hello = JSONObject(text)
            val params = hello.getJSONObject("params")
            assertEquals("new-token", params.getString("auth_token"))
            assertEquals("android", params.getString("platform"))
            assertEquals("android.get_screen_info", params.getJSONArray("capabilities").getString(0))
            webSocket.send(JSONObject().put("id", hello.getString("id"))
                .put("result", JSONObject().put("registered", true)).toString())
        }
        client.onStateChanged = { if (it == ConnectionState.ONLINE) reconnected.countDown() }
        client.configure(server.url("/second").toString().replace("http://", "ws://"), "new-token")
        client.connect()
        assertTrue(reconnected.await(2, TimeUnit.SECONDS))
        assertEquals("/second", server.takeRequest(2, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun `workflow submits ordered stages and advances a revision`() = kotlinx.coroutines.runBlocking {
        val online = CountDownLatch(1)
        enqueueRuntimeSocket { socket, text ->
            val request = JSONObject(text)
            val result = when (request.getString("method")) {
                "device/hello" -> JSONObject().put("registered", true)
                "task/workspace/submit" -> {
                    val stages = request.getJSONObject("params").getJSONArray("stages")
                    assertEquals("pc", stages.getJSONObject(0).getString("device_id"))
                    assertEquals("手机接续", stages.getJSONObject(1).getString("task"))
                    JSONObject().put("id", "parent").put("stage_index", 0)
                }
                "task/workspace/advance" -> {
                    assertEquals("checked-stage", request.getJSONObject("params").getString("revision"))
                    JSONObject().put("id", "parent").put("stage_index", 1).put("status", "planning")
                }
                else -> error("Unexpected workflow method")
            }
            socket.send(JSONObject().put("id", request.getString("id")).put("result", result).toString())
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        val submitted = client.taskWorkspace("submit", mapOf("id" to "parent", "task" to "协作", "stages" to listOf(
            mapOf("device_id" to "pc", "task" to "电脑处理"), mapOf("device_id" to "test-device", "task" to "手机接续"),
        )))
        assertEquals(0, submitted.get("stage_index").asInt)
        val next = client.taskWorkspace("advance", mapOf("id" to "parent", "revision" to "checked-stage"))
        assertEquals(1, next.get("stage_index").asInt)
        assertEquals("planning", next.get("status").asString)
    }

    @Test
    fun `replacing an online connection fails pending calls and closes old socket`() = kotlinx.coroutines.runBlocking {
        val online = CountDownLatch(1)
        val received = CountDownLatch(1)
        val oldClosed = enqueueRuntimeSocket { socket, text ->
            val request = JSONObject(text)
            if (request.getString("method") == "device/hello") {
                socket.send(JSONObject().put("id", request.getString("id"))
                    .put("result", JSONObject().put("registered", true)).toString())
            } else if (request.getString("method") == "task/workspace/list") {
                received.countDown()
            }
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { client.taskWorkspace("list") }
        }
        assertTrue(received.await(2, TimeUnit.SECONDS))
        val replacementOnline = CountDownLatch(1)
        enqueueRuntimeSocket { socket, text ->
            val request = JSONObject(text)
            assertEquals("device/hello", request.getString("method"))
            socket.send(JSONObject().put("id", request.getString("id"))
                .put("result", JSONObject().put("registered", true)).toString())
        }
        client.onStateChanged = { if (it == ConnectionState.ONLINE) replacementOnline.countDown() }
        client.connect()
        val failure = kotlinx.coroutines.withTimeout(2_000) { pending.await() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(oldClosed.await(2, TimeUnit.SECONDS))
        assertTrue(replacementOnline.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `connection loss fails pending workspace call without replay`() = kotlinx.coroutines.runBlocking {
        val online = CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        enqueueRuntimeSocket { socket, text ->
            val request = JSONObject(text)
            if (request.getString("method") == "device/hello") {
                socket.send(JSONObject().put("id", request.getString("id"))
                    .put("result", JSONObject().put("registered", true)).toString())
            } else if (request.getString("method") == "task/workspace/submit") {
                requests.incrementAndGet()
                socket.close(1001, "test connection loss")
            }
        }
        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()
        assertTrue(online.await(2, TimeUnit.SECONDS))
        val failure = kotlinx.coroutines.withTimeout(2_000) {
            runCatching { client.taskWorkspace("submit", mapOf("id" to "stable-task", "task" to "test")) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, requests.get())
        client.disconnect()
    }

    private fun newClient(): OctopusMobileClient =
        OctopusMobileClient(server.url("/ws").toString().replace("http://", "ws://"), "test-device")
            .also { clients.add(it) }

    private fun enqueueRuntimeSocket(
        onText: (WebSocket, String) -> Unit,
    ): CountDownLatch {
        val socketClosed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    onText(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socketClosed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketClosed.countDown()
                }
            }),
        )
        return socketClosed
    }
}
