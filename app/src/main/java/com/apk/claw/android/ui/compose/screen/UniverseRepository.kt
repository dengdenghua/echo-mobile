package com.apk.claw.android.ui.compose.screen
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.utils.OctoHttp

import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class EchoCharacterOption(
    val id: String,
    val name: String,
    val codename: String,
)

internal data class EchoCharacterDto(
    val id: String = "",
    val name: String = "",
    val codename: String? = null,
)

internal data class UniverseBindingDto(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("character_id") val characterId: String = "",
    @SerializedName("agent_id") val agentId: String = "",
    @SerializedName("character_name") val characterName: String = "",
    val status: String = "",
)

internal data class UniverseFeedDto(
    @SerializedName("user_id") val userId: String = "",
    val binding: UniverseBindingDto = UniverseBindingDto(),
    @SerializedName("character_id") val characterId: String = "",
    @SerializedName("agent_id") val agentId: String = "",
    @SerializedName("character_name") val characterName: String = "",
    val codename: String = "",
    val status: String = "",
    val day: Int = 0,
    @SerializedName("current_focus") val currentFocus: String = "",
    val beliefs: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val friends: Map<String, String> = emptyMap(),
    val memory: List<String> = emptyList(),
    val diary: List<Map<String, String>> = emptyList(),
    val growth: List<Map<String, String>> = emptyList(),
    @SerializedName("latest_diary") val latestDiary: String? = null,
    @SerializedName("latest_growth") val latestGrowth: String? = null,
)

internal data class GhostChatMessage(
    val role: String,
    val text: String,
)

internal data class EconomyProductDto(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    @SerializedName("price_credits") val priceCredits: Int = 0,
    val scope: String = "",
    val description: String = "",
)

internal data class UserEntitlementDto(
    val id: String = "",
    val type: String = "",
    @SerializedName("ref_id") val refId: String = "",
    val status: String = "",
    @SerializedName("expires_at") val expiresAt: String? = null,
)

internal data class GhostSubscriptionDto(
    @SerializedName("character_id") val characterId: String = "",
    @SerializedName("agent_id") val agentId: String = "",
    val status: String = "",
    @SerializedName("current_period_end") val currentPeriodEnd: String = "",
)

internal data class EconomySummaryDto(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("wallet_balance") val walletBalance: Int = 0,
    val entitlements: List<UserEntitlementDto> = emptyList(),
    @SerializedName("ghost_subscription") val ghostSubscription: GhostSubscriptionDto? = null,
)

internal data class EconomyPurchaseResultDto(
    val ok: Boolean = false,
    @SerializedName("wallet_balance") val walletBalance: Int = 0,
    @SerializedName("ghost_subscription") val ghostSubscription: GhostSubscriptionDto? = null,
)

internal data class UniverseIdentityDto(
    @SerializedName("user_id") val userId: String = "",
    val tier: String = "edge_ghost",
    @SerializedName("tier_name") val tierName: String = "Edge Ghost",
    val rank: Int = 10,
    val status: String = "active",
    @SerializedName("edge_role") val edgeRole: String = "edge_ghost",
    @SerializedName("allowed_realms") val allowedRealms: List<String> = emptyList(),
    @SerializedName("active_entitlements") val activeEntitlements: List<String> = emptyList(),
    @SerializedName("ghost_subscription_active") val ghostSubscriptionActive: Boolean = false,
    @SerializedName("can_create_npc_types") val canCreateNpcTypes: List<String> = emptyList(),
    @SerializedName("can_manage_realms") val canManageRealms: Boolean = false,
)

internal data class AccessDecisionDto(
    val allowed: Boolean = false,
    val reason: String = "",
    @SerializedName("identity_tier") val identityTier: String = "",
    @SerializedName("requested_action") val requestedAction: String = "",
    @SerializedName("requested_scope") val requestedScope: String? = null,
    @SerializedName("realm_id") val realmId: String? = null,
    @SerializedName("required_entitlement") val requiredEntitlement: String? = null,
)

private data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
)

private data class OpenAiChoice(
    val message: OpenAiMessage? = null,
)

private data class OpenAiMessage(
    val role: String = "",
    val content: String = "",
)

