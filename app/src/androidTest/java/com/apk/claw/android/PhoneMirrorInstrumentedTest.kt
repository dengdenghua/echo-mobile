package com.apk.claw.android

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.OctopusMobileClient
import com.apk.claw.android.octopus_mobile.ToolCallDispatcher
import com.apk.claw.android.transfer.TransferStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneMirrorInstrumentedTest {
    @Test fun realPhoneMirrorSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configName = InstrumentationRegistry.getArguments().getString("echoMirrorConfig")
        assumeTrue("Opt-in live mirror lab only", configName == "echo-mirror-lab.json")
        val context = instrumentation.targetContext
        val configFile = File(context.filesDir, configName!!)
        val config = JsonParser.parseString(configFile.readText()).asJsonObject
        val activity = instrumentation.startActivitySync(
            Intent(context, MirrorAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MirrorAcceptanceActivity
        val store = TransferStore(File(context.filesDir, "exchange"))
        val marker = store.file("mirror-finished.txt")
        marker.delete()
        store.file("from-desktop.bin").delete()
        val phoneSource = store.file("from-phone.bin")
        phoneSource.writeBytes(ByteArray(100_013) { ((it * 17) % 251).toByte() })
        delay(1500)
        val fixture = mutableMapOf<String, Any>()
        instrumentation.runOnMainSync {
            fun point(view: View): List<Double> {
                val at = IntArray(2); view.getLocationOnScreen(at)
                val metrics = context.resources.displayMetrics
                return listOf((at[0] + view.width / 2.0) / metrics.widthPixels, (at[1] + view.height / 2.0) / metrics.heightPixels)
            }
            fixture["tap"] = point(activity.tap)
            fixture["entry"] = point(activity.entry)
            fixture["width"] = com.blankj.utilcode.util.ScreenUtils.getScreenWidth()
            fixture["height"] = com.blankj.utilcode.util.ScreenUtils.getScreenHeight()
        }
        store.file("fixture.json").writeText(Gson().toJson(fixture))
        val client = OctopusMobileClient(config.get("url").asString, config.get("deviceId").asString, config.get("token").asString) {
            listOf("android.mirror_frame", "android.mirror_control", "android.exchange_files")
        }
        val dispatcher = ToolCallDispatcher(client)
        try {
            dispatcher.start(); client.connect()
            withTimeout(20_000) { while (client.currentState() != ConnectionState.ONLINE) delay(100) }
            withTimeout(600_000) { while (!marker.isFile) delay(200) }
            instrumentation.runOnMainSync {
                assertTrue("Desktop tap must activate the native button", activity.taps > 0)
                assertEquals("桌面输入 Echo 你好", activity.entry.text.toString())
                assertTrue("Desktop swipe must scroll native content", activity.scroll.scrollY > 0)
            }
            val actual = store.file("from-desktop.bin").readBytes()
            assertTrue(actual.contentEquals(ByteArray(180_017) { ((it * 13) % 251).toByte() }))
        } finally {
            dispatcher.stop(); client.disconnect(); configFile.delete()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
