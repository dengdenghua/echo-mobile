package com.apk.claw.android.octopus_mobile

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class PairedConnectionSnapshot(
    val socket: WebSocket?,
    val generation: Int,
    val online: Boolean,
)

/** Requests belong to one paired connection; disconnects fail rather than replay them. */
internal class PairedDeviceRpc(private val snapshot: () -> PairedConnectionSnapshot) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    suspend fun request(method: String, args: Map<String, Any?>, prefix: String): JsonObject {
        val before = snapshot()
        val socket = before.socket ?: error("请先连接设备中心")
        check(before.online) { "设备中心尚未连接" }
        val id = "$prefix-${UUID.randomUUID()}"
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        return try {
            // Close the registration race with a simultaneous connection change.
            val current = snapshot()
            check(current.generation == before.generation && current.socket === socket && current.online) {
                "连接已变更，请重新读取任务状态"
            }
            check(socket.send(Envelope.Request(method = method, id = id, params = args).toJson())) {
                "连接已断开"
            }
            withTimeout(REPLY_TIMEOUT_MS) { result.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun failAll(reason: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pending.clear()
    }

    fun handleReply(root: JsonObject): Boolean {
        val reply = if (snapshot().online && (root.has("result") || root.has("error"))) {
            root.get("id")?.asString?.let { pending.remove(it) }
        } else null
        if (reply == null) return false
        val result = root.get("result")
        when {
            root.has("error") -> reply.completeExceptionally(
                IllegalStateException(
                    root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("message")?.asString ?: "设备中心拒绝了此操作",
                ),
            )
            result?.isJsonObject == true -> reply.complete(result.asJsonObject)
            else -> reply.completeExceptionally(IllegalStateException("Invalid peer result"))
        }
        return true
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 35_000L
    }
}
