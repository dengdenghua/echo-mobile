package com.apk.claw.android.media

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.SecretKeyValueStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavMountsTest {

    private class FakeSecrets(var writable: Boolean = true) : SecretKeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String): Boolean {
            if (writable) values[key] = value
            return writable
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private val secrets = FakeSecrets()
    private val original = WebDavMounts.secrets

    @Before
    fun setUp() {
        KVUtils.resetForTest()
        WebDavMounts.secrets = secrets
    }

    @After
    fun tearDown() {
        WebDavMounts.secrets = original
        KVUtils.resetForTest()
    }

    private fun plainJson() = KVUtils.getString("webdav_mounts", "")

    private companion object {
        /** Shape written by the previous release, password in plaintext. */
        const val LEGACY_JSON =
            """[{"id":"m1","name":"NAS","baseUrl":"http://h","rootPath":"/","username":"u","password":"old"}]"""
    }

    @Test
    fun `password is stored encrypted and never in plaintext json`() {
        WebDavMounts.add(WebDavMounts.Mount("m1", "NAS", "http://192.168.1.10:5005", username = "u", password = "p@ss"))

        assertFalse(plainJson().contains("p@ss"))
        assertEquals("p@ss", secrets.values[WebDavMounts.PWD_KEY_PREFIX + "m1"])
        assertEquals("p@ss", WebDavMounts.all().single().password)

        WebDavMounts.remove("m1")
        assertTrue(WebDavMounts.all().isEmpty())
        assertFalse(secrets.values.containsKey(WebDavMounts.PWD_KEY_PREFIX + "m1"))
    }

    @Test
    fun `legacy plaintext password migrates on read`() {
        KVUtils.putString("webdav_mounts", LEGACY_JSON)

        assertEquals("old", WebDavMounts.all().single().password)
        assertFalse(plainJson().contains("old"))
        assertEquals("old", secrets.values[WebDavMounts.PWD_KEY_PREFIX + "m1"])
        assertEquals("old", WebDavMounts.all().single().password)
    }

    @Test
    fun `legacy plaintext is kept when migration fails`() {
        secrets.writable = false
        KVUtils.putString("webdav_mounts", LEGACY_JSON)

        assertEquals("old", WebDavMounts.all().single().password)
        assertTrue(plainJson().contains("old"))

        // Adding another mount must not discard the unmigrated legacy password, nor persist the new one in plaintext.
        WebDavMounts.add(WebDavMounts.Mount("m2", "Cloud", "http://c", username = "v", password = "new"))
        assertTrue(plainJson().contains("old"))
        assertFalse(plainJson().contains("new"))

        secrets.writable = true
        assertEquals("old", WebDavMounts.all().first { it.id == "m1" }.password)
        assertFalse(plainJson().contains("old"))
    }
}
