package com.apk.claw.android.ui.compose.screen.detail

import android.content.ContentValues
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.ui.compose.MainActivity
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.screen.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual bottom sheet while its execution snapshot changes. No model/network request. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class LiveToolsDetailTest {
    @Test
    fun runningSheetReceivesLatestOutputWithoutReopening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val calls = mutableStateListOf(ChatMessage.ToolCall("⚙", "执行命令",
            "{\"command\":\"printf '第一行\\n第二行'\",\"cwd\":\"/workspace\"}", null,
            status = ChatMessage.ToolCallStatus.RUNNING, callId = "fixture"))
        val pane = ToolsDetailPane(callsProvider = { calls.toList() })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { DetailDrawer(pane, onClose = {}) } }
            }
            assertTextAppears(resource(R.string.tool_detail_waiting))
            assertTextAppears("/workspace")
            clickText(resource(R.string.tool_detail_copy))
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                assertEquals("printf '第一行\n第二行'", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            }
            saveScreenshot("tools-running.png")
            scenario.onActivity {
                calls[0] = calls[0].copy(result = "第一行\n第二行\n最后一行😀",
                    status = ChatMessage.ToolCallStatus.SUCCESS, durationMs = 512)
            }
            assertTextAppears("最后一行😀")
            assertTextAppears("512ms")
            assertTextAppears(instrumentation.targetContext.resources.getQuantityString(
                R.plurals.tool_detail_success_count, 1, 1))
            saveScreenshot("tools-complete.png")
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun pausedLogStaysPausedAcrossSnapshotsAndCanResume() {
        val calls = mutableStateListOf(ChatMessage.ToolCall("⚙", "执行命令", "{}",
            (1..200).joinToString("\n") { "line $it" },
            status = ChatMessage.ToolCallStatus.RUNNING, callId = "long-log"))
        val pane = ToolsDetailPane(callsProvider = { calls.toList() })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { DetailDrawer(pane, onClose = {}) } }
            }
            assertTextAppears(resource(R.string.tool_detail_pause))
            dragLogBack()
            assertTextAppears(resource(R.string.tool_detail_latest))
            scenario.onActivity { calls[0] = calls[0].copy(result = calls[0].result + "\nNEW OUTPUT") }
            assertTextAppears(resource(R.string.tool_detail_latest))
            saveScreenshot("tools-paused.png")
            clickText(resource(R.string.tool_detail_latest))
            assertTextAppears(resource(R.string.tool_detail_pause))
            clickText(resource(R.string.tool_detail_pause))
            assertTextAppears(resource(R.string.tool_detail_latest))
            clickText(resource(R.string.tool_detail_latest))
            assertTextAppears(resource(R.string.tool_detail_pause))
            saveScreenshot("tools-following.png")
        }
    }

    private fun resource(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun dragLogBack() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = requireNotNull(findScrollableOutput(automation.rootInActiveWindow))
        val bounds = Rect().also(output::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        repeat(11) { step ->
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                10 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val y = bounds.top + bounds.height() * (0.2f + step * 0.06f)
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(30)
        }
    }

    private fun findScrollableOutput(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val child = (0 until node.childCount).firstNotNullOfOrNull { findScrollableOutput(node.getChild(it)) }
        return child ?: node.takeIf { it.isScrollable && containsText(it, "line 200") }
    }

    private fun clickText(expected: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val text = requireNotNull(findText(automation.rootInActiveWindow, expected))
        var target = text
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Clickable control: $expected", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findText(node: AccessibilityNodeInfo?, expected: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.contains(expected) == true) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findText(node.getChild(it), expected) }
    }

    private fun assertTextAppears(expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            instrumentation.waitForIdleSync()
            if (containsText(instrumentation.uiAutomation.rootInActiveWindow, expected)) return
            Thread.sleep(100)
        }
        assertTrue("Expected visible text: $expected", false)
    }

    private fun containsText(node: AccessibilityNodeInfo?, expected: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(expected) == true) return true
        return (0 until node.childCount).any { containsText(node.getChild(it), expected) }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EchoMobileTests")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
