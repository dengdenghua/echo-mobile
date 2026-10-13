package com.apk.claw.android.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/** `GET /app/latest`: what the client sends and how it reads the answer, including a key change. */
class UpdateManifestTest {

    private val oldKey = "a".repeat(64)
    private val newKey = "b".repeat(64)
    private lateinit var server: MockWebServer

    private fun installed(cert: String) = ApkFacts("com.octopus.mobile", 15, setOf(cert), setOf(cert), false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun manifest(vc: Int, cert: String = "", notes: String = "notes") =
        """{"versionCode":$vc,"versionName":"1.1.$vc","url":"https://x/app/latest.apk",""" +
            """"notes":"$notes","force":true,"signingCertSha256":"$cert"}"""

    @Test
    fun `request carries this install's certificate so the server can pick key-change notes`() {
        assertEquals("https://h/app/latest?cert=$newKey", UpdateManifest.latestUrl("https://h", installed(newKey)))
        assertEquals("https://h/app/latest", UpdateManifest.latestUrl("https://h", null))
        val twoSigners = ApkFacts("p", 1, setOf(oldKey, newKey), emptySet(), true)
        assertEquals("https://h/app/latest", UpdateManifest.latestUrl("https://h", twoSigners))
    }

    @Test
    fun `same key update is a normal update`() {
        val r = UpdateManifest.parse(manifest(16, cert = newKey), 15, installed(newKey))
        val info = (r as AppUpdater.CheckResult.Available).info
        assertEquals(16, info.versionCode)
        assertEquals(newKey, info.signingCertSha256)
        assertTrue(info.force)
        assertFalse(info.reinstallRequired)
    }

    @Test
    fun `different key is flagged before download`() {
        val r = UpdateManifest.parse(manifest(16, cert = newKey.uppercase()), 15, installed(oldKey))
        assertTrue((r as AppUpdater.CheckResult.Available).info.reinstallRequired)
    }

    @Test
    fun `an older server without the fingerprint still offers the update`() {
        val body = """{"versionCode":16,"versionName":"1.1.1","url":"https://x/a.apk","notes":"","force":false}"""
        val info = (UpdateManifest.parse(body, 15, installed(oldKey)) as AppUpdater.CheckResult.Available).info
        assertEquals("", info.signingCertSha256)
        assertFalse(info.reinstallRequired) // the download-time check catches a mismatch instead
    }

    @Test
    fun `up to date and missing url`() {
        assertEquals(AppUpdater.CheckResult.UpToDate, UpdateManifest.parse(manifest(15), 15, null))
        assertEquals(AppUpdater.CheckResult.UpToDate, UpdateManifest.parse("""{"versionCode":0}""", 15, null))
        val r = UpdateManifest.parse("""{"versionCode":16,"url":""}""", 15, null)
        assertEquals(AppUpdater.ErrorKind.BAD_RESPONSE, (r as AppUpdater.CheckResult.Error).kind)
    }

    @Test
    fun `check sends the certificate and reads the answer`() {
        server.enqueue(MockResponse().setBody(manifest(16, cert = newKey)))
        val base = server.url("/").toString().trimEnd('/')

        val r = AppUpdater.checkAt(base, 15, installed(oldKey))

        val req = server.takeRequest()
        assertEquals("/app/latest?cert=$oldKey", req.path)
        assertTrue((r as AppUpdater.CheckResult.Available).info.reinstallRequired)
    }

    @Test
    fun `check failures say what kind they are`() {
        server.enqueue(MockResponse().setResponseCode(502))
        val base = server.url("/").toString().trimEnd('/')
        val serverError = AppUpdater.checkAt(base, 15, null) as AppUpdater.CheckResult.Error
        assertEquals(AppUpdater.ErrorKind.SERVER, serverError.kind)

        // Nothing listening (offline, server down) → a network error, not a crash.
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = AppUpdater.checkAt("http://127.0.0.1:$closedPort", 15, null) as AppUpdater.CheckResult.Error
        assertEquals(AppUpdater.ErrorKind.NETWORK, offline.kind)

        val notConfigured = AppUpdater.checkAt("", 15, null) as AppUpdater.CheckResult.Error
        assertEquals(AppUpdater.ErrorKind.NOT_CONFIGURED, notConfigured.kind)
        assertNull(AppUpdater.available.value)
    }
}
