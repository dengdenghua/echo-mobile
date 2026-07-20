package com.apk.claw.android.octopus_mobile

/**
 * Octopus Mobile 协议定义 —— JSON-RPC 2.0 envelope (Kotlin data class)
 *
 * 完整规范见：
 *  - octopus-agent 仓库：docs/mobile/protocol.md
 *  - Runtime 侧：runtime/tentacle/apks/tool_bridge.py (Envelope)
 *
 * Phase 0 占位实现 —— Phase 1 接入真实 OkHttp WebSocket 客户端。
 *
 * ⚠️ 本文件不依赖任何 Octopus Mobile 现有代码，是 add-only 新增。
 */
sealed class Envelope {
    abstract fun toJson(): String

    /** JSON-RPC request 形如：{"jsonrpc":"2.0","method":"...","params":{...},"id":"..."} */
    data class Request(
        val method: String,
        val params: Map<String, Any?> = emptyMap(),
        val id: String = java.util.UUID.randomUUID().toString()
    ) : Envelope() {
        override fun toJson(): String {
            val paramsJson = params.entries.joinToString(",") { (k, v) ->
                "\"$k\":${JsonValue.encode(v)}"
            }
            return """{"jsonrpc":"2.0","method":"$method","params":{$paramsJson},"id":"$id"}"""
        }
    }

    /** JSON-RPC reply 形如：{"jsonrpc":"2.0","id":"...","result":{...}} 或带 error */
    data class Reply(
        val id: String,
        val result: Any? = null,
        val error: ErrorBody? = null
    ) : Envelope() {
        override fun toJson(): String {
            return if (error != null) {
                """{"jsonrpc":"2.0","id":"$id","error":${error.toJson()}}"""
            } else {
                """{"jsonrpc":"2.0","id":"$id","result":${JsonValue.encode(result)}}"""
            }
        }
    }
}

data class ErrorBody(
    val code: Int,
    val message: String,
    val data: Any? = null
) {
    fun toJson(): String {
        val dataPart = if (data != null) """, "data":${JsonValue.encode(data)}""" else ""
        return """{"code":$code,"message":"${JsonValue.escapeString(message)}"$dataPart}"""
    }
}

/** 错误码常量（与 Runtime 侧 ErrorCode 一一对应）*/
object ErrorCodes {
    const val COORDINATE_OUT_OF_BOUNDS = -32001
    const val TOOL_TIMEOUT = -32002
    const val TOOL_NOT_FOUND = -32003
    const val APP_NOT_FOUND = -32004
    const val PERMISSION_DENIED = -32005
    const val DEVICE_LOCKED = -32010
    const val DEVICE_OFFLINE = -32011
    const val SKILL_INSTALL_FAILED = -32020
    const val CONFIG_CONFLICT = -32030
}

/** 协议版本（与 OCTOPUS_MOBILE_VERSION.protocol_version 一致）*/
object ProtocolVersion {
    const val CURRENT = "1.0"
}

/** 远程任务执行结果 */
sealed class RemoteTaskResult {
    data class Success(
        val steps: Int,
        val response: String,
        val usage: TokenUsage
    ) : RemoteTaskResult()

    data class Failure(
        val error: String
    ) : RemoteTaskResult()
}

/** 极简 JSON 编码器（Phase 0 占位；Phase 1 替换为 Moshi/Gson） */
object JsonValue {
    fun encode(value: Any?): String {
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Int -> value.toString()
            is Long -> value.toString()
            is Double -> value.toString()
            is String -> "\"${escapeString(value)}\""
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${encode(v)}"
            }
            is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
            else -> "\"${escapeString(value.toString())}\""
        }
    }

    fun escapeString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/** 便捷构造器（与 Runtime 侧 hello/heartbeat/tool_execute 对应） */
object EnvelopeFactory {

    /** device/hello —— 协议握手 */
    fun hello(
        tentacleId: String,
        deviceMeta: Map<String, Any?>,
        capabilities: List<String>,
        authToken: String? = null,
        nonce: String? = null
    ): Envelope.Request = Envelope.Request(
        method = "device/hello",
        params = buildMap {
            put("protocol_version", ProtocolVersion.CURRENT)
            put("client_type", "android_tentacle")
            put("client_version", "0.1.0")
            put("tentacle_id", tentacleId)
            put("device_meta", deviceMeta)
            put("capabilities", capabilities)
            put("auth_token", authToken ?: "")
            if (nonce != null) put("nonce", nonce)
        }
    )