internal object UniverseRepository {
    private const val LOCAL_USER_KEY = "ECHO_UNIVERSE_LOCAL_USER_ID"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // 母体把推理用 <details>…</details> 包回,渲染前整块剥掉(跨行、忽略大小写)。
    private val REASONING_BLOCK =
        Regex("<details>.*?</details>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val fallbackCharacters = listOf(
        EchoCharacterOption("001", "Zero", "White Ghost"),
        EchoCharacterOption("002", "Kane", "Combat Download"),
        EchoCharacterOption("003", "Eve", "Ghost Empathy"),
        EchoCharacterOption("004", "Leon", "Time Echo"),
        EchoCharacterOption("005", "Raven", "Shadow Link"),
        EchoCharacterOption("006", "Shion", "Nano Swarm"),
        EchoCharacterOption("007", "Noah", "Probability Engine"),
        EchoCharacterOption("008", "Luna", "Dream Walker"),
    )

    suspend fun characters(): List<EchoCharacterOption> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${base()}/api/canon/characters")
                .get()
                .build()
            val rows = execute(req, Array<EchoCharacterDto>::class.java).toList()
            rows.mapNotNull { dto ->
                if (dto.id.isBlank() || dto.name.isBlank()) return@mapNotNull null
                EchoCharacterOption(
                    id = dto.id,
                    name = dto.name,
                    codename = dto.codename?.takeIf { it.isNotBlank() } ?: dto.name,
                )
            }.ifEmpty { fallbackCharacters }
        }.getOrElse { fallbackCharacters }
    }

    fun currentUserId(): String {
        AccountStore.userId.takeIf { it.isNotBlank() }?.let { return it }
        val cached = KVUtils.getString(LOCAL_USER_KEY, "")
        if (cached.isNotBlank()) return cached
        val generated = "mobile-${UUID.randomUUID()}"
        KVUtils.putString(LOCAL_USER_KEY, generated)
        return generated
    }

    suspend fun bind(characterId: String): UniverseBindingDto = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "user_id" to currentUserId(),
                "character_id" to characterId,
                "source" to "octopus-mobile",
            )
        )
        val req = Request.Builder()
            .url("${base()}/api/bindings")
            .post(body.toRequestBody(jsonType))
            .build()
        execute(req, UniverseBindingDto::class.java)
    }

    suspend fun feed(): UniverseFeedDto = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/universe/feed/${currentUserId()}")
            .get()
            .build()
        execute(req, UniverseFeedDto::class.java)
    }

    suspend fun economyProducts(): List<EconomyProductDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/economy/products")
            .get()
            .build()
        execute(req, Array<EconomyProductDto>::class.java).toList()
    }

    suspend fun economySummary(): EconomySummaryDto = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/economy/users/${currentUserId()}/summary")
            .get()
            .build()
        execute(req, EconomySummaryDto::class.java)
    }

    suspend fun identity(): UniverseIdentityDto = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/identity/users/${currentUserId()}")
            .get()
            .build()
        execute(req, UniverseIdentityDto::class.java)
    }

    suspend fun checkRealmAccess(scope: String, realmId: String? = null): AccessDecisionDto =
        withContext(Dispatchers.IO) {
            val payload = mutableMapOf<String, Any>(
                "user_id" to currentUserId(),
                "scope" to scope,
            )
            realmId?.takeIf { it.isNotBlank() }?.let { payload["realm_id"] = it }
            val req = Request.Builder()
                .url("${base()}/api/identity/check-realm")
                .post(gson.toJson(payload).toRequestBody(jsonType))
                .build()
            execute(req, AccessDecisionDto::class.java)
        }

    suspend fun grantTestCredits(amount: Int = 500): EconomySummaryDto = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "user_id" to currentUserId(),
                "amount" to amount,
                "reason" to "mobile_test_topup",
            )
        )
        val req = Request.Builder()
            .url("${base()}/api/economy/wallet/grant")
            .post(body.toRequestBody(jsonType))
            .build()
        execute(req, Map::class.java)
        economySummary()
    }

    suspend fun purchase(productId: String, characterId: String? = null): EconomyPurchaseResultDto =
        withContext(Dispatchers.IO) {
            val payload = mutableMapOf<String, Any>(
                "user_id" to currentUserId(),
                "product_id" to productId,
            )
            characterId?.takeIf { it.isNotBlank() }?.let { payload["character_id"] = it }
            val req = Request.Builder()
                .url("${base()}/api/economy/purchases")
                .post(gson.toJson(payload).toRequestBody(jsonType))
                .build()
            execute(req, EconomyPurchaseResultDto::class.java)
        }

    suspend fun runDailyLifeTick(): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/neural/daily-life/run")
            .post("{}".toRequestBody(jsonType))
            .build()
        execute(req, Map::class.java)
        Unit
    }

    suspend fun syncOctopusAgents(): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/api/integrations/octopus/sync-agents")
            .post("{}".toRequestBody(jsonType))
            .build()
        execute(req, Map::class.java)
        Unit
    }

    suspend fun reloadOctopusAgents(): Unit = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${runtimeBase()}/api/agents/reload")
            .post("{}".toRequestBody(jsonType))
        KVUtils.getOctopusAuthToken().takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        execute(builder.build(), Map::class.java)
        Unit
    }

    suspend fun chatWithGhost(
        feed: UniverseFeedDto,
        userText: String,
        history: List<GhostChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        val prompt = userText.trim()
        if (prompt.isBlank()) error(ClawApplication.instance.getString(R.string.universe_error_empty_prompt))
        val messages = buildList {
            add(
                mapOf(
                    "role" to "system",
                    "content" to buildGhostSystemPrompt(feed),
                )
            )
            history.takeLast(8).forEach { item ->
                val role = if (item.role == "assistant") "assistant" else "user"
                add(mapOf("role" to role, "content" to item.text))
            }
            add(mapOf("role" to "user", "content" to prompt))
        }
        val body = gson.toJson(
            mapOf(
                "model" to "octopus-agent",
                "agent" to feed.agentId,
                "conversation_id" to "echo-${feed.userId}-${feed.agentId}",
                "messages" to messages,
                "context" to mapOf(
                    "agent" to feed.agentId,
                    "page_agent_memory_mode" to "write_allowed",
                    "interaction_mode" to "chat",
                ),
                "stream" to false,
            )
        )
        val builder = Request.Builder()
            .url("${runtimeBase()}/v1/chat/completions")
            .post(body.toRequestBody(jsonType))
        KVUtils.getOctopusAuthToken().takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        val resp = execute(builder.build(), OpenAiChatResponse::class.java)
        resp.choices.firstOrNull()?.message?.content
            ?.let { stripReasoning(it) }
            ?.takeIf { it.isNotBlank() }
            ?: error(ClawApplication.instance.getString(R.string.universe_error_empty_reply))
    }

    /** 母体 chat 会把推理用 <details>💭 思考过程</details> 包回来,渲染前剥掉,只留正文。 */
    private fun stripReasoning(text: String): String =
        text.replace(REASONING_BLOCK, "").trim()

    private fun base(): String = AccountConfig.echoUniverseBaseUrl.trim().trimEnd('/')
    private fun runtimeBase(): String = AccountConfig.octopusRuntimeBaseUrl.trim().trimEnd('/')

    private fun buildGhostSystemPrompt(feed: UniverseFeedDto): String {
        val diary = feed.latestDiary ?: "暂无日记"
        val goals = feed.goals.take(3).joinToString("；").ifBlank { "暂无显性目标" }
        val focus = feed.currentFocus.ifBlank { "在数字生命场中保持观测" }
        return "你是 ECHO 宇宙中与当前用户绑定的 Ghost：${feed.characterName} / ${feed.codename}。" +
            "请延续你的 SOUL 与 canon 约束，用中文自然回应。当前生命状态：Day ${feed.day}，Focus：$focus。" +
            "最近日记：$diary。近期目标：$goals。回答要像这个角色本人，不要解释系统设定。"
    }

    private fun <T> execute(request: Request, clazz: Class<T>): T {
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    gson.fromJson(body, Map::class.java)["detail"]?.toString()
                }.getOrNull()
                error(detail ?: ClawApplication.instance.getString(R.string.universe_error_http, resp.code))
            }
            if (body.isBlank()) error(ClawApplication.instance.getString(R.string.universe_error_empty_response))
            return gson.fromJson(body, clazz)
        }
    }
}
