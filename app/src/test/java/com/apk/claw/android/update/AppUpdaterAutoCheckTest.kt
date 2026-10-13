package com.apk.claw.android.update

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * 启动自动检查更新每个进程只发一次：MainActivity 重建（旋转、换主题、换语言）不再重复请求。
 * 纯 JVM：注入假的检查函数，不走网络。
 */
class AppUpdaterAutoCheckTest {

    @Before
    fun setUp() = AppUpdater.resetAutoCheckForTest()

    @After
    fun tearDown() = AppUpdater.resetAutoCheckForTest()

    @Test
    fun `auto check runs only once per process`() = runBlocking {
        var calls = 0
        val fake: suspend () -> AppUpdater.CheckResult = {
            calls++
            AppUpdater.CheckResult.UpToDate
        }

        val first = AppUpdater.checkOncePerProcess(fake)
        val second = AppUpdater.checkOncePerProcess(fake)
        val third = AppUpdater.checkOncePerProcess(fake)

        assertSame(AppUpdater.CheckResult.UpToDate, first)
        assertNull(second)
        assertNull(third)
        assertEquals(1, calls)
    }

    @Test
    fun `a failed first check is not retried on recreation`() = runBlocking {
        var calls = 0
        val failing: suspend () -> AppUpdater.CheckResult = {
            calls++
            AppUpdater.CheckResult.Error("offline")
        }

        AppUpdater.checkOncePerProcess(failing)
        AppUpdater.checkOncePerProcess(failing)

        assertEquals(1, calls)
    }
}
