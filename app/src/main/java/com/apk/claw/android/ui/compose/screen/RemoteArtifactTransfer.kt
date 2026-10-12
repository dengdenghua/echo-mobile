package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Persist delivery coordinates, never device credentials or server filesystem paths. */
internal data class RemoteArtifactSource(
    val base: String, val environment: String, val taskId: String, val artifactId: String,
    val name: String, val mime: String, val size: Long, val sha256: String,
)

internal object RemoteArtifactTransfer {
    const val PREFIX = "remote-artifact:"
    class DeliveryException(val resource: Int) : Exception()
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val gson = Gson()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val client = OctoHttp.shared.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun save(ref: String, source: RemoteArtifactSource) = ChatStore.savePayload(ref, gson.toJson(source))
    fun source(payloadRef: String): RemoteArtifactSource? = runCatching {
        gson.fromJson(ChatStore.loadPayload(payloadRef.removePrefix(PREFIX)), RemoteArtifactSource::class.java)
    }.getOrNull()

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun fetch(source: RemoteArtifactSource): File = withContext(Dispatchers.IO) {
        require(DotRepository.validBaseUrl(source.base) && source.size in 0..MAX_BYTES)
        val taskId = UUID.fromString(source.taskId).toString()
        val artifactId = source.artifactId.lowercase()
        require(artifactId.matches(Regex("[a-f0-9]{32}")))
        require(source.sha256.matches(Regex("[a-f0-9]{64}")))
        val key = UUID.nameUUIDFromBytes("${source.base}/$taskId/$artifactId".toByteArray()).toString()
        locks.getOrPut(key) { Mutex() }.withLock {
            val directory = File(ClawApplication.instance.cacheDir, "remote-artifacts/$key").apply { mkdirs() }
            val name = source.name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "result"
            val file = File(directory, name)
            if (file.isFile && file.length() == source.size && hash(file) == source.sha256) return@withLock file
            val environment = DotEnvironment.entries.first { it.id == source.environment }
            require(environment != DotEnvironment.PHONE)
            val connection = DotRepository.connection(environment)
            if (connection.base != source.base) throw DeliveryException(R.string.echo_task_original_connection)
            val builder = Request.Builder().url("${source.base}/api/mobile/tasks/$taskId/artifacts/$artifactId")
            if (connection.token.isNotBlank()) builder.header("Authorization", "Bearer ${connection.token}")
            val partial = File(directory, "$name.part")
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) error(ClawApplication.instance.getString(R.string.dot_delivery_fetch_failed))
                    val body = requireNotNull(response.body)
                    if (body.contentLength() > MAX_BYTES) error("Delivery exceeds limit")
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > source.size || total > MAX_BYTES) error("Delivery size mismatch")
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                }
                if (partial.length() != source.size || hash(partial) != source.sha256)
                    throw DeliveryException(R.string.dot_delivery_integrity_failed)
                if (file.exists()) file.delete()
                check(partial.renameTo(file))
                file
            } finally { partial.delete() }
        }
    }
}
