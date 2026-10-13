package com.apk.claw.android.registry

import android.content.Context
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 资产 Registry 消费端。
 *
 * 技能([listSkills]):指令型,落地到 filesDir/registry/skills/,通过 [RegistrySkillStore] 管理。
 * 插件([listPlugins]):可执行型,落地到 filesDir/plugins/<slug>/,通过 [PluginRegistryStore] 管理。
 *
 * 契约(公开只读,无鉴权):
 *   GET <base>/api/v1/registry/assets?type=skill|plugin    列资产(信封,不含 body)
 *   GET <base>/api/v1/registry/assets/{type}/{slug}/download    取完整 body
 */

// ───────────────────────── registry 信封 DTO(Gson)─────────────────────────

internal data class RegistryContent(
    val ref: String? = null,
    val checksum: String? = null,   // "sha256:<hex>"
)

internal data class RegistryAsset(
    val id: String = "",            // "skill/<slug>"
    val type: String = "",
    val kind: String = "",          // data | code
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val category: String? = null,
    val tags: List<String>? = null,
    val mode: String? = null,       // inject | tool(消费模式;旧服务端可能没有)
    val platforms: List<String>? = null,  // mobile/desktop(旧服务端可能没有)
    val deps: List<String>? = null,
    val content: RegistryContent? = null,
) {
    val slug: String get() = id.substringAfterLast('/')
}

/** 桌面强相关启发式(服务端未下发 platforms 时的兜底,与 registry.py 的 _DESKTOP_ONLY_HINTS 对齐)。 */
private val DESKTOP_HINTS = listOf("xlsx", "pptx", "docx", "excel", "powerpoint", "spreadsheet", "pdf")

/** 是否适合手机:有 platforms 字段按字段;否则按名称启发式(排除桌面文件类)。 */
internal val RegistryAsset.mobileFit: Boolean
    get() {
        platforms?.takeIf { it.isNotEmpty() }?.let { return "mobile" in it }
        val s = "$slug $name ${category ?: ""}".lowercase()
        return DESKTOP_HINTS.none { it in s }
    }

internal data class RegistryListResponse(
    val success: Boolean = false,
    val total: Int = 0,
    val data: List<RegistryAsset> = emptyList(),
)

internal data class RegistryDownloadData(
    val id: String = "",
    val type: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val category: String? = null,
    val tags: List<String>? = null,
    val deps: List<String>? = null,
    val content: RegistryContent? = null,
    val body: String = "",
)

internal data class RegistryDownloadResponse(
    val success: Boolean = false,
    val data: RegistryDownloadData? = null,
)

// ───────────────────────── 已安装技能本地清单 ─────────────────────────

/** 已安装(下载)的技能元数据,持久化在 filesDir/registry/skills/.manifest.json。 */
internal data class InstalledSkill(
    val id: String,                 // "skill/<slug>"
    val slug: String,
    val version: String,
    val name: String,
    val description: String,
    val checksum: String,
    val installedAt: Long,
    val enabled: Boolean = true,
)

internal data class InstalledManifest(
    val skills: MutableList<InstalledSkill> = mutableListOf(),
)

/** 注入通道用的知识技能(名称 + 描述 + 完整 markdown 指令),供 agent 上下文注入。 */
internal data class KnowledgeSkill(
    val name: String,
    val description: String,
    val body: String,
)

// ───────────────────────── 网络层:列表 + 下载 ─────────────────────────

