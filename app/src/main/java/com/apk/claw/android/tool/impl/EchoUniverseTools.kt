package com.apk.claw.android.tool.impl
import com.apk.claw.android.utils.OctoHttp

import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.tool.ToolErr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Echo Universe 工具集：让 Octopus agent 能感知并影响 Echo 世界。
 *
 * 三个工具：
 *  - echo_observe：读取世界状态（角色/事件/身份/feed）
 *  - echo_act：提交领域事件（影响世界）
 *  - echo_bind：绑定/切换角色身份
 *
 * 数据流：
 *   agent → echo_observe 读世界 → LLM 推理 → echo_act 提交行动
 *        → Echo 引擎 simulate_event → echo_observe 读后果
 */
class EchoUniverseTools {

    // ── HTTP 客户端（与 UniverseRepository 对齐） ──
    private object Net {
        val http: OkHttpClient = OctoHttp.shared.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val gson: Gson = Gson()
        val jsonType = "application/json; charset=utf-8".toMediaType()

        fun base(): String = AccountConfig.echoUniverseBaseUrl.trim().trimEnd('/')

        fun currentUserId(): String {
            AccountStore.userId.takeIf { it.isNotBlank() }?.let { return it }
            val cached = KVUtils.getString(ECHO_LOCAL_USER_KEY, "")
            if (cached.isNotBlank()) return cached
            val generated = "mobile-${UUID.randomUUID()}"
            KVUtils.putString(ECHO_LOCAL_USER_KEY, generated)
            return generated
        }

        suspend fun <T> get(path: String, clazz: Class<T>): T = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("${base()}$path").get().build()
            execute(req, clazz)
        }

