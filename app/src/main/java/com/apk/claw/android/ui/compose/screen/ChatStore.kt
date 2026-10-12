package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 单个会话的消息持久化（MMKV，按 sessionId 分键）。
 *
 * 「Thinking」是运行期占位，不持久化。每个会话最多保留最近 [MAX_KEEP] 条。
 *
 * Artifact 消息的大 payload(HTML/图片 base64)存到独立的 MMKV 键([payloadKey]),
 * 主消息列表只存引用键 + 元信息,避免主列表臃肿。
 */
object ChatStore {

    private const val PREFIX = "chat_msgs_"
    private const val PAYLOAD_PREFIX = "chat_artifact_"
    private const val MAX_KEEP = 100
    private val gson = Gson()

    private data class Dto(
        val type: String,
        val a: String = "", val b: String = "", val c: String = "", val d: String = "",
        val e: String = "",
        val callId: String? = null,
        val batchId: String? = null,
        val status: String? = null,
        val durationMs: Long? = null,
        val remoteTaskId: String? = null,
        val requestId: String? = null,
        val delivery: String? = null,
        val deliveryFailure: String? = null,
        val messageId: Long? = null,
    )

    private fun key(sessionId: String) = PREFIX + sessionId

    /** Artifact payload 的存储键(全局唯一,跨会话不冲突,因 refId 含 sessionId+timestamp)。 */
    fun payloadKey(refId: String) = PAYLOAD_PREFIX + refId

    /** 存一个 artifact 的 payload(HTML 字符串 / 图片 base64 / diff 文本)。 */
    fun savePayload(refId: String, payload: String) {
        runCatching { KVUtils.putString(payloadKey(refId), payload) }
    }

    /** 读一个 artifact 的 payload。 */
    fun loadPayload(refId: String): String? =
        KVUtils.getString(payloadKey(refId), "").takeIf { it.isNotBlank() }

    /** 删除一个 artifact 的 payload。 */
    fun clearPayload(refId: String) {
        runCatching { KVUtils.putString(payloadKey(refId), "") }
    }

    fun save(sessionId: String, messages: List<ChatMessage>) {
        messages.dropLast(MAX_KEEP).filterIsInstance<ChatMessage.UserMessage>().forEach {
            it.requestId?.let { id -> KVUtils.putString(rejectedDeliveryKey(sessionId, id), "") }
        }
        runCatching { KVUtils.putString(key(sessionId), encode(messages)) }
    }

    internal fun encode(messages: List<ChatMessage>): String {
        val dtos = messages.takeLast(MAX_KEEP).mapNotNull { m ->
            val dto = when (m) {
                is ChatMessage.UserMessage -> Dto("user", m.text, requestId = m.requestId,
                    delivery = m.delivery?.name, deliveryFailure = m.deliveryFailure?.name)
                is ChatMessage.AgentMessage -> Dto("agent", m.text, remoteTaskId = m.remoteTaskId)
                is ChatMessage.ToolCall -> Dto("tool", m.icon, m.toolName, m.args, m.result ?: "",
                    callId = m.callId, batchId = m.batchId, status = m.status.name, durationMs = m.durationMs)
                is ChatMessage.Thinking -> null
                is ChatMessage.Artifact -> Dto(
                    "artifact",
                    m.kind.name,
                    m.title,
                    m.payloadRef,
                )
            }
            dto?.copy(messageId = m.id)
        }
        return gson.toJson(dtos)
    }

    fun load(sessionId: String): List<ChatMessage>? {
        val json = KVUtils.getString(key(sessionId), "")
        return decode(json)
    }

    internal fun decode(json: String): List<ChatMessage>? {
        if (json.isBlank()) return null
        return runCatching {
            val dtos: List<Dto> = gson.fromJson(json, object : TypeToken<List<Dto>>() {}.type)
            reserveMessageIds(dtos.mapNotNull { it.messageId }.filter { it > 0 }.maxOrNull() ?: 0)
            dtos.map { d ->
                val id = d.messageId?.takeIf { it > 0 } ?: nextId()
                when (d.type) {
                    "user" -> ChatMessage.UserMessage(d.a, id = id, requestId = d.requestId,
                        delivery = runCatching { MessageDeliveryStatus.valueOf(d.delivery.orEmpty()) }.getOrNull(),
                        deliveryFailure = runCatching {
                            MessageDeliveryFailure.valueOf(d.deliveryFailure.orEmpty())
                        }.getOrNull())
                    "tool" -> ChatMessage.ToolCall(d.a, d.b, d.c, d.d.ifBlank { null },
                        id = id,
                        callId = d.callId, batchId = d.batchId, durationMs = d.durationMs,
                        status = restoredToolStatus(d.status))
                    "artifact" -> ChatMessage.Artifact(
                        kind = runCatching { ChatMessage.ArtifactKind.valueOf(d.a) }
                            .getOrDefault(ChatMessage.ArtifactKind.FILE),
                        title = d.b,
                        payloadRef = d.c,
                        id = id,
                    )
                    else -> ChatMessage.AgentMessage(d.a, id = id, remoteTaskId = d.remoteTaskId)
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun hasSaved(sessionId: String): Boolean = KVUtils.getString(key(sessionId), "").isNotBlank()

    fun clear(sessionId: String) {
        ConversationReadingStore.clear(sessionId)
        load(sessionId)?.filterIsInstance<ChatMessage.UserMessage>()?.forEach {
            it.requestId?.let { id -> KVUtils.putString(rejectedDeliveryKey(sessionId, id), "") }
        }
        runCatching { KVUtils.putString(key(sessionId), "[]") }
    }
}

/** A persisted running task has no attached live execution after recreation. */
internal fun restoredToolStatus(value: String?): ChatMessage.ToolCallStatus =
    runCatching { ChatMessage.ToolCallStatus.valueOf(value ?: "SUCCESS") }
        .getOrDefault(ChatMessage.ToolCallStatus.SUCCESS)
        .let { if (it == ChatMessage.ToolCallStatus.RUNNING) ChatMessage.ToolCallStatus.CANCELLED else it }
