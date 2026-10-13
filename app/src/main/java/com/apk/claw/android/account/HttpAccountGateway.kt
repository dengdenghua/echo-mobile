package com.apk.claw.android.account
import com.apk.claw.android.utils.OctoHttp

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to octopus's own account server ([AccountConfig.baseUrl]) over JSON.
 * The bearer token (from login) is attached to authenticated calls. This class
 * makes no assumptions about which SMS / payment vendor the server uses — it
 * only speaks the [AccountGateway] JSON contract.
 */
class HttpAccountGateway(baseUrl: String) : AccountGateway {

    private val base = baseUrl.trimEnd('/')
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = base + path

    private suspend fun <T> post(
        path: String,
        body: Any?,
        token: String?,
        clazz: Class<T>,
    ): T = withContext(Dispatchers.IO) {
        val json = gson.toJson(body ?: emptyMap<String, Any>())
        val builder = Request.Builder()
            .url(url(path))
            .post(json.toRequestBody(JSON))
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
        exec(builder.build(), clazz)
    }

    private suspend fun <T> get(
        path: String,
        token: String?,
        clazz: Class<T>,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(path)).get()
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
        exec(builder.build(), clazz)
    }

    private fun <T> exec(req: Request, clazz: Class<T>): T {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AccountHttpException(resp.code, serverDetail(text), text)
            }
            return gson.fromJson(text, clazz)
        }
    }

    /** Pull FastAPI's `{"detail": "..."}` so error toasts show the friendly server message. */
    private fun serverDetail(body: String): String? = runCatching {
        gson.fromJson(body, com.google.gson.JsonObject::class.java)
            ?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    override suspend fun sendSmsCode(mobile: String): SmsSendResult =
        post("/auth/sms/send", mapOf("mobile" to mobile), null, SmsSendResult::class.java)

    override suspend fun login(mobile: String, code: String): LoginResult =
        post("/auth/sms/login", mapOf("mobile" to mobile, "code" to code), null, LoginResult::class.java)

    override suspend fun sendEmailCode(email: String): SmsSendResult =
        post("/auth/email/send", mapOf("email" to email), null, SmsSendResult::class.java)

    override suspend fun loginEmail(email: String, code: String): LoginResult =
        post("/auth/email/login", mapOf("email" to email, "code" to code), null, LoginResult::class.java)

    override suspend fun profile(token: String): AccountProfile =
        get("/account/profile", token, AccountProfile::class.java)

    override suspend fun updateNickname(token: String, nickname: String): AccountProfile =
        post("/account/nickname", mapOf("nickname" to nickname), token, AccountProfile::class.java)

    override suspend fun balance(token: String): BalanceResult =
        get("/account/balance", token, BalanceResult::class.java)

    override suspend fun goods(token: String): GoodsList =
        get("/billing/goods", token, GoodsList::class.java)

    override suspend fun createOrder(token: String, goodsId: String, currency: String): CreateOrderResult =
        post("/billing/orders", mapOf("goodsId" to goodsId, "currency" to currency), token, CreateOrderResult::class.java)

    override suspend fun queryOrder(token: String, orderNo: String): OrderStatusResult =
        get("/billing/orders/$orderNo", token, OrderStatusResult::class.java)

    override suspend fun dailyClaim(token: String): DailyClaimResult =
        post("/account/daily-claim", null, token, DailyClaimResult::class.java)

    override suspend fun inviteInfo(token: String): InviteInfo =
        get("/invite/info", token, InviteInfo::class.java)

    override suspend fun redeemInvite(token: String, code: String): RedeemResult =
        post("/invite/redeem", mapOf("code" to code), token, RedeemResult::class.java)

    override suspend fun membership(token: String): MembershipResult =
        get("/account/membership", token, MembershipResult::class.java)

    override suspend fun creditTransactions(token: String, limit: Int, offset: Int): CreditTransactionsResult =
        get("/account/credits/transactions?limit=$limit&offset=$offset", token, CreditTransactionsResult::class.java)

    override suspend fun usage(token: String, limit: Int, offset: Int): UsageResult =
        get("/account/usage?limit=$limit&offset=$offset", token, UsageResult::class.java)

    override suspend fun billingEstimate(
        token: String,
        model: String?,
        messages: List<Map<String, String>>,
        maxTokens: Int?,
    ): BillingEstimateResult = post(
        "/billing/estimate",
        buildMap {
            model?.let { put("model", it) }
            put("messages", messages)
            maxTokens?.let { put("maxTokens", it) }
        },
        token,
        BillingEstimateResult::class.java,
    )

    override suspend fun registerDevice(
        token: String,
        deviceId: String?,
        deviceName: String?,
        pushToken: String?,
        osVersion: String?,
        appVersion: String?,
        deviceModel: String?,
    ): DeviceRegisterResult = post(
        "/device/register",
        buildMap {
            deviceId?.let { put("deviceId", it) }
            deviceName?.let { put("deviceName", it) }
            pushToken?.let { put("pushToken", it) }
            osVersion?.let { put("osVersion", it) }
            appVersion?.let { put("appVersion", it) }
            deviceModel?.let { put("deviceModel", it) }
        },
        token,
        DeviceRegisterResult::class.java,
    )

    override suspend fun sendDeviceHeartbeat(
        token: String,
        deviceId: String,
        battery: Int?,
        isCharging: Boolean,
        currentApp: String?,
        screenHash: String?,
    ): DeviceHeartbeatResult = post(
        "/device/heartbeat",
        buildMap {
            put("deviceId", deviceId)
            battery?.let { put("battery", it) }
            put("isCharging", isCharging)
            currentApp?.let { put("currentApp", it) }
            screenHash?.let { put("screenHash", it) }
        },
        token,
        DeviceHeartbeatResult::class.java,
    )

    override suspend fun reportDeviceEvent(
        token: String,
        deviceId: String,
        type: String,
        payload: Map<String, Any>,
    ): DeviceReportResult = post(
        "/device/report",
        mapOf("deviceId" to deviceId, "type" to type, "payload" to payload),
        token,
        DeviceReportResult::class.java,
    )

    override suspend fun deviceStatus(token: String, deviceId: String): DeviceStatusResult =
        get("/device/$deviceId/status", token, DeviceStatusResult::class.java)

    override suspend fun pluginPay(
        token: String, pluginId: String, item: String, credits: Int, description: String,
    ): PluginPayResult = post(
        "/plugin/pay",
        mapOf("plugin_id" to pluginId, "item" to item, "credits" to credits, "description" to description),
        token, PluginPayResult::class.java
    )

    override suspend fun creatorDashboard(token: String): CreatorDashboardResult =
        get("/creator/dashboard", token, CreatorDashboardResult::class.java)

    override suspend fun creatorRanking(token: String): CreatorRankingResult =
        get("/creator/ranking", token, CreatorRankingResult::class.java)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
