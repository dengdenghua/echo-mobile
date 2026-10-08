package com.apk.claw.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnprivilegedShellFallbackTest {

    @Test
    fun `privileged commands require elevation`() {
        listOf(
            "input tap 1 2", "screencap -p", "settings put global a 0", "am start x", "pm list packages",
            "dumpsys window", "uiautomator dump /sdcard/x.xml", "cmd package list", "monkey -p a 1", "wm size",
            "sh -c \"dumpsys window\"", "", "   ", "unknowncmd arg",
        ).forEach { assertTrue(it, UnprivilegedShellFallback.requiresElevation(it)) }
    }

    @Test
    fun `file commands may run unprivileged`() {
        listOf(
            "ls /sdcard", "cat /sdcard/a.txt", "rm /sdcard/a", "mkdir /sdcard/x", "echo hi",
            "sh -c \"cat /sdcard/a\"", "sh -c \"rm -f /sdcard/a\"",
        ).forEach { assertFalse(it, UnprivilegedShellFallback.requiresElevation(it)) }
    }

    @Test
    fun `refused result is a failure that says why`() {
        val r = UnprivilegedShellFallback.refused()
        assertFalse(r.isSuccess)
        assertFalse(r.privileged)
        assertEquals(UnprivilegedShellFallback.REFUSED_MESSAGE, r.stderr)
    }

    @Test
    fun `unprivileged result is flagged and annotated`() {
        val ok = UnprivilegedShellFallback.markUnprivileged(ShizukuShellService.ShellResult(0, "out", ""))
        assertTrue(ok.isSuccess)
        assertFalse(ok.privileged)
        assertEquals("out", ok.stdout)
        assertEquals(UnprivilegedShellFallback.UNPRIVILEGED_NOTE, ok.stderr)

        val err = UnprivilegedShellFallback.markUnprivileged(ShizukuShellService.ShellResult(1, "", "boom"))
        assertTrue(err.stderr.startsWith(UnprivilegedShellFallback.UNPRIVILEGED_NOTE))
        assertTrue(err.stderr.endsWith("boom"))
    }

    @Test
    fun `app uid input output with security exception is not success`() {
        assertTrue(UnprivilegedShellFallback.appUidCommandSucceeded(0, ""))
        assertFalse(UnprivilegedShellFallback.appUidCommandSucceeded(1, ""))
        assertFalse(
            UnprivilegedShellFallback.appUidCommandSucceeded(
                0, "java.lang.SecurityException: Injecting to another application requires INJECT_EVENTS permission",
            ),
        )
    }
}