internal object RegistryClient {
    const val DEFAULT_BASE = "https://api.octoapk.com"
    private const val API = "/api/v1/registry/assets"
    private const val CACHE_KEY = "SKILL_MARKETPLACE_FEED_JSON"
    private const val BASE_KEY = "REGISTRY_BASE_URL"

    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)   // 技能 markdown 可能较大
        .build()

    /** registry 基址(默认公网盒子;可被 KVUtils 覆盖,便于自托管)。 */
    fun base(): String = KVUtils.getString(BASE_KEY, DEFAULT_BASE).trim().trimEnd('/').ifBlank { DEFAULT_BASE }

    /** 列技能目录:服务端 → 缓存回退(永不空屏)。 */
    suspend fun listSkills(): List<RegistryAsset> = listSkillsResult().getOrDefault(emptyList())

    /** 同 [listSkills],但网络失败且没有缓存时返回 failure,界面据此显示「加载失败 + 重试」而不是空状态。 */
    suspend fun listSkillsResult(): Result<List<RegistryAsset>> = listResult("skill", CACHE_KEY)

    /**
     * 列目录:服务端有数据用服务端(并刷新缓存);否则回退缓存;网络失败又没有缓存时返回 failure。
     * 服务端正常返回但为空、且没有缓存时是 success(空列表):那是真的没有内容。
     */
    private suspend fun listResult(type: String, cacheKey: String): Result<List<RegistryAsset>> =
        withContext(Dispatchers.IO) {
            val fetched = runCatching {
                val req = Request.Builder().url("${base()}$API?type=$type").get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val data = if (body.isBlank()) {
                        emptyList()
                    } else {
                        gson.fromJson(body, RegistryListResponse::class.java)?.data.orEmpty()
                    }
                    if (data.isNotEmpty()) KVUtils.putString(cacheKey, body)
                    data
                }
            }
            val cached = cachedList(cacheKey)
            when {
                fetched.getOrNull()?.isNotEmpty() == true -> fetched
                cached.isNotEmpty() -> Result.success(cached)
                else -> fetched
            }
        }

    private fun cachedList(cacheKey: String): List<RegistryAsset> {
        val cached = KVUtils.getString(cacheKey, "")
        if (cached.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(cached, RegistryListResponse::class.java)?.data.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 取单技能 payload(信封 + body)。失败返回 null。 */
    suspend fun download(id: String): RegistryDownloadData? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${base()}$API/$id/download").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    return@withContext gson.fromJson(body, RegistryDownloadResponse::class.java)?.data
                }
            }
        }
        null
    }

    // ──── 插件 (type=plugin) ────

    private const val PLUGIN_CACHE_KEY = "PLUGIN_MARKETPLACE_FEED_JSON"

    /** 列插件目录:服务端 → 缓存回退(永不空屏)。 */
    suspend fun listPlugins(): List<RegistryAsset> = listPluginsResult().getOrDefault(emptyList())

    /** 同 [listPlugins],但网络失败且没有缓存时返回 failure。 */
    suspend fun listPluginsResult(): Result<List<RegistryAsset>> = listResult("plugin", PLUGIN_CACHE_KEY)

    /** 下载单个插件完整 payload(含 base64 ZIP body)。失败返回 null。 */
    suspend fun downloadPlugin(id: String): RegistryDownloadData? = download(id)
}

// ───────────────────────── 本地落地 + 安装清单 ─────────────────────────

internal object RegistrySkillStore {
    private val gson = Gson()

    private fun skillsDir(context: Context): File =
        File(context.filesDir, "registry/skills").apply { mkdirs() }

    private fun manifestFile(context: Context): File = File(skillsDir(context), ".manifest.json")

    private fun readManifest(context: Context): InstalledManifest {
        val f = manifestFile(context)
        if (!f.isFile) return InstalledManifest()
        return runCatching { gson.fromJson(f.readText(), InstalledManifest::class.java) }
            .getOrNull() ?: InstalledManifest()
    }

    private fun writeManifest(context: Context, m: InstalledManifest) {
        runCatching { manifestFile(context).writeText(gson.toJson(m)) }
    }

    fun installed(context: Context): List<InstalledSkill> = readManifest(context).skills.toList()

