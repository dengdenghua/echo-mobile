package com.apk.claw.android.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordKeywordTest {

    @Test
    fun `a new install defaults to the Echo wake word in the UI language`() {
        assertEquals(
            "你好 Echo",
            WakeWordSettings.resolveKeyword(stored = null, usedWakeWordBefore = false, language = "zh"),
        )
        assertEquals(
            "Hey Echo",
            WakeWordSettings.resolveKeyword(stored = "", usedWakeWordBefore = false, language = "en"),
        )
        assertEquals(
            "Hey Echo",
            WakeWordSettings.resolveKeyword(stored = null, usedWakeWordBefore = false, language = "ja"),
        )
    }

    @Test
    fun `an existing wake-word user who never picked a word keeps 章鱼章鱼`() {
        assertEquals("章鱼章鱼", WakeWordSettings.resolveKeyword(stored = null, usedWakeWordBefore = true))
    }

    @Test
    fun `a word the user picked is kept, including the old octopus presets`() {
        WakeWordSettings.LEGACY_PRESETS.forEach { legacy ->
            assertEquals(legacy, WakeWordSettings.resolveKeyword(stored = legacy, usedWakeWordBefore = true))
            assertEquals(legacy, WakeWordSettings.resolveKeyword(stored = legacy, usedWakeWordBefore = false))
        }
        assertEquals("Hey Echo", WakeWordSettings.resolveKeyword(stored = "Hey Echo", usedWakeWordBefore = true))
    }

    @Test
    fun `the Echo presets are listed first and an old octopus word stays selectable`() {
        val fresh = WakeWordSettings.presetsFor(WakeWordSettings.DEFAULT_KEYWORD, language = "zh")
        assertEquals(WakeWordSettings.ECHO_PRESETS, fresh)
        assertEquals(WakeWordSettings.DEFAULT_KEYWORD, fresh.first())
        assertFalse(fresh.any { "章鱼" in it })
        assertEquals("Hey Echo", WakeWordSettings.presetsFor("Hey Echo", language = "en").first())

        val legacy = WakeWordSettings.presetsFor("小章鱼", language = "zh")
        assertEquals(WakeWordSettings.ECHO_PRESETS, legacy.take(WakeWordSettings.ECHO_PRESETS.size))
        assertTrue("小章鱼" in legacy)
    }
}