        suspend fun <T> post(path: String, body: String, clazz: Class<T>): T = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("${base()}$path").post(body.toRequestBody(jsonType)).build()
            execute(req, clazz)
        }

        private fun <T> execute(request: Request, clazz: Class<T>): T {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching {
                        gson.fromJson(body, Map::class.java)["detail"]?.toString()
                    }.getOrNull()
                    error(detail ?: "ECHO request failed: HTTP ${resp.code}")
                }
                if (body.isBlank()) error("ECHO returned an empty response")
                return gson.fromJson(body, clazz)
            }
        }
    }

    // ── DTO ──
    private data class FeedDto(
        @SerializedName("user_id") val userId: String = "",
        @SerializedName("character_name") val characterName: String = "",
        val codename: String = "",
        val day: Int = 0,
        @SerializedName("current_focus") val currentFocus: String = "",
        val goals: List<String> = emptyList(),
        val memory: List<String> = emptyList(),
        @SerializedName("latest_diary") val latestDiary: String? = null,
    )

    private data class CharacterDto(
        val id: String = "",
        val name: String = "",
        val codename: String? = null,
    )

    private data class JournalEventDto(
        val title: String = "",
        val summary: String = "",
        @SerializedName("event_type") val eventType: String = "",
        @SerializedName("created_at") val createdAt: String = "",
    )

    private data class RealmEventRequest(
        val title: String,
        val summary: String,
        val scope: String = "personal",
        @SerializedName("realm_id") val realmId: String? = null,
        val submitter: String = "octopus-agent",
        val content: String = "",
    )

    private data class RealmEventResponse(
        val title: String = "",
        val summary: String = "",
        @SerializedName("event_type") val eventType: String = "",
        @SerializedName("created_at") val createdAt: String = "",
    )

    private data class BindingDto(
        @SerializedName("user_id") val userId: String = "",
        @SerializedName("character_id") val characterId: String = "",
        @SerializedName("character_name") val characterName: String = "",
        @SerializedName("agent_id") val agentId: String = "",
        val status: String = "",
    )

    /** 工具 1: echo_observe —— 感知 Echo 世界 */
    class ObserveTool : BaseTool() {
        override fun getName() = "echo_observe"

        override fun getDescriptionCN() =
            "感知 ECHO 虚拟世界的当前状态。可读取：自己的角色绑定、世界 feed（角色状态/日记/目标）、" +
                "最近的世界事件编年史、可选 NPC 列表。用于 agent 决定下一步行动前获取上下文。" +
                "返回结构化世界快照。"

        override fun getDescriptionEN() =
            "Observe the ECHO virtual world state. Reads: your character binding, world feed " +
                "(character status/diary/goals), recent journal events, optional NPC list. " +
                "Use before deciding actions to get world context."

        override fun getParameters(): List<ToolParameter> = listOf(
            ToolParameter(
                "what", "string",
                "要观察什么：'feed'(我的世界状态,默认) | 'events'(最近编年史事件) | 'characters'(可绑定角色) | 'npcs'(NPC 列表)",
                isRequired = false,
            ),
            ToolParameter(
                "limit", "integer",
                "返回条数上限（仅对 events/npcs 生效），默认 10",
                isRequired = false,
            ),
        )

        override fun execute(params: Map<String, Any>): ToolResult {
            currentCancellationToken()?.checkCancelled()
            return runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(ECHO_TIMEOUT_MS) {
                    val what = optionalString(params, "what", "feed")
                    val limit = optionalInt(params, "limit", 10)
                    val userId = Net.currentUserId()
                    try {
                        val result = when (what) {
                            "events" -> {
                                val url = "/api/journal/events?limit=$limit"
                                val events = Net.get(url, Array<JournalEventDto>::class.java)
                                buildString {
                                    appendLine("== ECHO 编年史（最近 ${events.size} 条）==")
                                    events.forEachIndexed { i, e ->
                                        appendLine("${i + 1}. [${e.createdAt}] ${e.title}")
                                        if (e.summary.isNotBlank()) appendLine("   ${e.summary.take(200)}")
                                    }
                                }
                            }
                            "characters" -> {
                                val chars = Net.get("/api/canon/characters", Array<CharacterDto>::class.java)
                                buildString {
                                    appendLine("== 可绑定角色（${chars.size}）==")
                                    chars.forEach { c ->
                                        appendLine("- ${c.id} | ${c.name}" + (c.codename?.let { " ($it)" } ?: ""))
                                    }
                                }
                            }
                            "npcs" -> {
                                @Suppress("UNCHECKED_CAST")
                                val npcs = Net.get("/api/npcs?limit=$limit", List::class.java) as List<Map<String, Any>>
                                buildString {
                                    appendLine("== NPC 列表（${npcs.size}）==")
                                    npcs.forEach { n ->
                                        appendLine("- ${n["id"] ?: n["name"]} | ${n["name"]}" +
                                            (n["type"]?.let { " [$it]" } ?: ""))
                                    }
                                }
                            }
                            else -> {
                                val feed = Net.get("/api/universe/feed/$userId", FeedDto::class.java)
                                buildString {
                                    appendLine("== ECHO 世界状态 · Day ${feed.day} ==")
                                    appendLine("角色：${feed.characterName}" +
                                        (feed.codename.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
                                    appendLine("当前焦点：${feed.currentFocus.ifBlank { "无" }}")
                                    if (feed.goals.isNotEmpty()) {
                                        appendLine("近期目标：")
                                        feed.goals.take(3).forEach { appendLine("  - $it") }
                                    }
                                    feed.latestDiary?.takeIf { it.isNotBlank() }?.let {
                                        appendLine("最近日记：${it.take(300)}")
                                    }
                                    if (feed.memory.isNotEmpty()) {
                                        appendLine("记忆碎片：")
                                        feed.memory.take(3).forEach { appendLine("  - ${it.take(120)}") }
                                    }
                                }
                            }
                        }
                        ToolResult.success(result)
                    } catch (e: Exception) {
                        ToolResult.error("感知 ECHO 世界失败：${e.message}", ToolErr.UPSTREAM)
                    }
                } ?: ToolResult.error("感知 ECHO 世界请求超时(15s)", ToolErr.TIMEOUT)
            }
        }
    }

    /** 工具 2: echo_act —— 提交领域事件，影响世界 */
    class ActTool : BaseTool() {
        override fun getName() = "echo_act"

        override fun getDescriptionCN() =
            "向 ECHO 世界提交一个行动/事件，影响世界走向。提交后 Echo 引擎会模拟所有角色反应，" +
                "事件可能进入正史（canon）或仅影响个人（personal）。" +
                "scope='personal' 只影响自己；scope='canon' 影响全员正史（需审核）。" +
                "行动示例：传信给某人、探索某区域、触发某事件、与 NPC 交互。"

        override fun getDescriptionEN() =
            "Submit an action/event to the ECHO world. The engine simulates character reactions. " +
                "scope='personal' affects only you; scope='canon' affects all (requires review)."

        override fun getParameters(): List<ToolParameter> = listOf(
            ToolParameter(
                "title", "string",
                "行动标题（简短，如'传信给零号'）",
                isRequired = true,
            ),
            ToolParameter(
                "summary", "string",
                "行动描述（详细说明发生了什么、为什么）",
                isRequired = true,
            ),
            ToolParameter(
                "scope", "string",
                "影响范围：'personal'(默认,只影响自己) | 'canon'(影响正史,需审核)",
                isRequired = false,
            ),
            ToolParameter(
                "realm_id", "string",
                "在哪个领域发生（可选，如'north'/'south'/'central'）",
                isRequired = false,
            ),
        )

        override fun execute(params: Map<String, Any>): ToolResult {
            currentCancellationToken()?.checkCancelled()
            return runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(ECHO_TIMEOUT_MS) {
                    val title = optionalString(params, "title", "").takeIf { it.isNotBlank() }
                        ?: return@withTimeoutOrNull ToolResult.error("'title' 不能为空", ToolErr.INVALID_PARAM)
                    val summary = optionalString(params, "summary", "").takeIf { it.isNotBlank() }
                        ?: return@withTimeoutOrNull ToolResult.error("'summary' 不能为空", ToolErr.INVALID_PARAM)
                    val scope = optionalString(params, "scope", "personal")
                    val realmId = optionalString(params, "realm_id", "").takeIf { it.isNotBlank() }
                    val userId = Net.currentUserId()
                    try {
                        val req = RealmEventRequest(
                            title = title,
                            summary = summary,
                            scope = scope,
                            realmId = realmId,
                            submitter = "octopus-agent:$userId",
                            content = summary,
                        )
                        val resp = Net.post(
                            "/api/realm-events",
                            Net.gson.toJson(req),
                            RealmEventResponse::class.java,
                        )
                        ToolResult.success(
                            buildString {
                                appendLine("✅ 行动已提交到 ECHO 世界")
                                appendLine("标题：${resp.title}")
                                appendLine("范围：$scope" + (realmId?.let { " · 领域 $it" } ?: ""))
                                appendLine("时间：${resp.createdAt}")
                                appendLine("类型：${resp.eventType}")
                                if (scope == "canon") {
                                    appendLine("⚠️ 正史事件需审核，结果稍后在 echo_observe events 中查看")
                                } else {
                                    appendLine("个人事件已生效，可用 echo_observe feed 查看后续影响")
                                }
                            },
                        )
                    } catch (e: Exception) {
                        ToolResult.error("影响 ECHO 世界失败：${e.message}", ToolErr.UPSTREAM)
                    }
                } ?: ToolResult.error("影响 ECHO 世界请求超时(15s)", ToolErr.TIMEOUT)
            }
        }
    }

    /** 工具 3: echo_bind —— 绑定/切换角色身份 */
    class BindTool : BaseTool() {
        override fun getName() = "echo_bind"

        override fun getDescriptionCN() =
            "在 ECHO 世界绑定或切换角色身份。绑定后 agent 的行动都通过该角色身份发生，" +
                "可读该角色的日记/目标/记忆。用 echo_observe characters 查看可绑定角色列表。"

        override fun getDescriptionEN() =
            "Bind or switch your character identity in the ECHO world. " +
                "After binding, your actions happen through that character. " +
                "Use echo_observe characters to see available characters."

        override fun getParameters(): List<ToolParameter> = listOf(
            ToolParameter(
                "character_id", "string",
                "角色 ID 或名称（如 '001' 或 'Zero'）",
                isRequired = true,
            ),
        )

        override fun execute(params: Map<String, Any>): ToolResult {
            currentCancellationToken()?.checkCancelled()
            return runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(ECHO_TIMEOUT_MS) {
                    val characterId = optionalString(params, "character_id", "").takeIf { it.isNotBlank() }
                        ?: return@withTimeoutOrNull ToolResult.error(
                            "'character_id' 不能为空",
                            ToolErr.INVALID_PARAM,
                        )
                    val userId = Net.currentUserId()
                    try {
                        val body = Net.gson.toJson(
                            mapOf(
                                "user_id" to userId,
                                "character_id" to characterId,
                                "source" to "octopus-agent",
                            ),
                        )
                        val binding = Net.post("/api/bindings", body, BindingDto::class.java)
                        ToolResult.success(
                            buildString {
                                appendLine("✅ 角色绑定成功")
                                appendLine("角色：${binding.characterName}")
                                appendLine("Agent ID：${binding.agentId}")
                                appendLine("状态：${binding.status}")
                                appendLine("现在可以用 echo_observe feed 读取该角色的世界状态")
                            },
                        )
                    } catch (e: Exception) {
                        ToolResult.error("绑定角色失败：${e.message}", ToolErr.UPSTREAM)
                    }
                } ?: ToolResult.error("绑定角色请求超时(15s)", ToolErr.TIMEOUT)
            }
        }
    }

    companion object {
        private const val ECHO_TIMEOUT_MS = 15_000L
        private const val ECHO_LOCAL_USER_KEY = "ECHO_UNIVERSE_LOCAL_USER_ID"

        /** 一次性注册所有 Echo 工具 */
        fun registerAll() {
            val registry = com.apk.claw.android.tool.ToolRegistry.getInstance()
            registry.register(ObserveTool())
            registry.register(ActTool())
            registry.register(BindTool())
        }
    }
}
