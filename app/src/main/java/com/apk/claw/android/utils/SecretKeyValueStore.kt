package com.apk.claw.android.utils

/**
 * Minimal per-key secret storage used by registries that keep metadata in plain
 * JSON and credentials separately. The default implementation routes keys through
 * [KVUtils], whose secure key prefixes send them to the encrypted backend.
 */
interface SecretKeyValueStore {
    fun read(key: String): String?

    /** Returns true only when the value was durably committed to encrypted storage. */
    fun write(key: String, value: String): Boolean

    fun remove(key: String)

    companion object {
        val Default: SecretKeyValueStore = object : SecretKeyValueStore {
            override fun read(key: String): String? = KVUtils.getString(key, "").ifEmpty { null }
            override fun write(key: String, value: String): Boolean = KVUtils.putString(key, value)
            override fun remove(key: String) = KVUtils.remove(key)
        }
    }
}
