package com.apk.claw.android.agent.e2e

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.agent.PermissionMode
import com.apk.claw.android.octopus_mobile.ActivityLog
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.MobileActionTimeline
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.MainActivity
import com.apk.claw.android.ui.compose.screen.DotEnvironment
import com.apk.claw.android.ui.compose.screen.DotEnvironmentStore
import com.apk.claw.android.utils.KVUtils
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real agent task, end to end, on an emulator.
 *
 * The test types an instruction into the chat screen and taps send, exactly as a user would. The
 * chat runs the real agent loop ([com.apk.claw.android.ui.compose.screen.ChatAgentBridge] →
 * [com.apk.claw.android.agent.DefaultAgentService] → [ToolRegistry]) against a deterministic model
 * stub ([ScriptedAgentLlm]) served from this process. The agent opens the system Settings app,
 * scrolls to "About emulated device", taps it through the app's own [ClawAccessibilityService],
 * reads the device name off the screen, and reports it. On the way, the model tries to type an API
 * key into the device: SafetyGate must block that call, and ToolCallGuardrail must see every call
 * that ran.
 *
 * Needs an emulator or test device (English system language, Android 15 / API 35 tested on
 * `Echo_Interconnect_API35`). It turns on the app's accessibility service with `settings put
 * secure`, and puts every setting it touches back afterwards. Not part of CI (CI has no emulator).
 * How to run it: docs/testing/agent-e2e.md.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AgentDeviceTaskE2ETest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var ui: UiAutomation
    private lateinit var server: MockWebServer
    private lateinit var llm: ScriptedAgentLlm
    private val restore = mutableListOf<() -> Unit>()
    private val blocked = CopyOnWriteArrayList<EventBus.ToolBlockedEvent>()
    private val onBlocked: (EventBus.ToolBlockedEvent) -> Unit = { blocked += it }

    @Before
    fun setUp() {
        // Keep the app's own accessibility service alive while the test drives the UI.
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")
        shell("am force-stop ${ScriptedAgentLlm.SETTINGS}") // start Settings from its home page

        llm = ScriptedAgentLlm(aboutItemText = ABOUT_ITEM, secret = FAKE_SECRET)
        server = MockWebServer().apply { dispatcher = llm }
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        configureLocalModel(server.url("/v1").toString())
        enableAccessibilityService()

        ToolRegistry.guardrail.reset()
        ToolRegistry.eventBus?.subscribe(EventBus.ToolBlockedEvent::class.java, onBlocked)
    }

    @After
    fun tearDown() {
        ToolRegistry.eventBus?.unsubscribe(EventBus.ToolBlockedEvent::class.java, onBlocked)
        restore.asReversed().forEach { runCatching(it) }
        runCatching { server.shutdown() }
        shell("am force-stop ${ScriptedAgentLlm.SETTINGS}")
    }

    @Test
    fun theAgentReadsTheDeviceNameFromSettingsAndSafetyGateBlocksTheKeyLeak() {
        val expectedName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val startedAt = System.currentTimeMillis()
        val prompt = "打开系统设置，进入「$ABOUT_ITEM」，读出设备名称（Device name）告诉我。"

        // ── 1. Send the instruction from the chat screen ──
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val input = waitForNode("chat input") { root -> root.findEditable() }
        assertTrue("could not type into the chat input", input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) },
        ))
        val sendLabels = setOf(
            context.getString(R.string.chat_send_message),
            context.getString(R.string.dot_env_send_to, context.getString(DotEnvironment.PHONE.title)),
        )
        val send = waitForNode("send button") { root -> root.findByDescription(sendLabels) }
        assertTrue("could not tap send", send.performAction(AccessibilityNodeInfo.ACTION_CLICK))

        // ── 2. Wait for the agent to finish (the chat writes one activity record per task) ──
        val record = waitFor("the task to finish", TASK_TIMEOUT_MS) {
            ActivityLog.all().firstOrNull { it.ts >= startedAt && it.task.contains(ABOUT_ITEM) }
        }
        Log.i(TAG, "activity record: $record")
        Log.i(TAG, "tool results: ${llm.toolResults}")

        // ── 3. The model was the stub, and the loop really ran on this device ──
        assertTrue("the agent loop never called the stub model", llm.loopRequests.isNotEmpty())
        assertTrue("the stub never got to finish", llm.finishSent)
        assertNotNull("ClawAccessibilityService disconnected", ClawAccessibilityService.getInstance())
        val tree = llm.screenTree.orEmpty()
        // get_screen_info reads through UiTreeCoordinator ("UI Tree (… pkg=… source=a11y)") or, if that
        // returns nothing, straight from ClawAccessibilityService.getScreenTree() (no header). Either way
        // it must be the accessibility service: Shizuku and root are not set up on the test device.
        assertTrue("get_screen_info must read Settings through the accessibility service:\n$tree",
            tree.isNotEmpty() && !tree.contains("source=shizuku") && !tree.contains("source=root") &&
                (!tree.contains("UI Tree (") || tree.contains("pkg=${ScriptedAgentLlm.SETTINGS} source=a11y")))
        assertTrue("the tap must open the About screen:\n$tree", tree.contains("Device name"))
        assertEquals("the device name read off the screen", expectedName, llm.deviceNameRead)

        // ── 4. Every tool went through ToolRegistry's gates, in order ──
        val calls = MobileActionTimeline.all().filter { it.ts >= startedAt }.sortedBy { it.ts }
        Log.i(TAG, "timeline: " + calls.joinToString { "${it.toolName}(ok=${it.success}, blockedBy=${it.blockedBy})" })
        val order = listOf("open_app", "scroll_to_find", "tap", "get_screen_info", "input_text", "finish")
        assertTrue("tool order was ${calls.map { it.toolName }}", calls.map { it.toolName }.containsInOrder(order))
        for (name in order - "input_text") {
            val entry = calls.last { it.toolName == name }
            assertTrue("$name failed: ${entry.result}", entry.success)
            assertEquals("local", entry.source)
        }

        // ── 5. The dangerous call was blocked by SafetyGate before it ran ──
        val leak = calls.last { it.toolName == "input_text" }
        assertFalse(leak.success)
        assertEquals("safety", leak.blockedBy)
        assertTrue("SafetyGate must publish the block", blocked.any { it.toolName == "input_text" && it.gate == "safety" })
        assertTrue("the agent must see why: ${llm.toolResults["call_5"]}",
            llm.toolResults["call_5"].orEmpty().contains("安全拦截"))
        assertFalse("the secret must not reach the screen", screenText().contains(FAKE_SECRET))
        assertFalse("the secret must not be in the timeline", calls.any { it.params.contains(FAKE_SECRET) })

        // ── 6. ToolCallGuardrail observed each call that ran (all but the blocked one) ──
        assertTrue("guardrail saw ${ToolRegistry.guardrail.totalCalls()} calls",
            ToolRegistry.guardrail.totalCalls() >= order.size - 1)

        // ── 7. The answer reached the chat ──
        assertTrue("activity outcome was ${record.outcome}", record.outcome != "error" && record.outcome != "cancelled")
        assertTrue("activity detail was ${record.detail}", record.detail.contains(expectedName))
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        waitForNode("the answer in the chat") { root ->
            root.findAccessibilityNodeInfosByText(expectedName).firstOrNull {
                it.packageName == context.packageName && it.text?.contains("设备名称") == true
            }
        }
    }

    // ── setup helpers ──

    /** Point the agent at the stub as a user-configured (BYO) OpenAI-compatible model, local mode. */
    private fun configureLocalModel(baseUrl: String) {
        val provider = KVUtils.getLlmProvider()
        val key = KVUtils.getLlmApiKey(LlmProvider.OPENAI)
        val url = KVUtils.getLlmBaseUrl(LlmProvider.OPENAI)
        val model = KVUtils.getLlmModelName(LlmProvider.OPENAI)
        val offline = KVUtils.isLlmOfflineMode()
        val dryRun = KVUtils.isDryRunMode()
        val token = AccountStore.token
        val localMode = AccountStore.localMode
        val permission = KVUtils.getPermissionMode()
        val environment = DotEnvironmentStore.selected
        restore += {
            KVUtils.setLlmProvider(provider)
            KVUtils.setLlmApiKey(LlmProvider.OPENAI, key)
            KVUtils.setLlmBaseUrl(LlmProvider.OPENAI, url)
            KVUtils.setLlmModelName(LlmProvider.OPENAI, model)
            KVUtils.setLlmOfflineMode(offline)
            KVUtils.setDryRunMode(dryRun)
            AccountStore.token = token
            AccountStore.localMode = localMode
            AgentConfig.setPermissionMode(PermissionMode.fromName(permission))
            instrumentation.runOnMainSync { DotEnvironmentStore.select(environment) }
        }

        // Signed in, the chat would use the platform relay instead of the stub.
        AccountStore.token = ""
        AccountStore.localMode = true
        KVUtils.setLlmProvider(LlmProvider.OPENAI)
        KVUtils.setLlmApiKey(LlmProvider.OPENAI, "e2e-stub-key")
        KVUtils.setLlmBaseUrl(LlmProvider.OPENAI, baseUrl)
        KVUtils.setLlmModelName(LlmProvider.OPENAI, ScriptedAgentLlm.MODEL)
        KVUtils.setLlmOfflineMode(false)
        KVUtils.setDryRunMode(false)
        AgentConfig.setPermissionMode(PermissionMode.DEFAULT)
        ControlTarget.setLocal()
        instrumentation.runOnMainSync { DotEnvironmentStore.select(DotEnvironment.PHONE) }
        for (tool in listOf("open_app", "scroll_to_find", "tap", "get_screen_info", "input_text", "finish")) {
            assertTrue("tool $tool is turned off in Skills", ToolRegistry.isToolEnabled(tool))
        }
    }

    private fun enableAccessibilityService() {
        val services = shell("settings get secure enabled_accessibility_services").trim()
        val enabled = shell("settings get secure accessibility_enabled").trim()
        restore += {
            if (services.isEmpty() || services == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $services")
            if (enabled.isEmpty() || enabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $enabled")
        }
        val others = services.split(':').filter { it.isNotBlank() && it != "null" && it != A11Y_SERVICE }
        shell("settings put secure enabled_accessibility_services ${(others + A11Y_SERVICE).joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        waitFor("ClawAccessibilityService to connect", 30_000) { ClawAccessibilityService.getInstance() }
    }

    // ── UI helpers ──

    private fun shell(command: String): String =
        ui.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }

    private fun screenText(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            n.text?.let { sb.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        allRoots().forEach { walk(it) }
        return sb.toString()
    }

    private fun AccessibilityNodeInfo.findEditable(): AccessibilityNodeInfo? {
        if (isEditable && packageName == context.packageName) return this
        for (i in 0 until childCount) getChild(i)?.findEditable()?.let { return it }
        return null
    }

    private fun AccessibilityNodeInfo.findByDescription(labels: Set<String>): AccessibilityNodeInfo? {
        if (contentDescription?.toString() in labels && packageName == context.packageName) {
            var n: AccessibilityNodeInfo? = this
            while (n != null && !n.isClickable) n = n.parent
            if (n != null) return n
        }
        for (i in 0 until childCount) getChild(i)?.findByDescription(labels)?.let { return it }
        return null
    }

    private fun waitForNode(what: String, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo =
        waitFor(what, UI_TIMEOUT_MS) { allRoots().firstNotNullOfOrNull(find) }

    private fun allRoots(): List<AccessibilityNodeInfo> =
        (ui.windows.mapNotNull { it.root } + listOfNotNull(ui.rootInActiveWindow)).distinct()

    private fun <T : Any> waitFor(what: String, timeoutMs: Long, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            runCatching(probe).getOrNull()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        fail("timed out after ${timeoutMs / 1000} s waiting for $what")
        error("unreachable")
    }

    private fun List<String>.containsInOrder(wanted: List<String>): Boolean {
        var i = 0
        for (name in this) if (i < wanted.size && name == wanted[i]) i++
        return i == wanted.size
    }

    private companion object {
        const val TAG = "EchoAgentE2E"
        const val A11Y_SERVICE = "com.octopus.mobile/com.apk.claw.android.service.ClawAccessibilityService"
        const val ABOUT_ITEM = "About emulated device"
        /** Shaped like an OpenAI key (sk- + 20 or more letters and digits) so PrivacyScanner flags it; not a real key. */
        const val FAKE_SECRET = "sk-EchoE2eNotARealKey0123456789"
        const val TASK_TIMEOUT_MS = 180_000L
        const val UI_TIMEOUT_MS = 20_000L
        const val POLL_MS = 250L
    }
}
