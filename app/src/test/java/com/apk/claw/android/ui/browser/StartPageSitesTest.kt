package com.apk.claw.android.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The start page's 常用网站: the user's sites first, then the desktop's top sites from history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartPageSitesTest {

    @Test
    fun `shortcuts come first, then the most visited hosts, one tile per host`() {
        val shortcuts = listOf(CommonSiteItem("https://github.com", "GitHub", 1L))
        val history = listOf(
            HistoryEntry("a", "https://www.zhihu.com/question/1", 10L),
            HistoryEntry("b", "https://news.example.com/x", 30L),
            HistoryEntry("c", "https://zhihu.com/question/2", 20L),
            HistoryEntry("d", "https://github.com/echo", 40L),
            HistoryEntry("e", "echo://workspace/projects", 50L),
        )
        val sites = startPageSites(shortcuts, history)
        assertEquals(
            listOf("https://github.com", "https://www.zhihu.com/", "https://news.example.com/"),
            sites.map { it.url },
        )
        assertEquals(listOf("GitHub", "zhihu.com", "news.example.com"), sites.map { it.title })
    }

    @Test
    fun `at most eight tiles`() {
        val history = (1..12).map { HistoryEntry("s$it", "https://site$it.example/", it.toLong()) }
        assertEquals(8, startPageSites(emptyList(), history).size)
    }
}
