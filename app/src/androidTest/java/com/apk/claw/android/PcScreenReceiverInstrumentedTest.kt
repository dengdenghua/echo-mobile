package com.apk.claw.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.octopus_mobile.OctopusMobileClient
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.google.gson.JsonParser
import java.io.File
import com.apk.claw.android.ui.featurescreens.PcRemoteActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PcScreenReceiverInstrumentedTest {
    @Test fun jpegReceiverRendersAndUnsubscribesWhenClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configName = InstrumentationRegistry.getArguments().getString("echoReceiverConfig")
        val configFile = if (configName == "echo-receiver-lab.json") File(context.filesDir, configName) else null
        val config = configFile?.let { JsonParser.parseString(it.readText()).asJsonObject }
        val client = OctopusMobileClient(
            config?.get("url")?.asString ?: "ws://127.0.0.1:1",
            config?.get("deviceId")?.asString ?: "receiver-test",
            config?.get("token")?.asString ?: "unused",
        ) { emptyList() }
        val field = AppViewModel::class.java.getDeclaredField("octopusClient").apply { isAccessible = true }
        val previous = field.get(appViewModel)
        field.set(appViewModel, client)
        var activity: PcRemoteActivity? = null
        try {
            if (config != null) {
                client.connect()
                val connectDeadline = System.currentTimeMillis() + 20_000
                while (client.currentState() != ConnectionState.ONLINE && System.currentTimeMillis() < connectDeadline) Thread.sleep(100)
                assertTrue("Live receiver must connect", client.currentState() == ConnectionState.ONLINE)
            }
            activity = instrumentation.startActivitySync(Intent(context, PcRemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PcRemoteActivity
            val deadline = System.currentTimeMillis() + if (config == null) 10_000 else 60_000
            while (client.onPcFrame == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Receiver must register its frame callback", client.onPcFrame != null)
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            bitmap.recycle()
            val source = "pc-host".toByteArray()
            val frame = byteArrayOf(0, source.size.toByte(), 2, 1) + source + jpeg
            if (config == null) client.onPcFrame!!.invoke(frame)
            else File(context.filesDir, "receiver-green.jpg").writeBytes(jpeg)
            var rendered = false
            while (!rendered && System.currentTimeMillis() < deadline) {
                Thread.sleep(150)
                val screen = instrumentation.uiAutomation.takeScreenshot() ?: continue
                val pixel = screen.getPixel(screen.width / 2, screen.height / 2)
                rendered = Color.green(pixel) > 200 && Color.red(pixel) < 40 && Color.blue(pixel) < 40
                screen.recycle()
            }
            assertTrue("JPEG receiver must display the supplied green pixels", rendered)
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            val closeDeadline = System.currentTimeMillis() + 5_000
            while (client.onPcFrame != null && System.currentTimeMillis() < closeDeadline) Thread.sleep(100)
            assertNull("Closing the receiver must clear its callback", client.onPcFrame)
            val subscribed = OctopusMobileClient::class.java.getDeclaredField("pcScreenSubscribed").apply { isAccessible = true }
            assertFalse("Closing must unsubscribe", subscribed.getBoolean(client))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            field.set(appViewModel, previous)
            client.disconnect()
            configFile?.delete()
        }
    }
}
