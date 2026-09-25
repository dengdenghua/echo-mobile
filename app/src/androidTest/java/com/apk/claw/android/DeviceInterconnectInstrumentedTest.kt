package com.apk.claw.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.OctopusMobileClient
import com.apk.claw.android.octopus_mobile.ToolCallDispatcher
import com.apk.claw.android.utils.KVUtils
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

/** Opt-in live acceptance. Uses the actual APK tools and gateway, without mock responses. */
@RunWith(AndroidJUnit4::class)
class DeviceInterconnectInstrumentedTest {
    @Test
    fun actualAndroidExchangesToolsWithWindowsAndLinux() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configName = InstrumentationRegistry.getArguments().getString("echoLabConfig")
        assumeTrue("Live device lab was not requested", configName == "echo-device-lab.json")
        val configFile = File(instrumentation.targetContext.filesDir, configName!!)
        assumeTrue("Live device lab credentials are absent", configFile.isFile)
        val config = JsonParser.parseString(configFile.readText()).asJsonObject
        KVUtils.setInsecureOctopusRuntimeAllowed(false)
        val client = OctopusMobileClient(
            config.get("url").asString,
            config.get("deviceId").asString,
            config.get("token").asString,
        ) { listOf("android.get_installed_apps") }
        val dispatcher = ToolCallDispatcher(client)
        try {
            dispatcher.start()
            client.connect()
            withTimeout(20_000) {
                while (client.currentState() != ConnectionState.ONLINE) delay(100)
            }
            for ((device, platform) in listOf("lab-windows" to "windows", "lab-linux" to "linux")) {
                val info = client.executePeerTool(device, "device.info", emptyMap())
                assertTrue(info.get("success").asBoolean)
                assertEquals(platform, info.getAsJsonObject("data").get("platform").asString)
                val text = "安卓运行时 → $platform\n第二行\n"
                val write = client.executePeerTool(device, "workspace.write_text", mapOf(
                    "path" to "from-android.txt", "text" to text,
                ))
                assertTrue(write.toString(), write.get("success").asBoolean)
                val read = client.executePeerTool(device, "workspace.read_text", mapOf(
                    "path" to "from-android.txt",
                ))
                assertTrue(read.get("success").asBoolean)
                assertEquals(text, read.getAsJsonObject("data").get("text").asString)
            }
            // The desktop peers set this marker only after receiving and checking
            // the real Android PackageManager result through ToolCallDispatcher.
            withTimeout(90_000) {
                for (device in listOf("lab-windows", "lab-linux")) {
                    while (true) {
                        val result = client.executePeerTool(device, "workspace.read_text", mapOf(
                            "path" to "phone-result-checked.txt",
                        ))
                        if (result.get("success").asBoolean) {
                            assertEquals("verified", result.getAsJsonObject("data").get("text").asString)
                            break
                        }
                        delay(250)
                    }
                }
            }
        } finally {
            dispatcher.stop()
            client.disconnect()
            configFile.delete()
        }
    }
}
