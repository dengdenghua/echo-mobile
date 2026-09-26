package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unknown evidence must never become a verified success or trigger a repair. */
class GoalVerifierTest {

    @Test
    fun `clear YES is achieved`() {
        val v = GoalVerifier.parse("YES\n已经成功发送消息")
        assertTrue(v.achieved)
        assertEquals("已经成功发送消息", v.reason)
    }

    @Test
    fun `clear NO is not achieved`() {
        val v = GoalVerifier.parse("NO\n消息还停留在输入框，未点发送")
        assertFalse(v.achieved)
        assertEquals("消息还停留在输入框，未点发送", v.reason)
    }

    @Test
    fun `chinese 否 prefix is not achieved`() {
        assertFalse(GoalVerifier.parse("否，页面没有跳转").achieved)
    }

    @Test
    fun `chinese 未完成 is not achieved`() {
        assertFalse(GoalVerifier.parse("任务未完成").achieved)
        assertFalse(GoalVerifier.parse("未达成").achieved)
        assertFalse(GoalVerifier.parse("没有达成").achieved)
    }

    @Test
    fun `chinese 是 and 已完成 are achieved`() {
        assertTrue(GoalVerifier.parse("是\n聊天记录里能看到这条消息").achieved)
        assertTrue(GoalVerifier.parse("已完成").achieved)
        assertTrue(GoalVerifier.parse("已达成").achieved)
    }

    @Test
    fun `ambiguous answer stays unverified`() {
        assertEquals(GoalVerifier.Status.UNVERIFIED, GoalVerifier.parse("不确定，可能需要再看看").status)
        assertEquals(GoalVerifier.Status.UNVERIFIED, GoalVerifier.parse("这张截图看起来像是设置页").status)
    }

    @Test
    fun `blank answer stays unverified`() {
        assertEquals(GoalVerifier.Status.UNVERIFIED, GoalVerifier.parse("").status)
        assertEquals(GoalVerifier.Status.UNVERIFIED, GoalVerifier.parse("   \n  ").status)
    }

    @Test
    fun `head verdict line governs, body text is only reason`() {
        // positive/negative 只看首行裁决，不扫正文：首行 NO 即判未达成，
        // 即便正文出现"已完成"也不翻盘（信任 VLM 的 YES/NO 裁决行）。
        val v = GoalVerifier.parse("NO\n但其实已完成了")
        assertFalse(v.achieved)
        assertEquals("但其实已完成了", v.reason)
    }

    @Test
    fun `reason falls back to head when single line`() {
        val v = GoalVerifier.parse("未完成")
        assertFalse(v.achieved)
        assertEquals("未完成", v.reason)
    }

    @Test
    fun `mixed or misleading verdict prefixes remain unverified`() {
        listOf("YES / NO", "YES but not completed", "Yesterday", "Nobody", "是否已完成", "未完成但显示已完成", "UNKNOWN").forEach {
            val verdict = GoalVerifier.parse(it)
            assertEquals(it, GoalVerifier.Status.UNVERIFIED, verdict.status)
            assertFalse(verdict.needsRepair)
        }
    }

    @Test
    fun `missing screenshot cannot verify completion`() = kotlinx.coroutines.runBlocking {
        val verdict = GoalVerifier.verify("保存文件", null)
        assertEquals(GoalVerifier.Status.UNVERIFIED, verdict.status)
        assertFalse(verdict.achieved)
        assertFalse(verdict.needsRepair)
        assertTrue(verdict.present("模型声称完成").startsWith("结果待核验"))
    }
}
