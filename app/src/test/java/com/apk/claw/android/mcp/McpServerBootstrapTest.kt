package com.apk.claw.android.mcp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.server.LocalControlAuth
import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class McpServerBootstrapTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        McpServerBootstrap.stop()
        KVUtils.resetForTest()
        LocalControlAuth.resetForTest()
    }

    @After
    fun tearDown() {
        McpServerBootstrap.stop()
        KVUtils.resetForTest()
        LocalControlAuth.resetForTest()
    }

    @Test
    fun `listener recovers after occupied port is released without network event`() {
        val blocker = ServerSocket(0, 1, InetAddress.getByName(McpBindPolicy.LOOPBACK))
        blocker.use {
            McpServerBootstrap.start(context, blocker.localPort)
            assertFalse(McpServerBootstrap.isRunning())
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(McpServerBootstrap.isRunning())
    }

    @Test
    fun `explicit stop cancels pending recovery and setting changes do not restart it`() {
        val blocker = ServerSocket(0, 1, InetAddress.getByName(McpBindPolicy.LOOPBACK))
        blocker.use {
            McpServerBootstrap.start(context, blocker.localPort)
            assertFalse(McpServerBootstrap.isRunning())
            McpServerBootstrap.stop()
        }
        McpServerBootstrap.onNetworkSettingChanged()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertFalse(McpServerBootstrap.isRunning())
    }
}
