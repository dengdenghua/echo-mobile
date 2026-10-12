package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object DotRepository {
    private val gson = Gson()
    private val client = OctoHttp.shared.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val taskClient = client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    private val rosterClient = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun validBaseUrl(value: String): Boolean = value.trim().toHttpUrlOrNull()?.let {
        it.scheme in listOf("http", "https") && it.encodedPath == "/" && it.query == null &&
            it.fragment == null && it.username.isEmpty() && it.password.isEmpty()
    } == true

    private suspend fun request(path: String, environment: DotEnvironment): Request.Builder {
        require(environment != DotEnvironment.PHONE) { "Phone tasks use the local agent" }
        val connection = DotPairingRepository.connection(environment)
        val base = connection.base
        require(validBaseUrl(base)) { "Invalid Echo address" }
        return Request.Builder().url(base + path).apply {
            connection.token.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }
    }

    private fun execute(request: Request, roster: Boolean = false): String = (if (roster) rosterClient else client).newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        response.body?.string()?.takeIf { it.isNotBlank() } ?: error("Empty response")
    }

    /** Only actual desktop squad agents are returned; other installed capabilities stay out. */
    suspend fun installedAgentIds(environment: DotEnvironment = DotEnvironmentStore.selected): Set<String> = withContext(Dispatchers.IO) {
        if (environment == DotEnvironment.PHONE) return@withContext DotRoster.members.map { it.agentId }.toSet()
        val response = JsonParser.parseString(execute(request("/api/agents?include_visuals=false", environment).get().build(), roster = true))
        val rows = if (response.isJsonArray) response.asJsonArray else response.asJsonObject.getAsJsonArray("agents")
        requireNotNull(rows) { "Missing agent roster" }
        rows.mapNotNull { it.asJsonObject.get("name")?.asString }.toSet()
    }

    internal data class Connection(val base: String, val token: String, val environment: DotEnvironment? = null)
    internal data class TaskArtifact(val id: String, val name: String, val mime: String, val size: Long, val sha256: String)
    internal data class TaskResult(val status: String, val answer: String,
        val artifacts: List<TaskArtifact> = emptyList(), val omittedArtifacts: Int = 0)
    internal class TaskHttpException(val code: Int) : Exception("HTTP $code")

    fun connection(environment: DotEnvironment) = Connection(
        DotEnvironmentStore.address(environment).trim().trimEnd('/'), DotEnvironmentStore.token(environment), environment)

    private suspend fun taskRequest(path: String, connection: Connection, body: String? = null, mayRefresh: Boolean = true): TaskResult = withContext(Dispatchers.IO) {
        require(validBaseUrl(connection.base)) { "Invalid device address" }
        val builder = Request.Builder().url(connection.base + path)
        if (connection.token.isNotBlank()) builder.header("Authorization", "Bearer ${connection.token}")
        if (body != null) builder.post(body.toRequestBody(jsonType)) else builder.get()
        taskClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 && mayRefresh && connection.environment?.let { DotPairingRepository.paired(it) } == true) {
                response.close()
                val renewed = DotPairingRepository.connection(connection.environment, forceRefresh = true)
                require(renewed.base == connection.base) { "Original device connection changed" }
                return@withContext taskRequest(path, renewed, body, mayRefresh = false)
            }
            if (!response.isSuccessful) throw TaskHttpException(response.code)
            val row = JsonParser.parseString(response.body!!.string()).asJsonObject
            val result = row.get("result")?.takeUnless { it.isJsonNull }?.asJsonObject
            val answer = result?.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString.orEmpty()
            val hostStatus = row.get("status").asString
            val executionFailed = result?.getAsJsonObject("echo")?.get("success")?.let { !it.isJsonNull && !it.asBoolean } == true ||
                result?.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.get("finish_reason")?.asString == "failed"
            val artifacts = row.getAsJsonArray("artifacts")?.mapNotNull { item ->
                runCatching {
                    val artifact = item.asJsonObject
                    TaskArtifact(artifact.get("id").asString, artifact.get("name").asString,
                        artifact.get("mime").asString, artifact.get("size").asLong, artifact.get("sha256").asString)
                }.getOrNull()
            }.orEmpty()
            TaskResult(if (hostStatus == "completed" && executionFailed) "failed" else hostStatus,
                answer.replace(Regex("<details>.*?</details>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "").trim(),
                artifacts, row.get("artifacts_omitted")?.asInt ?: 0)
        }
    }

    suspend fun submitTask(body: com.google.gson.JsonObject, connection: Connection) = taskRequest("/api/mobile/tasks", connection, body.toString())
    suspend fun readTask(id: String, connection: Connection) = taskRequest("/api/mobile/tasks/$id", connection)
    suspend fun cancelTask(id: String, connection: Connection) = taskRequest("/api/mobile/tasks/$id/cancel", connection, "{}")

    fun taskBody(member: DotMember, sessionId: String, history: List<GhostChatMessage>, environment: DotEnvironment): com.google.gson.JsonObject {
        val host = UUID.nameUUIDFromBytes(DotEnvironmentStore.address(environment).trim().trimEnd('/').toByteArray())
        val key = "dot_runtime_conversation_${environment.id}_${host}_$sessionId"
        val conversationId = KVUtils.getString(key, "").ifBlank { UUID.randomUUID().toString().also { KVUtils.putString(key, it) } }
        return gson.toJsonTree(mapOf(
            "request_id" to UUID.randomUUID().toString(),
            "model" to "echo-ai", "agent" to member.agentId, "conversation_id" to conversationId,
            "messages" to history.takeLast(24).map { mapOf("role" to it.role, "content" to it.text) },
            "context" to mapOf("agent" to member.agentId, "page_agent_memory_mode" to "write_allowed", "interaction_mode" to "chat"),
            "stream" to false,
        )).asJsonObject
    }

    suspend fun chat(member: DotMember, sessionId: String, history: List<GhostChatMessage>, environment: DotEnvironment): String = withContext(Dispatchers.IO) {
        // The gateway falls back for unknown agents, so check availability before sending.
        require(member.agentId in installedAgentIds(environment)) { "Dot is not installed on this Echo" }
        // A conversation on one host must not reuse another host's runtime workspace.
        val host = UUID.nameUUIDFromBytes(DotEnvironmentStore.address(environment).trim().trimEnd('/').toByteArray())
        val key = "dot_runtime_conversation_${environment.id}_${host}_$sessionId"
        val conversationId = KVUtils.getString(key, "").ifBlank {
            UUID.randomUUID().toString().also { KVUtils.putString(key, it) }
        }
        val body = gson.toJson(mapOf(
            "model" to "echo-ai", "agent" to member.agentId,
            "conversation_id" to conversationId,
            "messages" to history.takeLast(24).map { mapOf("role" to it.role, "content" to it.text) },
            "context" to mapOf("agent" to member.agentId, "page_agent_memory_mode" to "write_allowed", "interaction_mode" to "chat"),
            "stream" to false,
        ))
        val response = JsonParser.parseString(execute(request("/v1/chat/completions", environment)
            .post(body.toRequestBody(jsonType)).build())).asJsonObject
        val content = response.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")?.get("content")?.asString.orEmpty()
        content.replace(Regex("<details>.*?</details>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .trim().takeIf { it.isNotBlank() } ?: error("Empty Dot response")
    }
}
