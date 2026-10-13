package com.apk.claw.android.octopus_mobile

import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页「准备状态」和信任中心「准备就绪度」共用 [SetupReadiness]：两处计数和清单必须一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestClawApplication::class)
class SetupReadinessTest {

    @Before
    fun setUp() = KVUtils.resetForTest()

    @After
    fun tearDown() = KVUtils.resetForTest()

    private fun item(r: SetupReadiness.Result, kind: SetupReadiness.Kind) = r.items.single { it.kind == kind }

    @Test
    fun `storage is not counted on Android 10 and later`() {
        val r = SetupReadiness.check(ApplicationProvider.getApplicationContext())

        assertFalse(SetupReadiness.storageNeedsGrant())
        assertTrue(r.items.none { it.kind == SetupReadiness.Kind.STORAGE })
        assertEquals(
            listOf(
                SetupReadiness.Kind.ACCESSIBILITY,
                SetupReadiness.Kind.MODEL,
                SetupReadiness.Kind.NOTIFICATIONS,
                SetupReadiness.Kind.OVERLAY,
                SetupReadiness.Kind.BATTERY,
                SetupReadiness.Kind.SHIZUKU,
            ),
            r.items.map { it.kind },
        )
        assertEquals(6, r.total)
    }

    @Test
    fun `missing model is reported by its name, not as a state`() {
        val r = SetupReadiness.check(ApplicationProvider.getApplicationContext())

        val model = item(r, SetupReadiness.Kind.MODEL)
        assertFalse(model.ready)
        assertTrue(model.critical)
        // 名称来自 settings_section_model，不再是「LLM 已配置」这种状态描述
        assertEquals("Model", model.label)
        assertTrue(r.missingCritical.any { it.kind == SetupReadiness.Kind.MODEL })
        assertFalse(r.isReady)
    }

    @Test
    fun `result counts ready items and critical gaps`() {
        val r = SetupReadiness.Result(
            listOf(
                SetupReadiness.Item(SetupReadiness.Kind.ACCESSIBILITY, "a", ready = true, critical = true),
                SetupReadiness.Item(SetupReadiness.Kind.MODEL, "m", ready = true, critical = true),
                SetupReadiness.Item(SetupReadiness.Kind.NOTIFICATIONS, "n", ready = false, critical = false),
                SetupReadiness.Item(SetupReadiness.Kind.SHIZUKU, "s", ready = false, critical = false),
            ),
        )

        assertEquals(2, r.readyCount)
        assertEquals(4, r.total)
        assertEquals(50, r.percent)
        assertTrue(r.isReady)
    }
}
