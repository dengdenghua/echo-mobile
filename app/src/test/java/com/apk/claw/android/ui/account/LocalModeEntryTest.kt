package com.apk.claw.android.ui.account

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.R
import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.account.LoginResult
import com.apk.claw.android.ui.compose.MainActivity
import com.apk.claw.android.ui.splash.SplashActivity
import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sign-in gate's 「不登录使用」 and the splash routing that makes the choice stick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class, sdk = [34])
class LocalModeEntryTest {

    @Before
    fun setUp() {
        KVUtils.resetForTest()
        AccountStore.clear()
        AccountStore.localMode = false
    }

    @After
    fun tearDown() {
        AccountStore.clear()
        AccountStore.localMode = false
        KVUtils.resetForTest()
    }

    private fun nextStarted(): Intent {
        val app = ApplicationProvider.getApplicationContext<TestClawApplication>()
        val next = shadowOf(app).nextStartedActivity
        assertNotNull("expected an activity to be started", next)
        return next
    }

    @Test
    fun `gate offers use without an account and remembers the choice`() {
        val gate = LoginActivity.gateIntent(ApplicationProvider.getApplicationContext())
        val activity = Robolectric.buildActivity(LoginActivity::class.java, gate).setup().get()

        val useLocal = activity.findViewById<TextView>(R.id.btnUseLocal)
        assertEquals(View.VISIBLE, useLocal.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tvLocalHint).visibility)
        assertEquals(activity.getString(R.string.account_local_mode_use), useLocal.text.toString())

        useLocal.performClick()

        assertTrue(AccountStore.localMode)
        val next = nextStarted()
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `sign-in opened from settings or a prompt does not offer local mode`() {
        val context = ApplicationProvider.getApplicationContext<TestClawApplication>()
        val fromPrompt = Robolectric.buildActivity(LoginActivity::class.java, LoginActivity.signInIntent(context))
            .setup().get()
        assertEquals(View.GONE, fromPrompt.findViewById<View>(R.id.btnUseLocal).visibility)

        val plain = Robolectric.buildActivity(LoginActivity::class.java, Intent(context, LoginActivity::class.java))
            .setup().get()
        assertEquals(View.GONE, plain.findViewById<View>(R.id.btnUseLocal).visibility)
        assertFalse(AccountStore.localMode)
    }

    @Test
    fun `splash sends a first-time user to the gate`() {
        Robolectric.buildActivity(SplashActivity::class.java).setup()

        val next = nextStarted()
        assertEquals(LoginActivity::class.java.name, next.component?.className)
        assertTrue(next.getBooleanExtra(LoginActivity.EXTRA_GATE, false))
    }

    @Test
    fun `splash skips the gate after the user chose local mode`() {
        AccountStore.localMode = true

        Robolectric.buildActivity(SplashActivity::class.java).setup()

        assertEquals(MainActivity::class.java.name, nextStarted().component?.className)
    }

    @Test
    fun `splash skips the gate for a signed-in user`() {
        AccountStore.saveLogin(LoginResult(token = "t", userId = "u"))

        Robolectric.buildActivity(SplashActivity::class.java).setup()

        assertEquals(MainActivity::class.java.name, nextStarted().component?.className)
    }
}
