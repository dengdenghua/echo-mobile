package com.apk.claw.android.ui.featurescreens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 通道访问控制页的「/pair <码>」提示：没有配对码时不能显示 "/pair null"。 */
class ChannelAclPairHintTest {

    @Test
    fun `no pairing code gives no hint`() {
        assertNull(pairCommandHint("DingTalk", null))
    }

    @Test
    fun `blank pairing code gives no hint`() {
        assertNull(pairCommandHint("DingTalk", ""))
        assertNull(pairCommandHint("DingTalk", "   "))
    }

    @Test
    fun `hint carries the real code and channel`() {
        val hint = pairCommandHint("DingTalk", "482913")
        assertEquals("在 DingTalk 给机器人发送:/pair 482913", hint)
        assertFalse(hint!!.contains("null"))
    }
}
