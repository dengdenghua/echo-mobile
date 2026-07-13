@file:Suppress(
    "PackageNaming", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException",
)   // octopus_mobile 包;拉取失败多出口守卫式 + 宽 catch 兜底返 null(网络/解析任何异常都视为拉取失败)

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 知识拉取(客户端)—— 从同账号/局域网另一台设备的 `/api/knowledge`(见 KnowledgeRouteHandler)
 * 拉取其规矩 + 记忆并导入本机。fleet 数据飞轮不经云端、走局域网 ConfigServer 的「拉取半段」。
 *
 * 导入复用 [KnowledgeBundle] + [InteractionLedger.addManualRule] / [MemoryStore.addUserFact](均幂等去重),
 * 所以重复拉取不会翻倍。阻塞式,须在后台线程调用。
 */
object KnowledgeSync {

    /**
     * 从 [baseUrl](对端 ConfigServer 基址,如 http://ip:9527)拉取并导入。
     * @return (导入规矩数, 导入记忆数);网络失败/非法响应返回 null。
     */
    fun pullFrom(baseUrl: String, authToken: String, http: OkHttpClient): Pair<Int, Int>? {
        val url = baseUrl.trimEnd('/') + "/api/knowledge"
        val req = Request.Builder()
            .url(url)
            .apply { if (authToken.isNotBlank()) addHeader("Authorization", "Bearer $authToken") }
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (!KnowledgeBundle.looksValid(body)) return null
                val parsed = KnowledgeBundle.parse(body)
                parsed.rules.forEach { InteractionLedger.addManualRule(it) }
                val store = MemoryStore()
                parsed.memories.forEach {
                    val type = runCatching { MemoryStore.MemoryType.valueOf(it.type) }
                        .getOrDefault(MemoryStore.MemoryType.FACT)
                    store.addUserFact(it.content, type)
                }
                parsed.rules.size to parsed.memories.size
            }
        } catch (e: Exception) {
            null
        }
    }
}
