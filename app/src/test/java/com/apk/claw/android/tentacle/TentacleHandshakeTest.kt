package com.apk.claw.android.tentacle

import android.content.Context
import com.apk.claw.android.tool.ToolResult
import com.google.gson.JsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tentacle 握手协议 JVM 单测.
 *
 * 覆盖:
 *  1. device/hello 帧格式正确(DeviceRegistration.deviceHelloFrame)
 *  2. 收到 device/welcome 后状态变 ONLINE(OctopusMobileClient + MockWebServer)
 *  3. 心跳 30s 间隔(DeviceRegistration.HEARTBEAT_INTERVAL_MS)
 *  4. 重连指数退避(OctopusMobileClient.computeBackoff)
 *
 * 用 Robolectric 提供 Context + 桥接 android.util.Log(XLog 调用).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TentacleHandshakeTest {

    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OctopusMobileClient>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        clients.forEach { it.disconnect() }
        clients.clear()
        server.shutdown()
    }

    // ── 1. device/hello 帧格式 ─────────────────────────────────────────────

    @Test
    fun `device hello frame contains required fields`() {
        val reg = DeviceRegistration(context, capabilitiesProvider = { listOf("tap", "swipe", "screenshot") })
        val frame = reg.deviceHelloFrame(authToken = "tok-abc")

        assertEquals("device/hello", frame.get("type").asString)
        assertNotNull(frame.get("device_id").asString)
        assertTrue(frame.get("device_id").asString.isNotEmpty())

        // capabilities 应为字符串数组
        val caps = frame.getAsJsonArray("capabilities")
        assertNotNull(caps)
        assertEquals(3, caps.size())
        assertEquals("tap", caps[0].asString)
        assertEquals("swipe", caps[1].asString)
        assertEquals("screenshot", caps[2].asString)

        // auth_token 应原样透传
        assertEquals("tok-abc", frame.get("auth_token").asString)

        // 元数据
        assertEquals("android", frame.get("platform").asString)
        assertNotNull(frame.get("brand"))
        assertNotNull(frame.get("model"))
    }

    @Test
    fun `device hello frame omits auth_token when blank`() {
        val reg = DeviceRegistration(context)
        val frame = reg.deviceHelloFrame(authToken = "")
        assertFalse(frame.has("auth_token"))
    }

    @Test
    fun `device_id is stable across instances`() {
        val reg1 = DeviceRegistration(context)
        val reg2 = DeviceRegistration(context)
        assertEquals(reg1.deviceId, reg2.deviceId)
    }

    // ── 2. 收到 device/welcome 后状态变 ONLINE ──────────────────────────────

    @Test
    fun `client becomes online after device welcome`() {
        val helloSent = CountDownLatch(1)
        val becameOnline = CountDownLatch(1)
        val socketClosed = enqueueTentacleSocket { webSocket, text ->
            // 收到 device/hello 后回 device/welcome
            val hello = JSONObject(text)
            if (hello.optString("type") == "device/hello") {
                helloSent.countDown()
                webSocket.send(
                    JSONObject()
                        .put("type", "device/welcome")
                        .put("session_id", "sess-123")
                        .put("server_version", "0.1.0")
                        .toString(),
                )
            }
        }

        val client = newClient()
        client.onStateChanged = { state ->
            if (state == TentacleState.ONLINE) becameOnline.countDown()
        }
        // 模拟 TentacleManager 的 onWsOpen: 收到 onOpen 后发 hello
        client.onWsOpen = {
            val hello = JsonObject().apply {
                addProperty("type", "device/hello")
                addProperty("device_id", "test-device")
                add("capabilities", com.google.gson.JsonArray())
                addProperty("auth_token", "tok-abc")
            }
            client.send(hello)
        }
        client.connect(server.url("/ws").toString().replace("http://", "ws://"), "tok-abc")

        assertTrue("device/hello should be sent on WS open", helloSent.await(2, TimeUnit.SECONDS))
        assertTrue("state should become ONLINE after device/welcome", becameOnline.await(2, TimeUnit.SECONDS))
        assertEquals(TentacleState.ONLINE, client.currentState())

        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `client stays CONNECTING before device welcome`() {
        val helloSeen = CountDownLatch(1)
        enqueueTentacleSocket { _, text ->
            val hello = JSONObject(text)
            if (hello.optString("type") == "device/hello") {
                helloSeen.countDown()
                // 故意不回 welcome, 客户端应停在 CONNECTING
            }
        }

        val client = newClient()
        client.onWsOpen = {
            val hello = JsonObject().apply {
                addProperty("type", "device/hello")
                addProperty("device_id", "test-device")
            }
            client.send(hello)
        }
        client.connect(server.url("/ws").toString().replace("http://", "ws://"), "")

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(TentacleState.CONNECTING, client.currentState())
        client.disconnect()
    }

    @Test
    fun `tool execute before welcome is rejected`() {
        val helloSeen = CountDownLatch(1)
        enqueueTentacleSocket { webSocket, text ->
            val hello = JSONObject(text)
            if (hello.optString("type") == "device/hello") {
                helloSeen.countDown()
                // welcome 之前抢发 tool/execute —— 应被拒绝(状态机 ONLINE 才接受)
                webSocket.send(
                    JSONObject()
                        .put("type", "tool/execute")
                        .put("call_id", "evil-1")
                        .put("tool_name", "run_code")
                        .put("params", JSONObject())
                        .toString(),
                )
            }
        }

        val client = newClient()
        val dispatched = CountDownLatch(1)
        client.setToolCallHandler { _, _ ->
            dispatched.countDown()
            ToolResult.success("ok")
        }
        client.onWsOpen = {
            val hello = JsonObject().apply {
                addProperty("type", "device/hello")
                addProperty("device_id", "test-device")
            }
            client.send(hello)
        }
        client.connect(server.url("/ws").toString().replace("http://", "ws://"), "")

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        // 状态未 ONLINE → tool handler 不应被调用
        assertEquals(false, dispatched.await(500, TimeUnit.MILLISECONDS))
        client.disconnect()
    }

    // ── 3. 心跳 30s 间隔 ──────────────────────────────────────────────────

    @Test
    fun `heartbeat interval is 30 seconds`() {
        assertEquals(30_000L, DeviceRegistration.HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun `heartbeat ack timeout is 5 seconds`() {
        assertEquals(5_000L, DeviceRegistration.ACK_TIMEOUT_MS)
    }

    @Test
    fun `max missed acks before reconnect is 3`() {
        assertEquals(3, DeviceRegistration.MAX_MISSED_ACKS)
    }

    // ── 4. 重连指数退避 ──────────────────────────────────────────────────

    @Test
    fun `backoff starts at 1s and grows exponentially`() {
        val client = newClient()
        val b1 = client.computeBackoff(1)
        val b2 = client.computeBackoff(2)
        val b3 = client.computeBackoff(3)

        // 1s 起步(允许 +0..500ms jitter, 见 computeBackoff 实现)
        assertTrue("attempt 1 backoff should be ~1s, got $b1", b1 in 1_000L..1_500L)
        assertTrue("attempt 2 backoff should be ~2s, got $b2", b2 in 2_000L..2_500L)
        assertTrue("attempt 3 backoff should be ~4s, got $b3", b3 in 4_000L..4_500L)
    }

    @Test
    fun `backoff is capped at 30s`() {
        val client = newClient()
        // 大 attempt 值应被 30s 封顶 + 1s jitter 余量
        val big = client.computeBackoff(20)
        assertTrue("backoff should be capped near 30s, got $big", big <= OctopusMobileClient.MAX_BACKOFF_MS + OctopusMobileClient.INITIAL_BACKOFF_MS)
        assertTrue(big >= OctopusMobileClient.MAX_BACKOFF_MS)
    }

    @Test
    fun `max reconnect attempts is 3`() {
        assertEquals(3, OctopusMobileClient.MAX_RECONNECT_ATTEMPTS)
        assertEquals(1_000L, OctopusMobileClient.INITIAL_BACKOFF_MS)
        assertEquals(30_000L, OctopusMobileClient.MAX_BACKOFF_MS)
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private fun newClient(): OctopusMobileClient =
        OctopusMobileClient().also { clients.add(it) }

    private fun enqueueTentacleSocket(
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
