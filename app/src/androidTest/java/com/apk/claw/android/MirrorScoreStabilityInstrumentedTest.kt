package com.apk.claw.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.octopus_mobile.evolution.TurnScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MirrorScoreStabilityInstrumentedTest {
    @Test fun repeatedImageResultsStayBoundedOnAndroidHeap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "mirror-score-${System.nanoTime()}").apply { mkdirs() }
        try {
            val scorer = TurnScorer(directory)
            val image = "x".repeat(128 * 1024)
            repeat(1200) { scorer.record("mirror_frame", true, reason = image) }
            assertEquals(1000, scorer.readRecentScores(2000).size)
            assertTrue(File(directory, "turn_scores.jsonl").length() < 256 * 1024)
        } finally { directory.deleteRecursively() }
    }
}
