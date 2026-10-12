package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Bind once. Refresh only against the original host; never move an existing task to another host. */
internal object DotPairingRepository {
    private val client = OctoHttp.shared.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val locks = DotEnvironment.entries.associateWith { Mutex() }
    private fun key(environment: DotEnvironment) = "dot_pair_${environment.id}"
    private fun refreshKey(environment: DotEnvironment) = "dot_pair_refresh_${environment.id}"
    private fun metadata(environment: DotEnvironment): JsonObject? = runCatching {
        JsonParser.parseString(KVUtils.getString(key(environment), "")).asJsonObject
    }.getOrNull()
    fun paired(environment: DotEnvironment): Boolean = metadata(environment)?.get("base")?.asString ==
        DotEnvironmentStore.address(environment).trim().trimEnd('/')
    fun hostName(environment: DotEnvironment): String? = metadata(environment)?.get("host_name")?.asString

    fun clientId(environment: DotEnvironment): String {
        val key = "dot_pair_nonce_${environment.id}"
        return KVUtils.getString(key, "").ifBlank {
            UUID.randomUUID().toString().also { KVUtils.putString(key, it) }
        }
    }

    fun forget(environment: DotEnvironment) {
        KVUtils.putString(key(environment), "")
        KVUtils.putString(refreshKey(environment), "")
    }

    private fun response(base: String, path: String, body: JsonObject? = null, token: String = ""): JsonObject {
        require(DotRepository.validBaseUrl(base))
        val builder = Request.Builder().url(base + path)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use {
            if (!it.isSuccessful) throw DotRepository.TaskHttpException(it.code)
            val stream = requireNotNull(it.body).byteStream()
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 65536)
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        }
    }

    suspend fun pair(environment: DotEnvironment, base: String, code: String,
        clientId: String, expectedHost: String? = null) = withContext(Dispatchers.IO) {
        locks.getValue(environment).withLock {
            val root = base.trim().trimEnd('/')
            val result = response(root, "/api/mobile/devices/pair", JsonObject().apply {
                addProperty("code", code); addProperty("client_id", clientId)
                addProperty("name", android.os.Build.MODEL.take(80))
            })
            require(expectedHost == null || expectedHost == result.get("host_id").asString)
            require(result.get("access_token").asString.startsWith("echo_device_"))
            val refresh = result.get("refresh_token").asString
            require(refresh.length >= 32)
            DotEnvironmentStore.saveConnection(environment, root, result.get("access_token").asString, paired = true)
            result.remove("access_token"); result.remove("refresh_token")
            result.addProperty("base", root)
            result.addProperty("renew_at", System.currentTimeMillis() + 12 * 60 * 1000)
            KVUtils.putString(refreshKey(environment), refresh)
            KVUtils.putString(key(environment), result.toString())
        }
    }

    suspend fun connection(environment: DotEnvironment, forceRefresh: Boolean = false): DotRepository.Connection = withContext(Dispatchers.IO) {
        locks.getValue(environment).withLock {
            val current = DotRepository.connection(environment)
            val saved = metadata(environment)
            if (saved == null || saved.get("base").asString != current.base) return@withLock current
            if (saved.get("revoked")?.asBoolean == true) throw DotRepository.TaskHttpException(401)
            if (!forceRefresh && saved.get("renew_at").asLong > System.currentTimeMillis()) return@withLock current
            val result = try {
                response(current.base, "/api/mobile/devices/refresh", JsonObject().apply {
                    addProperty("refresh_token", KVUtils.getString(refreshKey(environment), ""))
                })
            } catch (error: DotRepository.TaskHttpException) {
                if (error.code in listOf(401, 403)) {
                    saved.addProperty("revoked", true)
                    KVUtils.putString(key(environment), saved.toString())
                }
                throw error
            }
            require(result.get("host_id").asString == saved.get("host_id").asString)
            require(DotRepository.connection(environment).base == current.base) { "Device changed during refresh" }
            val token = result.get("access_token").asString
            DotEnvironmentStore.saveConnection(environment, current.base, token, paired = true)
            saved.addProperty("renew_at", System.currentTimeMillis() + 12 * 60 * 1000)
            KVUtils.putString(key(environment), saved.toString())
            DotRepository.connection(environment)
        }
    }

    data class Health(val hostName: String, val desktop: Boolean)
    suspend fun health(environment: DotEnvironment): Health = withContext(Dispatchers.IO) {
        var snapshot = connection(environment)
        val result = try { response(snapshot.base, "/api/mobile/devices/self", token = snapshot.token) }
        catch (error: DotRepository.TaskHttpException) {
            if (error.code != 401 || !paired(environment)) throw error
            snapshot = connection(environment, forceRefresh = true)
            response(snapshot.base, "/api/mobile/devices/self", token = snapshot.token)
        }
        metadata(environment)?.let { require(it.get("host_id").asString == result.get("host_id").asString) }
        Health(result.get("host_name").asString, result.getAsJsonObject("capabilities").get("desktop").asBoolean)
    }
}
