package com.apk.claw.android.octopus_mobile

import androidx.annotation.StringRes
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
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
        val socket = before.socket
            ?: error(text(R.string.devicelink_err_not_connected, "Connect to the device hub first."))
        check(before.online) { text(R.string.devicetasks_error_hub_not_connected, "The device hub is not connected.") }
        val id = "$prefix-${UUID.randomUUID()}"
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        return try {
            // Close the registration race with a simultaneous connection change.
            val current = snapshot()
            check(current.generation == before.generation && current.socket === socket && current.online) {
                text(R.string.devicelink_err_connection_changed, "The connection changed. Reload the task status.")
            }
            check(socket.send(Envelope.Request(method = method, id = id, params = args).toJson())) {
                text(R.string.devicelink_err_disconnected, "The connection dropped.")
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
                        ?.get("message")?.asString
                        ?: text(R.string.devicelink_err_refused, "The device hub refused this action."),
                ),
            )
            result?.isJsonObject == true -> reply.complete(result.asJsonObject)
            else -> reply.completeExceptionally(
                IllegalStateException(
                    text(R.string.devicelink_err_bad_reply, "The device hub sent an unreadable reply."),
                ),
            )
        }
        return true
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 35_000L

        /** These messages reach the device-tasks screen; [fallback] covers JVM tests, where there is no app. */
        fun text(@StringRes id: Int, fallback: String): String =
            runCatching { ClawApplication.instance.getString(id) }.getOrDefault(fallback)
    }
}
