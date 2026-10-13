package com.apk.claw.android.account

import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「不登录使用」 (local mode): who lands where, what needs an account, and that the choice
 * survives restarts until the user signs in. Plain JVM: KVUtils falls back to memory.
 */
class AccountGateTest {

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

    @Test
    fun `signed in wins over local mode`() {
        assertEquals(AccessMode.SIGNED_IN, AccountGate.accessMode(loggedIn = true, localMode = true))
        assertEquals(AccessMode.SIGNED_IN, AccountGate.accessMode(loggedIn = true, localMode = false))
        assertEquals(AccessMode.LOCAL, AccountGate.accessMode(loggedIn = false, localMode = true))
        assertEquals(AccessMode.SIGNED_OUT, AccountGate.accessMode(loggedIn = false, localMode = false))
    }

    @Test
    fun `only a signed-out user without local mode sees the sign-in gate`() {
        assertEquals(Landing.LOGIN, AccountGate.landing(AccessMode.SIGNED_OUT, desktopDefault = false))
        assertEquals(Landing.LOGIN, AccountGate.landing(AccessMode.SIGNED_OUT, desktopDefault = true))
        assertEquals(Landing.MAIN, AccountGate.landing(AccessMode.LOCAL, desktopDefault = false))
        assertEquals(Landing.DESKTOP, AccountGate.landing(AccessMode.LOCAL, desktopDefault = true))
        assertEquals(Landing.MAIN, AccountGate.landing(AccessMode.SIGNED_IN, desktopDefault = false))
        assertEquals(Landing.DESKTOP, AccountGate.landing(AccessMode.SIGNED_IN, desktopDefault = true))
    }

    @Test
    fun `every account feature needs a signed-in account`() {
        for (feature in AccountFeature.entries) {
            assertFalse("$feature must say 登录后可用 in local mode", AccountGate.isAvailable(feature, loggedIn = false))
            assertTrue("$feature must work when signed in", AccountGate.isAvailable(feature, loggedIn = true))
        }
    }

    @Test
    fun `local mode choice persists and survives a fresh read`() {
        assertEquals(AccessMode.SIGNED_OUT, AccountGate.accessMode())

        AccountGate.chooseLocalMode()

        // A new process reads the same store: the flag, not in-memory state, decides.
        assertTrue(KVUtils.getBoolean("ACCOUNT_LOCAL_MODE", false))
        assertTrue(AccountStore.localMode)
        assertEquals(AccessMode.LOCAL, AccountGate.accessMode())
        assertTrue(AccountGate.isLocalMode)
        assertEquals(Landing.MAIN, AccountGate.landing(desktopDefault = false))
    }

    @Test
    fun `signing in later leaves local data alone and ends local mode`() {
        AccountGate.chooseLocalMode()
        KVUtils.putString("LOCAL_DATA_PROBE", "kept")

        AccountStore.saveLogin(LoginResult(token = "t", userId = "u", mobile = "", isNewUser = false))

        assertEquals(AccessMode.SIGNED_IN, AccountGate.accessMode())
        assertFalse(AccountStore.localMode)
        assertEquals("kept", KVUtils.getString("LOCAL_DATA_PROBE", ""))
        assertTrue(AccountGate.isAvailable(AccountFeature.COMMUNITY))
    }

    @Test
    fun `signing out returns to the gate instead of silently entering local mode`() {
        AccountGate.chooseLocalMode()
        AccountStore.saveLogin(LoginResult(token = "t", userId = "u", mobile = "", isNewUser = false))

        AccountStore.clear()

        assertEquals(AccessMode.SIGNED_OUT, AccountGate.accessMode())
        assertEquals(Landing.LOGIN, AccountGate.landing(desktopDefault = false))
    }
}
