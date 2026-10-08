package com.apk.claw.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("TooManyFunctions") // Regression cases cover separate storage failure modes.
class SecureCredentialStoreTest {
    private class Encrypted : SecureCredentialStore.EncryptedBackend {
        val values = mutableMapOf<String, String>()
        var commit = true
        var failRead = false
        var failWrite = false
        override fun read(key: String): String? {
            check(!failRead) { "keystore unavailable" }
            return values[key]
        }
        override fun write(key: String, value: String?): Boolean {
            check(!failWrite) { "keystore unavailable" }
            if (!commit) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
        override fun clear(): Boolean {
            check(!failWrite) { "keystore unavailable" }
            if (commit) values.clear()
            return commit
        }
    }

    private class Legacy : SecureCredentialStore.LegacyBackend {
        val values = mutableMapOf<String, String>()
        private val cleared = mutableMapOf<String, Boolean>()
        private var clearedAll = false
        var failMarkerRead = false
        override fun read(key: String): String? = values[key]
        override fun remove(key: String) { values.remove(key) }
        override fun isCleared(key: String): Boolean {
            check(!failMarkerRead) { "persistence unavailable" }
            return cleared[key] ?: clearedAll
        }
        override fun markCleared(key: String, cleared: Boolean): Boolean {
            this.cleared[key] = cleared
            return true
        }
        override fun markAllCleared(): Boolean {
            cleared.clear()
            clearedAll = true
            return true
        }
    }

    @Test fun `unavailable encryption keeps new secret in session only`() {
        val legacy = Legacy()
        val store = SecureCredentialStore({ null }, legacy)
        assertFalse(store.write("token", "new-secret"))
        assertEquals("new-secret", store.read("token"))
        assertEquals(SecureCredentialStore.Status.SESSION_ONLY, store.status("token"))
        assertTrue(legacy.values.isEmpty())
        assertNull(SecureCredentialStore({ null }, legacy).read("token"))
    }

    @Test fun `failed commit preserves old encrypted value and overlays new value`() {
        val encrypted = Encrypted().apply { values["token"] = "old"; commit = false }
        val legacy = Legacy()
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertFalse(store.write("token", "new"))
        assertEquals("new", store.read("token"))
        assertEquals("old", encrypted.values["token"])
        assertEquals("old", SecureCredentialStore({ encrypted }, legacy).read("token"))
        assertTrue(legacy.values.isEmpty())
    }

    @Test fun `write exception never writes a plaintext replacement`() {
        val encrypted = Encrypted().apply { failWrite = true }
        val legacy = Legacy().apply { values["token"] = "old" }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertFalse(store.write("token", "new"))
        assertEquals("new", store.read("token"))
        assertEquals("old", legacy.values["token"])
    }

    @Test fun `legacy value migrates only after a successful encrypted commit`() {
        val encrypted = Encrypted()
        val legacy = Legacy().apply { values["token"] = "old" }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertEquals("old", store.read("token"))
        assertEquals("old", encrypted.values["token"])
        assertFalse(legacy.values.containsKey("token"))
        assertEquals(SecureCredentialStore.Status.ENCRYPTED, store.status("token"))
    }

    @Test fun `failed migration commit preserves readable legacy data`() {
        val encrypted = Encrypted().apply { commit = false }
        val legacy = Legacy().apply { values["token"] = "old" }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertEquals("old", store.read("token"))
        assertEquals("old", legacy.values["token"])
        assertEquals(SecureCredentialStore.Status.LEGACY_READ_ONLY, store.status("token"))
    }

    @Test fun `legacy data remains readable without encryption`() {
        val legacy = Legacy().apply { values["token"] = "old" }
        val store = SecureCredentialStore({ null }, legacy)
        assertEquals("old", store.read("token"))
        assertEquals("old", legacy.values["token"])
    }

    @Test fun `encrypted read failure cannot overwrite an existing encrypted value`() {
        val encrypted = Encrypted().apply { values["token"] = "current"; failRead = true }
        val legacy = Legacy().apply { values["token"] = "stale" }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertEquals("stale", store.read("token"))
        assertEquals("current", encrypted.values["token"])
        assertEquals("stale", legacy.values["token"])
    }

    @Test fun `encrypted empty string does not revive a stale legacy credential`() {
        val encrypted = Encrypted().apply { values["token"] = "" }
        val legacy = Legacy().apply { values["token"] = "stale" }
        assertEquals("", SecureCredentialStore({ encrypted }, legacy).read("token"))
    }

    @Test fun `failed credential deletion stays deleted after restart`() {
        val encrypted = Encrypted().apply { values["token"] = "old"; failWrite = true }
        val legacy = Legacy().apply { values["token"] = "old" }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertFalse(store.write("token", null))
        assertNull(store.read("token"))
        assertNull(SecureCredentialStore({ encrypted }, legacy).read("token"))
        assertEquals("old", encrypted.values["token"])
        assertTrue(legacy.values.isEmpty())
    }

    @Test fun `empty string clears credentials even if encryption is unavailable`() {
        val legacy = Legacy().apply { values["token"] = "old" }
        val store = SecureCredentialStore({ null }, legacy)
        assertFalse(store.write("token", ""))
        assertEquals("", store.read("token"))
        assertNull(SecureCredentialStore({ null }, legacy).read("token"))
    }

    @Test fun `encryption recovery durably saves session value`() {
        val encrypted = Encrypted().apply { failWrite = true }
        val legacy = Legacy()
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertFalse(store.write("token", "new"))
        encrypted.failWrite = false
        assertTrue(store.write("token", "new"))
        assertEquals("new", SecureCredentialStore({ encrypted }, legacy).read("token"))
        assertEquals(SecureCredentialStore.Status.ENCRYPTED, store.status("token"))
    }

    @Test fun `failed clear blocks all old keys but permits an explicit new credential`() {
        val encrypted = Encrypted().apply { values["dynamic-token"] = "old"; failWrite = true }
        val legacy = Legacy()
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertFalse(store.clear())
        assertNull(SecureCredentialStore({ encrypted }, legacy).read("dynamic-token"))
        encrypted.failWrite = false
        assertTrue(store.write("new-token", "new"))
        val restarted = SecureCredentialStore({ encrypted }, legacy)
        assertNull(restarted.read("dynamic-token"))
        assertEquals("new", restarted.read("new-token"))
    }

    @Test fun `unreadable revocation marker cannot revive a credential`() {
        val encrypted = Encrypted().apply { values["token"] = "old" }
        val legacy = Legacy().apply { failMarkerRead = true }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertNull(store.read("token"))
        assertEquals(SecureCredentialStore.Status.UNAVAILABLE, store.status("token"))
    }

    @Test fun `legacy key pool JSON stays intact until encrypted migration succeeds`() {
        val key = "llm_api_key_pool_v1"
        val json = """[{"k":"test-backup-key","s":2,"f":1,"cf":0,"lu":123,"lf":0,"d":false}]"""
        val encrypted = Encrypted().apply { commit = false }
        val legacy = Legacy().apply { values[key] = json }
        val store = SecureCredentialStore({ encrypted }, legacy)
        assertEquals(json, store.read(key))
        assertEquals(json, legacy.values[key])
        assertEquals(SecureCredentialStore.Status.LEGACY_READ_ONLY, store.status(key))
        encrypted.commit = true
        assertEquals(json, store.read(key))
        assertEquals(json, encrypted.values[key])
        assertFalse(legacy.values.containsKey(key))
    }
}
