package com.apk.claw.android.utils

/**
 * Sensitive values are written only to the encrypted backend. Existing plaintext
 * values remain readable until an encrypted commit succeeds; failures keep new
 * values in this process only and are reported to the caller.
 */
@Suppress("TooGenericExceptionCaught") // Keystore and persistence backends fail through several exception types.
class SecureCredentialStore(
    private val encrypted: () -> EncryptedBackend?,
    private val legacy: LegacyBackend,
    private val onFailure: () -> Unit = {},
) {
    interface EncryptedBackend {
        fun read(key: String): String?
        fun write(key: String, value: String?): Boolean
        fun clear(): Boolean
    }

    /** No method accepts a plaintext credential for persistence. */
    interface LegacyBackend {
        fun read(key: String): String?
        fun remove(key: String)
        fun isCleared(key: String): Boolean
        fun markCleared(key: String, cleared: Boolean): Boolean
        fun markAllCleared(): Boolean
    }

    enum class Status { ENCRYPTED, LEGACY_READ_ONLY, SESSION_ONLY, CLEARED, UNAVAILABLE }

    private val sessionValues = mutableMapOf<String, String?>()
    private val states = mutableMapOf<String, Status>()
    private val writtenAfterClear = mutableSetOf<String>()
    private var clearedAll = false

    /** Returns true only when the change is durably committed. */
    @Synchronized
    fun write(key: String, value: String?): Boolean {
        sessionValues[key] = value
        writtenAfterClear.add(key)
        val clearing = value.isNullOrEmpty()
        if (clearing) {
            attempt { legacy.markCleared(key, true) }
            attempt { legacy.remove(key) }
        }
        val committed = attempt { encrypted()?.write(key, value) } == true
        val markerCommitted = committed && attempt { legacy.markCleared(key, clearing) } == true
        if (markerCommitted) {
            attempt { legacy.remove(key) }
            sessionValues.remove(key)
            states[key] = if (clearing) Status.CLEARED else Status.ENCRYPTED
            return true
        }
        states[key] = Status.SESSION_ONLY
        onFailure()
        return false
    }

    @Synchronized
    @Suppress("ReturnCount") // Early exits keep revocation and session overrides ahead of stored credentials.
    fun read(key: String): String? {
        if (sessionValues.containsKey(key)) return sessionValues[key]
        val revoked = try {
            legacy.isCleared(key)
        } catch (_: Exception) {
            states[key] = Status.UNAVAILABLE
            onFailure()
            return null
        }
        if ((clearedAll && key !in writtenAfterClear) || revoked) {
            states[key] = Status.CLEARED
            return null
        }
        val backend = attempt { encrypted() }
        var encryptedReadFailed = false
        val stored = try {
            backend?.read(key)
        } catch (_: Exception) {
            encryptedReadFailed = true
            null
        }
        if (stored != null) {
            states[key] = Status.ENCRYPTED
            return stored
        }
        val existing = attempt { legacy.read(key) }
        if (existing != null) {
            // Never discard an old user's value on commit=false or an exception.
            if (!encryptedReadFailed && attempt { backend?.write(key, existing) } == true) {
                attempt { legacy.remove(key) }
                states[key] = Status.ENCRYPTED
            } else {
                states[key] = Status.LEGACY_READ_ONLY
                onFailure()
            }
            return existing
        }
        states[key] = Status.UNAVAILABLE
        if (encryptedReadFailed) onFailure()
        return null
    }

    @Synchronized
    fun status(key: String): Status = states[key] ?: Status.UNAVAILABLE

    /** The marker contains no credential and prevents failed deletes from reviving on restart. */
    @Synchronized
    fun clear(): Boolean {
        sessionValues.clear()
        states.clear()
        writtenAfterClear.clear()
        clearedAll = true
        val marked = attempt { legacy.markAllCleared() } == true
        val committed = attempt { encrypted()?.clear() } == true
        if (!marked || !committed) onFailure()
        return marked && committed
    }

    @Synchronized
    fun resetSession() {
        sessionValues.clear()
        states.clear()
        writtenAfterClear.clear()
        clearedAll = false
    }

    private fun <T> attempt(operation: () -> T): T? = try {
        operation()
    } catch (_: Exception) {
        null
    }
}
