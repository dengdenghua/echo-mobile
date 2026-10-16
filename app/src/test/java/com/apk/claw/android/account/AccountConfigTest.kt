package com.apk.claw.android.account

import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 广场 (Square) base URL: the built-in default matches the desktop, and the resolution order is
 * manual override → server /config cache → default. Plain JVM: KVUtils falls back to memory.
 */
class AccountConfigTest {

    @Before
    fun setUp() {
        KVUtils.resetForTest()
    }

    @After
    fun tearDown() {
        KVUtils.resetForTest()
    }

    @Test
    fun `default square is the same host the desktop uses`() {
        assertEquals("https://ai.echo-age.com", AccountConfig.DEFAULT_SQUARE_BASE_URL)
        assertEquals(AccountConfig.DEFAULT_SQUARE_BASE_URL, AccountConfig.squareBaseUrl)
    }

    @Test
    fun `server config overrides the default and a manual address overrides both`() {
        AccountConfig.setRemoteSquareBaseUrl(" https://square.example ")
        assertEquals("https://square.example", AccountConfig.squareBaseUrl)

        AccountConfig.squareBaseUrl = "https://manual.example"
        assertEquals("https://manual.example", AccountConfig.squareBaseUrl)

        AccountConfig.squareBaseUrl = ""
        assertEquals("https://square.example", AccountConfig.squareBaseUrl)
    }

    @Test
    fun `a blank server value keeps the default`() {
        AccountConfig.setRemoteSquareBaseUrl("   ")
        assertEquals(AccountConfig.DEFAULT_SQUARE_BASE_URL, AccountConfig.squareBaseUrl)
    }
}
