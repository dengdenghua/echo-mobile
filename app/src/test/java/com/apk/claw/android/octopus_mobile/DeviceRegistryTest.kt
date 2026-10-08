package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.SecretKeyValueStore
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceRegistryTest {

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

    private val registryKey = "OCTOPUS_DEVICE_REGISTRY"
    private val tokenKey = DeviceRegistry.TOKEN_KEY_PREFIX + "dev-1"

    @Before
    fun setUp() = KVUtils.resetForTest()

    @After
    fun tearDown() = KVUtils.resetForTest()

    /** Shape written by the previous release: a full DeviceInfo including the plaintext token. */
    private fun legacyJson() = Gson().toJson(listOf(beacon(token = "legacy")))

    private fun beacon(ip: String = "192.168.1.20", token: String = "") =
        DeviceInfo(deviceId = "dev-1", deviceName = "Pad", ip = ip, authToken = token)

    @Test
    fun `beacon without token keeps verified token at same address`() {
        val existing = beacon(token = "lan-secret").copy(firstSeenTs = 1L)
        val merged = DeviceRegistry.mergeDiscovered(existing, beacon(), now = 50L)
        assertEquals("lan-secret", merged.authToken)
        assertEquals(1L, merged.firstSeenTs)
        assertEquals(50L, merged.lastSeenTs)
        assertTrue(merged.online)
    }

    @Test
    fun `beacon from a changed address drops the token`() {
        val existing = beacon(token = "lan-secret")
        val merged = DeviceRegistry.mergeDiscovered(existing, beacon(ip = "192.168.1.99"), now = 50L)
        assertEquals("", merged.authToken)
    }

    @Test
    fun `incoming token replaces existing token`() {
        val merged = DeviceRegistry.mergeDiscovered(beacon(token = "old"), beacon(token = "new"), now = 1L)
        assertEquals("new", merged.authToken)
    }

    @Test
    fun `account token survives periodic discovery beacons`() {
        val registry = DeviceRegistry(FakeSecrets())
        registry.upsertDevice(beacon())
        registry.applyAccountTokens(mapOf("192.168.1.20" to "lan-secret"))
        registry.upsertDevice(beacon())
        registry.upsertDevice(beacon())
        assertEquals("lan-secret", registry.getDevice("dev-1")?.authToken)

        registry.upsertDevice(beacon(ip = "192.168.1.99"))
        assertEquals("", registry.getDevice("dev-1")?.authToken)
    }

    @Test
    fun `token is persisted only in the secret store`() {
        val secrets = FakeSecrets()
        val registry = DeviceRegistry(secrets)
        registry.upsertDevice(beacon())
        registry.applyAccountTokens(mapOf("192.168.1.20" to "lan-secret"))
        registry.doPersist()

        assertFalse(KVUtils.getString(registryKey, "").contains("lan-secret"))
        assertEquals("lan-secret", secrets.values[tokenKey])
        assertEquals("lan-secret", DeviceRegistry(secrets).getDevice("dev-1")?.authToken)

        registry.removeDevice("dev-1")
        registry.doPersist()
        assertFalse(secrets.values.containsKey(tokenKey))
    }

    @Test
    fun `legacy plaintext token is migrated and then cleared`() {
        KVUtils.putString(registryKey, legacyJson())
        val secrets = FakeSecrets()
        val registry = DeviceRegistry(secrets)

        assertEquals("legacy", registry.getDevice("dev-1")?.authToken)
        assertEquals("legacy", secrets.values[tokenKey])
        assertFalse(KVUtils.getString(registryKey, "").contains("legacy"))
    }

    @Test
    fun `legacy plaintext token is kept when migration fails`() {
        KVUtils.putString(registryKey, legacyJson())
        val secrets = FakeSecrets(writable = false)
        val registry = DeviceRegistry(secrets)

        assertEquals("legacy", registry.getDevice("dev-1")?.authToken)
        assertTrue(KVUtils.getString(registryKey, "").contains("legacy"))

        // A new token is never written to plaintext even while encryption is unavailable.
        registry.applyAccountTokens(mapOf("192.168.1.20" to "fresh"))
        registry.doPersist()
        val json = KVUtils.getString(registryKey, "")
        assertFalse(json.contains("fresh"))
        assertFalse(json.contains("legacy"))

        // Once encryption recovers, the retained legacy value migrates on the next load.
        KVUtils.putString(registryKey, legacyJson())
        secrets.writable = true
        DeviceRegistry(secrets)
        assertFalse(KVUtils.getString(registryKey, "").contains("legacy"))
        assertEquals("legacy", secrets.values[tokenKey])
    }
}
