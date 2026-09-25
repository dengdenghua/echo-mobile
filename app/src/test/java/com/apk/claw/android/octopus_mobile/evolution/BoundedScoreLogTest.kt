package com.apk.claw.android.octopus_mobile.evolution

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class BoundedScoreLogTest {
    @Test fun retainsLatestRecordsInOrder() {
        assertEquals(listOf("b", "c"), BoundedScoreLog.read(StringReader("a\nb\nc\n"), 2))
    }
    @Test fun legacyImagePayloadDoesNotEnterRetainedWindow() {
        val source = "small\n" + "x".repeat(2_000_000) + "\nlatest\n"
        assertEquals(listOf("small", "latest"), BoundedScoreLog.read(StringReader(source), 1000))
    }
    @Test fun handlesCrLfAndUnterminatedRecord() {
        assertEquals(listOf("a", "b"), BoundedScoreLog.read(StringReader("a\r\n\r\nb"), 5))
    }
    @Test fun zeroLimitIsEmpty() {
        assertEquals(emptyList<String>(), BoundedScoreLog.read(StringReader("a"), 0))
    }
}
