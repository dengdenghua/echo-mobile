package com.apk.claw.android.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * MCP 会话 —— 每个传输连接(stdio / SSE / HTTP 长连接)对应一个 session 实例。
 *
 * 职责:
 *  1. 持有连接级状态([initialized] / [protocolVersion])
 *  2. 把原始文本(JSON-RPC over-the-wire)解析为 [JsonRpcRequest]
 *  3. 把分发后的 [JsonRpcResponse] 序列化为 JSON 文本
 *  4. 强制握手顺序:initialize 前调用其他方法 → -32002 INITIALIZE_REQUIRED
 *  5. JSON 解析失败 → -32700 PARSE_ERROR
 *  6. 请求格式错误(缺 method) → -32600 INVALID_REQUEST
 *
 * 纯逻辑组件,无 Android / IO 依赖。
 */
class McpSession {

    /** 是否已完成 initialize 握手。 */
    @Volatile
    var initialized: Boolean = false
        private set

    /** 客户端在 initialize 中声明的 protocolVersion;null 表示尚未握手。 */
    @Volatile
    var protocolVersion: String? = null
        private set

    private val gson = Gson()

    /**
     * 处理一条原始 JSON-RPC 文本。
     *
     * @param rawText 客户端发来的整条 JSON 文本
     * @param dispatcher JSON-RPC 分发器
     * @return JSON 响应文本;返回 null 表示这是通知(id=null),不应回包给客户端
     */
    fun handleMessage(rawText: String, dispatcher: JsonRpcDispatcher): String? {
        // 1. JSON 解析
        val root = try {
            JsonParser.parseString(rawText)
        } catch (e: Exception) {
            return errorEnvelope(null, JsonRpcErrors.PARSE_ERROR, "Parse error: ${e.message}")
        }

        if (!root.isJsonObject) {
            return errorEnvelope(null, JsonRpcErrors.INVALID_REQUEST, "Invalid request: not a JSON object")
        }
        val obj = root.asJsonObject

        // 2. 解析 JSON-RPC 字段
        val id: Any? = when {
            !obj.has("id") || obj.get("id").isJsonNull -> null
            else -> {
                val idEl = obj.get("id")
                if (idEl.isJsonPrimitive) {
                    val p = idEl.asJsonPrimitive
                    when {
                        p.isNumber -> p.asInt
                        p.isString -> p.asString
                        else -> idEl.toString()
                    }
                } else {
                    idEl.toString()
                }
            }
        }

        val method = obj.get("method")?.let { if (it.isJsonPrimitive) it.asString else null }
            ?: return errorEnvelope(id, JsonRpcErrors.INVALID_REQUEST, "Invalid request: missing 'method'")

        val params: JsonObject? = obj.getAsJsonObject("params")

        // 3. 握手顺序检查:initialize / notifications/initialized / ping 之外的方法,必须先 initialize
        val isNotification = id == null
        if (!initialized && method != "initialize" &&
            method != "notifications/initialized" && method != "ping"
        ) {
            // 通知不回包;有 id 的请求回错误
            return if (isNotification) {
                null
            } else {
                errorEnvelope(id, JsonRpcErrors.INITIALIZE_REQUIRED, "initialize required")
            }
        }

        // 4. 分发
        val request = JsonRpcRequest(
            jsonrpc = obj.get("jsonrpc")?.let { if (it.isJsonPrimitive) it.asString else "2.0" } ?: "2.0",
            id = id,
            method = method,
            params = params,
        )
        val response = dispatcher.dispatch(request)

        // 5. 副作用:握手成功后标记 session 为已初始化
        if (method == "initialize" && response.error == null) {
            initialized = true
            // 客户端声明的 protocolVersion 在 params 中(如有);我们采用服务端响应里的版本
            protocolVersion = response.result?.get("protocolVersion")?.let {
                if (it.isJsonPrimitive) it.asString else null
            }
        }

        // 6. 通知(id=null)→ 不回包
        if (isNotification) {
            return null
        }

        // 7. 序列化响应
        return gson.toJson(responseToJson(response))
    }

    /** 把 [JsonRpcResponse] 转成可直接被 Gson 序列化的 Map(确保 id 类型正确)。 */
    private fun responseToJson(resp: JsonRpcResponse): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["jsonrpc"] = resp.jsonrpc
        map["id"] = resp.id
        resp.result?.let { map["result"] = it }
        resp.error?.let { err ->
            val errMap = LinkedHashMap<String, Any?>()
            errMap["code"] = err.code
            errMap["message"] = err.message
            err.data?.let { d -> errMap["data"] = d }
            map["error"] = errMap
        }
        return map
    }

    /** 生成一条 JSON-RPC 错误响应文本。 */
    private fun errorEnvelope(id: Any?, code: Int, message: String): String {
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            when (id) {
                is Int -> addProperty("id", id)
                is String -> addProperty("id", id)
                else -> add("id", com.google.gson.JsonNull.INSTANCE) // 包括 null
            }
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
        return gson.toJson(envelope)
    }
}
