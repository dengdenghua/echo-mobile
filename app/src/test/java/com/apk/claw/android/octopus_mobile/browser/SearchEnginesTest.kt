package com.apk.claw.android.octopus_mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone offers the desktop AI 浏览器's engines, in its order, plus 问 AI. */
class SearchEnginesTest {

    @Test
    fun `engines follow the desktop list and order`() {
        assertEquals(listOf("baidu", "google", "bing"), SearchEngines.ALL.map { it.id })
        assertEquals("baidu", SearchEngines.DEFAULT.id)
    }

    @Test
    fun `engines no longer offered fall back to the default`() {
        assertEquals("baidu", SearchEngines.byId("duckduckgo").id)
        assertEquals("baidu", SearchEngines.byId(null).id)
        assertEquals("bing", SearchEngines.byId("bing").id)
    }

    @Test
    fun `ask AI covers the old AI engines`() {
        assertTrue(SearchEngines.isAi(SearchEngines.AI_ID))
        listOf("perplexity", "kimi", "tongyi").forEach { assertTrue(it, SearchEngines.isAi(it)) }
        assertFalse(SearchEngines.isAi("google"))
        // Typing an address while 问 AI is chosen still searches the web.
        assertEquals("baidu", SearchEngines.byId(SearchEngines.AI_ID).id)
    }

    @Test
    fun `search urls round-trip their query`() {
        val bing = SearchEngines.byId("bing")
        val url = bing.searchUrl("echo os")
        assertEquals("echo os", SearchEngines.extractQuery(url))
        assertNull(SearchEngines.extractQuery("https://example.com/"))
    }
}
