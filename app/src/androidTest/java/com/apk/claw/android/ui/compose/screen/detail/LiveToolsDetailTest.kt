package com.apk.claw.android.ui.compose.screen.detail

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.ui.compose.MainActivity
import com.apk.claw.android.ui.compose.screen.ChatMessage
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
            assertTextAppears("执行中，等待输出")
            saveScreenshot("tools-running.png")
            scenario.onActivity {
                calls[0] = calls[0].copy(result = "第一行\n第二行\n最后一行😀",
                    status = ChatMessage.ToolCallStatus.SUCCESS, durationMs = 512)
            }
            assertTextAppears("最后一行😀")
            assertTextAppears("512ms")
            saveScreenshot("tools-complete.png")
        }
        instrumentation.waitForIdleSync()
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
