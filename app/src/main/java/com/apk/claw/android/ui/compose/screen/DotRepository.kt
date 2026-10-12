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
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val rosterClient = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun validBaseUrl(value: String): Boolean = value.trim().toHttpUrlOrNull()?.let {
        it.scheme in listOf("http", "https") && it.encodedPath == "/" && it.query == null &&
            it.fragment == null && it.username.isEmpty() && it.password.isEmpty()
    } == true

    private fun request(path: String, environment: DotEnvironment): Request.Builder {
        require(environment != DotEnvironment.PHONE) { "Phone tasks use the local agent" }
        val base = DotEnvironmentStore.address(environment).trim().trimEnd('/')
        require(validBaseUrl(base)) { "Invalid Echo address" }
        return Request.Builder().url(base + path).apply {
            DotEnvironmentStore.token(environment).takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
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