    fun isInstalled(context: Context, slug: String): InstalledSkill? =
        readManifest(context).skills.firstOrNull { it.slug == slug }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 安装(下载)一个技能:取 body → **校验 sha256 checksum** → 落地 + 记入清单。
     * 内置技能不受影响(只写 filesDir,不碰 assets)。返回错误信息,null = 成功。
     */
    suspend fun install(context: Context, asset: RegistryAsset): String? {
        val data = RegistryClient.download(asset.id) ?: return "下载失败:网络或服务不可达"
        val body = data.body
        val expected = (data.content?.checksum ?: asset.content?.checksum)?.removePrefix("sha256:")
        if (expected.isNullOrBlank() || body.isBlank()) {
            return "校验失败:服务端未提供 checksum 或 body 为空,已拒绝安装"
        }
        val actual = sha256Hex(body)
        if (!actual.equals(expected, ignoreCase = true)) return "校验失败:checksum 不符,已拒绝安装"
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(skillsDir(context), asset.slug).apply { mkdirs() }
                File(dir, "body.md").writeText(body)
                File(dir, "envelope.json").writeText(gson.toJson(data))
                val m = readManifest(context)
                m.skills.removeAll { it.slug == asset.slug }   // 覆盖旧版
                m.skills.add(
                    InstalledSkill(
                        id = asset.id, slug = asset.slug, version = data.version.ifBlank { asset.version },
                        name = asset.name.ifBlank { data.name }, description = asset.description.ifBlank { data.description },
                        checksum = data.content?.checksum ?: asset.content?.checksum.orEmpty(),
                        installedAt = System.currentTimeMillis(), enabled = true,
                    )
                )
                writeManifest(context, m)
                null
            }.getOrElse { "落地失败:${it.message}" }
        }
    }

    /** 卸载:删文件 + 移出清单(只动 filesDir,内置技能不受影响)。 */
    fun uninstall(context: Context, slug: String) {
        runCatching { File(skillsDir(context), slug).deleteRecursively() }
        val m = readManifest(context)
        m.skills.removeAll { it.slug == slug }
        writeManifest(context, m)
    }

    /** 启用/停用一个已安装技能(停用 = 不注入 agent)。 */
    fun setEnabled(context: Context, slug: String, enabled: Boolean) {
        val m = readManifest(context)
        val i = m.skills.indexOfFirst { it.slug == slug }
        if (i >= 0) { m.skills[i] = m.skills[i].copy(enabled = enabled); writeManifest(context, m) }
    }

    /**
     * 注入通道:已启用的下载技能(名称 + 描述 + 完整 body)。供 agent 上下文注入(option 2),
     * **不进工具调用列表**。body 从 filesDir 读,读不到则跳过。
     */
    fun enabledKnowledge(context: Context): List<KnowledgeSkill> {
        return readManifest(context).skills.filter { it.enabled }.mapNotNull { s ->
            val f = File(File(skillsDir(context), s.slug), "body.md")
            val body = runCatching { if (f.isFile) f.readText() else null }.getOrNull() ?: return@mapNotNull null
            KnowledgeSkill(name = s.name, description = s.description, body = body)
        }
    }

    /**
     * 注入文案(option 2):把已启用技能的指令拼成一段**系统上下文**追加给 agent,
     * 让 LLM「照着指令、用现有工具能力完成」。**带总长上限**(默认 6000 字),防爆 context;
     * 单技能正文也截断。空串 = 没有已启用技能(则系统提示原样不变)。
     */
    fun knowledgeBlock(context: Context, charBudget: Int = 6000, perSkillCap: Int = 1800): String {
        val ks = enabledKnowledge(context)
        if (ks.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## 已安装技能(知识库)\n")
        sb.append("你已安装以下技能,需要时按其说明、用你现有的工具与能力去完成(它们是操作指南,不是可直接调用的工具):\n\n")
        for (k in ks) {
            val body = if (k.body.length > perSkillCap) k.body.take(perSkillCap) + "\n…(已截断)" else k.body
            val block = "### ${k.name}\n${k.description}\n\n${body}\n\n---\n\n"
            if (sb.length + block.length > charBudget) break
            sb.append(block)
        }
        return sb.toString().trimEnd()
    }
}
