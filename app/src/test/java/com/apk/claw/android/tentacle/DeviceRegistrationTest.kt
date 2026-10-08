package com.apk.claw.android.tentacle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceRegistrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, DeviceRegistration.DEVICE_ID_FILE).delete()
    }

    @Test
    fun `device_id is non-empty and stable across instances`() {
        val first = DeviceRegistration(context).deviceId
        val second = DeviceRegistration(context).deviceId
        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
    }

    @Test
    fun `device_id is persisted to filesDir`() {
        val id = DeviceRegistration(context).deviceId
        val persisted = File(context.filesDir, DeviceRegistration.DEVICE_ID_FILE).readText().trim()
        assertEquals(id, persisted)
    }

    @Test
    fun `existing persisted device_id is reused`() {
        File(context.filesDir, DeviceRegistration.DEVICE_ID_FILE).writeText("persisted-id\n")
        assertEquals("persisted-id", DeviceRegistration(context).deviceId)
    }
}