    /** device/heartbeat —— 心跳 */
    fun heartbeat(
        tentacleId: String,
        currentApp: String?,
        battery: Int,
        isCharging: Boolean,
        screenTreeHash: String?
    ): Envelope.Request = Envelope.Request(
        method = "device/heartbeat",
        params = mapOf(
            "tentacle_id" to tentacleId,
            "ts" to System.currentTimeMillis(),
            "online" to true,
            "current_app" to currentApp,
            "battery" to battery,
            "is_charging" to isCharging,
            "last_screen_tree_hash" to screenTreeHash
        )
    )

    /** device/screen_changed —— 屏幕状态变化 */
    fun screenChanged(
        tentacleId: String,
        currentApp: String,
        screenHash: String,
        treeDelta: Map<String, Any?> = emptyMap()
    ): Envelope.Request = Envelope.Request(
        method = "device/screen_changed",
        params = mapOf(
            "tentacle_id" to tentacleId,
            "ts" to System.currentTimeMillis(),
            "current_app" to currentApp,
            "screen_tree_hash" to screenHash,
            "tree_delta" to treeDelta
        )
    )

    // ── workspace 协议(workspace/sync 系列) ──────────────────────

    /** workspace/sync_ack —— 移动端收到母体 workspace/sync 后的确认 */
    fun workspaceSyncAck(
        workspaceId: String,
        mountId: String,
        status: String,  // ok / mount_failed / invalid_type / missing_host
        message: String,
    ): Envelope.Request = Envelope.Request(
        method = "workspace/sync_ack",
        params = mapOf(
            "workspace_id" to workspaceId,
            "mount_id" to mountId,
            "status" to status,
            "message" to message,
            "ts" to System.currentTimeMillis(),
        )
    )

    /** workspace/lease_acquire —— 移动端推送前向母体申请租约(防多端同时推送) */
    fun workspaceLeaseAcquire(
        leaseId: String,
        workspaceId: String,
        path: String,
        tentacleId: String,
    ): Envelope.Request = Envelope.Request(
        method = "workspace/lease_acquire",
        params = mapOf(
            "lease_id" to leaseId,
            "workspace_id" to workspaceId,
            "path" to path,
            "tentacle_id" to tentacleId,
            "ts" to System.currentTimeMillis(),
        )
    )

    /** workspace/lease_release —— 推送完成后释放租约 */
    fun workspaceLeaseRelease(
        leaseId: String,
        workspaceId: String,
        path: String,
        tentacleId: String,
    ): Envelope.Request = Envelope.Request(
        method = "workspace/lease_release",
        params = mapOf(
            "lease_id" to leaseId,
            "workspace_id" to workspaceId,
            "path" to path,
            "tentacle_id" to tentacleId,
        )
    )

    /** workspace/push_notify —— 通知母体文件已推送(协作通知) */
    fun workspacePushNotify(
        workspaceId: String,
        path: String,
        mountId: String,
        tentacleId: String,
    ): Envelope.Request = Envelope.Request(
        method = "workspace/push_notify",
        params = mapOf(
            "workspace_id" to workspaceId,
            "path" to path,
            "mount_id" to mountId,
            "tentacle_id" to tentacleId,
            "ts" to System.currentTimeMillis(),
        )
    )

    /** tool/result —— 工具执行结果（reply to tool/execute） */
    fun toolResult(
        callId: String,
        success: Boolean,
        data: Any? = null,
        errorCode: Int? = null,
        errorMessage: String? = null,
        durationMs: Int = 0,
        screenHashAfter: String? = null
    ): Envelope.Reply = if (success) {
        Envelope.Reply(
            id = callId,
            result = mapOf(
                "success" to true,
                "data" to (data ?: ""),
                "duration_ms" to durationMs
            ).let {
                if (screenHashAfter != null) it + ("screen_hash_after" to screenHashAfter) else it
            }
        )
    } else {
        Envelope.Reply(
            id = callId,
            error = ErrorBody(
                code = errorCode ?: -32603,
                message = errorMessage ?: "Unknown error"
            )
        )
    }
}
