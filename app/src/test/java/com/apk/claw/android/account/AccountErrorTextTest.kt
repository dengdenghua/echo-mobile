package com.apk.claw.android.account

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Found on the release golden path: with no network the login screen toasted the raw platform
 * message `Unable to resolve host "api.octoapk.com": No address associated with hostname` in the
 * Chinese UI, because it showed `exception.message` as is.
 */
class AccountErrorTextTest {

    private fun text(error: Throwable) = AccountErrorText.of(
        error,
        fallback = "FALLBACK",
        network = "NETWORK",
    ) { code -> "SERVER $code" }

    @Test
    fun `network failures get the network message, not the platform text`() {
        assertEquals("NETWORK", text(UnknownHostException("Unable to resolve host \"api.octoapk.com\"")))
        assertEquals("NETWORK", text(SocketTimeoutException("timeout")))
        assertEquals("NETWORK", text(SSLHandshakeException("Connection closed by peer")))
        assertEquals("NETWORK", text(IOException("unexpected end of stream")))
    }

    @Test
    fun `server detail is shown as the server worded it`() {
        assertEquals("验证码错误", text(AccountHttpException(400, "验证码错误", """{"detail":"验证码错误"}""")))
        assertEquals("维护中", text(AccountHttpException(503, "维护中", "")))
    }

    @Test
    fun `server errors without a detail name the status, client errors fall back`() {
        assertEquals("SERVER 502", text(AccountHttpException(502, null, "<html>Bad Gateway</html>")))
        assertEquals("FALLBACK", text(AccountHttpException(404, null, "Not Found")))
        assertEquals("FALLBACK", text(AccountHttpException(400, "  ", "")))
    }

    @Test
    fun `other errors keep their message or fall back`() {
        assertEquals("未登录", text(IllegalStateException("未登录")))
        assertEquals("FALLBACK", text(RuntimeException()))
    }

    @Test
    fun `http exception message keeps the old format for callers that read it`() {
        assertEquals("验证码错误", AccountHttpException(400, "验证码错误", "x").message)
        assertEquals("HTTP 502: <html>", AccountHttpException(502, null, "<html>").message)
    }

    @Test
    fun `gateway reports a 5xx html page as an AccountHttpException`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        server.start()
        try {
            HttpAccountGateway(server.url("/").toString()).sendEmailCode("test@example.com")
            fail("expected a failure")
        } catch (e: AccountHttpException) {
            assertEquals(502, e.code)
            assertEquals("SERVER 502", text(e))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway reports a server detail through the exception`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"发送太频繁"}"""))
        server.start()
        try {
            HttpAccountGateway(server.url("/").toString()).sendEmailCode("test@example.com")
            fail("expected a failure")
        } catch (e: AccountHttpException) {
            assertEquals("发送太频繁", text(e))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway that cannot connect fails with an IOException`() = runBlocking {
        // A port that was free a moment ago: nothing listens there, so the connect is refused.
        val port = ServerSocket(0).use { it.localPort }
        try {
            HttpAccountGateway("http://127.0.0.1:$port").sendEmailCode("test@example.com")
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals("NETWORK", text(e))
        }
    }
}
