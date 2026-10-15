package com.apk.claw.android.ui.compose.screen
import com.apk.claw.android.utils.OctoHttp

import androidx.compose.ui.graphics.Color
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 广场数据层。
 *
 * 设计目标（按需求）：
 *  - **广场目录走服务端 API**（`<baseUrl>/square/feed`），后台可随意增删改，App 不用发版；
 *    网络失败时回退到本地缓存；没有缓存就显示离线状态和重试，不拿虚构的示例帖充数。
 *  - **与本机强相关的技能/插件走本地注册**（[LocalSkillRegistry]），不依赖服务端目录。
 *
 * UI（[AgentSquareScreen]）只消费 [AgentPost]，不关心来源。
 */

/** 广场卡片领域模型（颜色已解析为 Compose Color，UI 直接用）。 */
internal data class AgentPost(
    val id: String,
    val title: String,
    val author: String,
    val authorInitial: String,
    val authorColor: Color,
    val likes: String,
    val tag: String,
    val tagColor: Color,
    val coverHeightDp: Int,
    val coverGradient: List<Color>,
    /** true = 本地注册的技能/插件（非服务端目录）。 */
    val local: Boolean = false,
    /** 帖子类型:"post"=图文帖(小红书式), "mini-app"=小程序分享帖, ""=旧式种子/静态示例。 */
    val kind: String = "",
    /** 图文帖正文(仅 kind="post" 有)。 */
    val content: String = "",
    /** 图文帖封面图 URL(优先于 coverGradient 展示)。 */
    val coverUrl: String = "",
    /** 图文帖图片列表(详情页用)。 */
    val images: List<String> = emptyList(),
    /** 作者 opaque uid(点击作者头像跳主页用)。 */
    val authorId: String = "",
    /** 点赞数(数值形式,便于展示与排序;旧式卡片用 likes 字符串)。 */
    val likesCount: Int = 0,
    /** 评论数。 */
    val commentsCount: Int = 0,
    /** 收藏数。 */
    val favoritesCount: Int = 0,
    /** 当前用户是否已点赞。 */
    val liked: Boolean = false,
    /** 当前用户是否已收藏。 */
    val favorited: Boolean = false,
    /** 创建时间(epoch millis)。 */
    val createdAt: Long = 0,
    /** 关联可运行物 id(mini-app slug / routine);空=纯图文帖,只显示点赞评论。 */
    val appRef: String = "",
    /** 关联物类型:mini-app | routine | skill。 */
    val appKind: String = "",
    /** 复刻定价(付费积分);0=免费复刻。 */
    val priceCredits: Int = 0,
    /** 分类 key(recommend/automation/efficiency/life/learning/device)。 */
    val topic: String = "recommend",
    /** 当前用户是否已复刻/解锁(已解锁再下载免费)。 */
    val owned: Boolean = false,
    /** 按月订阅价(积分/月);0=非订阅帖。 */
    val subPriceCredits: Int = 0,
    /** 当前用户订阅是否有效。 */
    val subActive: Boolean = false,
    /** 浏览数。 */
    val viewsCount: Int = 0,
    /** Fork 来源帖子 id(空=原创)。 */
    val forkedFrom: String = "",
)

/** 服务端下发的广场卡片：颜色用 "#RRGGBB" 字符串，方便后台随意编辑。 */
internal data class SquarePostDto(
    val id: String = "",
    val kind: String = "",
    val title: String = "",
    val content: String = "",
    val coverUrl: String = "",
    val images: List<String> = emptyList(),
    val author: String = "",
    val authorId: String = "",
    val authorInitial: String = "",
    val authorColor: String = "#7C6FF0",
    val likes: String = "",
    @com.google.gson.annotations.SerializedName("likesCount") val likesCount: Int = 0,
    @com.google.gson.annotations.SerializedName("commentsCount") val commentsCount: Int = 0,
    @com.google.gson.annotations.SerializedName("favoritesCount") val favoritesCount: Int = 0,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val tag: String = "",
    val tagColor: String = "#7C6FF0",
    val coverHeightDp: Int = 160,
    val coverGradient: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("createdAt") val createdAt: Long = 0,
    @com.google.gson.annotations.SerializedName("appRef") val appRef: String = "",
    @com.google.gson.annotations.SerializedName("appKind") val appKind: String = "",
    @com.google.gson.annotations.SerializedName("priceCredits") val priceCredits: Int = 0,
    val topic: String = "recommend",
    val owned: Boolean = false,
    @com.google.gson.annotations.SerializedName("subPriceCredits") val subPriceCredits: Int = 0,
    val subActive: Boolean = false,
    val viewsCount: Int = 0,
    val forkedFrom: String = "",
)

internal data class SquareFeedDto(
    val posts: List<SquarePostDto> = emptyList(),
    @com.google.gson.annotations.SerializedName("has_more") val hasMore: Boolean = false,
)

/**
 * Placeholder covers for posts and mini-apps without an image. ECHO surfaces are graphite and
 * categories are never told apart by colour, so covers only vary in lightness (no rainbow gradients).
 */
internal val EchoCoverGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF2E333B), Color(0xFF171A1F)),
    listOf(Color(0xFF262A31), Color(0xFF101216)),
    listOf(Color(0xFF3A4049), Color(0xFF1E2228)),
    listOf(Color(0xFF1E2228), Color(0xFF090A0C)),
)

private fun parseColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrDefault(fallback)

private val BrowserTint get() = OctopusTints.Browser
private val MemoryTint get() = OctopusTints.Memory
private val SkillTint get() = OctopusTints.Skill
private val RoutineTint get() = OctopusTints.Routine
private val CloudTint get() = OctopusTints.Cloud
private val WindowTint get() = OctopusTints.Window

internal fun SquarePostDto.toAgentPost(): AgentPost {
    val grad = coverGradient
        .mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it.trim())) }.getOrNull() }
        .ifEmpty { EchoCoverGradients[0] }
    return AgentPost(
        id = id.ifBlank { title.hashCode().toString() },
        title = title,
        author = author,
        authorInitial = authorInitial.ifBlank { author.take(1) },
        authorColor = parseColor(authorColor, OctopusTints.Skill),
        likes = likes,
        tag = tag,
        tagColor = parseColor(tagColor, OctopusTints.Routine),
        coverHeightDp = coverHeightDp.coerceIn(120, 240),
        coverGradient = grad,
        kind = kind,
        content = content,
        coverUrl = coverUrl,
        images = images,
        authorId = authorId,
        likesCount = likesCount,
        commentsCount = commentsCount,
        favoritesCount = favoritesCount,
        liked = liked,
        favorited = favorited,
        createdAt = createdAt,
        appRef = appRef,
        appKind = appKind,
        priceCredits = priceCredits,
        topic = topic.ifBlank { "recommend" },
        owned = owned,
        subPriceCredits = subPriceCredits,
        subActive = subActive,
        viewsCount = viewsCount,
        forkedFrom = forkedFrom,
    )
}

/**
 * 本地技能/插件注册表：与本机能力强相关的条目在本地注册，不走服务端目录。
 * 各本地插件可在初始化时调用 [register] 把自己挂上来。
 */
internal object LocalSkillRegistry {
    private val items = linkedMapOf<String, AgentPost>()

    fun register(post: AgentPost) { items[post.id] = post.copy(local = true) }
    fun unregister(id: String) { items.remove(id) }
    fun all(): List<AgentPost> = items.values.toList()

    init {
        // 内置示例：本地相关技能（演示本地注册机制；真实插件可各自 register）
        register(
            AgentPost(
                id = "local.notify", title = "通知巡检 · 重要消息汇总给我",
                author = "本地技能", authorInitial = "本", authorColor = OctopusTints.Skill,
                likes = "", tag = "本地", tagColor = OctopusTints.Window,
                coverHeightDp = 140, coverGradient = EchoCoverGradients[1],
            )
        )
        register(
            AgentPost(
                id = "local.files", title = "文件整理 · 截图自动归档到文件夹",
                author = "本地技能", authorInitial = "本", authorColor = OctopusTints.Cloud,
                likes = "", tag = "本地", tagColor = OctopusTints.Window,
                coverHeightDp = 150, coverGradient = EchoCoverGradients[2],
            )
        )
    }
}

/** 广场目录仓库：服务端 → 缓存；都没有时如实显示离线状态。 */
internal object SquareRepository {
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 服务端目录（后台可随意改）。服务端回什么就显示什么（可以为空）；连不上时回退到上次成功的缓存；
     *  连缓存也没有时返回 null，由界面显示离线状态和重试。不再用虚构的示例帖（假作者、假点赞数）充数。
     *  [topic] 为分类 key(recommend=全部,不过滤);按 topic 分桶缓存,避免切分类污染主 feed 缓存。 */
    suspend fun remoteFeed(
        topic: String = "recommend",
        sort: String = "latest",
        following: Boolean = false,
    ): List<AgentPost>? = withContext(Dispatchers.IO) {
        RemoteConfig.refresh()  // 先取服务端下发的技能中心域名（拿不到则用缓存/默认）
        val filter = topic.trim().takeIf { it.isNotBlank() && it != "recommend" }.orEmpty()
        val cacheKey = "SQUARE_FEED_CACHE_JSON_${filter}_${sort}_${if (following) "following" else "all"}"
        val base = AccountConfig.squareBaseUrl.trim().trimEnd('/')
        if (base.isNotBlank()) {
            runCatching {
                val params = mutableListOf<String>()
                if (filter.isNotEmpty()) params.add("topic=$filter")
                if (sort != "latest") params.add("sort=$sort")
                if (following) params.add("following=1")
                val queryString = if (params.isEmpty()) "" else "?" + params.joinToString("&")
                val url = "$base/square/feed$queryString"
                val builder = Request.Builder().url(url).get()
                // 携带登录态:让服务端能返回 liked/favorited/owned 当前用户态(未登录时服务端忽略)
                val tok = AccountStore.token
                if (tok.isNotBlank()) builder.header("Authorization", "Bearer $tok")
                http.newCall(builder.build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val feed = gson.fromJson(body, SquareFeedDto::class.java)
                        if (feed != null) {
                            // 缓存有内容的成功结果(按 topic 分桶)
                            if (feed.posts.isNotEmpty()) KVUtils.putString(cacheKey, body)
                            return@withContext feed.posts.map { it.toAgentPost() }
                        }
                    }
                }
            }
        }
        cached(cacheKey)
    }

    /** 上次成功拿到的目录；从没成功过时为 null。 */
    private fun cached(cacheKey: String): List<AgentPost>? {
        val cached = KVUtils.getString(cacheKey, "")
        if (cached.isNotBlank()) {
            runCatching {
                val feed = gson.fromJson(cached, SquareFeedDto::class.java)
                if (feed?.posts?.isNotEmpty() == true) return feed.posts.map { it.toAgentPost() }
            }
        }
        return null
    }

    /** 本地技能/插件（本地注册，不依赖服务端）。 */
    fun localFeed(): List<AgentPost> = LocalSkillRegistry.all()
}

// ───────────────────────── 灵感发现流（FeatureHub「灵感」tab）─────────────────────────

/**
 * 灵感发现卡片（富模型：封面/作者/用量/成功率/权限/详情）。
 * 内容文本走服务端，可后台随意改；[topicRes] 仍是本地化分类资源，保留 App 内分类胶囊/筛选/封面图标逻辑。
 * [topicKey] 是服务端 topic key（automation/efficiency/life/learning/device），用于远端 topics 标签匹配。
 */
internal data class AgentDiscoveryPost(
    val id: String,
    val title: String,
    val desc: String,
    val author: String,
    val authorInitial: String,
    val likes: String,
    val topicRes: Int,
    val topicKey: String,
    val tag: String,
    val tagColor: Color,
    val coverHeight: Int,
    val cover: List<Color>,
    val usage: String,
    val successRate: String,
    val duration: String,
    val permissions: List<String>,
)

/** 服务端下发的灵感卡片（颜色 "#RRGGBB"、topic 用 key、文本与权限直给字符串）。 */
internal data class DiscoveryPostDto(
    val id: String = "",
    val title: String = "",
    val desc: String = "",
    val author: String = "",
    val authorInitial: String = "",
    val likes: String = "",
    val topic: String = "",
    val tag: String = "",
    val tagColor: String = "#7C6FF0",
    val coverHeight: Int = 160,
    val cover: List<String> = emptyList(),
    val usage: String = "",
    val successRate: String = "",
    val duration: String = "",
    val permissions: List<String> = emptyList(),
)

/** 服务端下发的顶部引导动作（icon/action 用 key 字符串，App 端映射）。 */
internal data class DiscoveryActionDto(
    val icon: String = "",
    val text: String = "",
    val action: String = "",
    val tint: String = "",
)

/** 服务端下发的顶部引导头（title/desc/icon/tint/actions）。 */
internal data class DiscoveryHeaderDto(
    val title: String = "",
    val desc: String = "",
    val icon: String = "",
    val tint: String = "",
    val actions: List<DiscoveryActionDto> = emptyList(),
)

/** 服务端下发的主题标签（key 用于筛选，label/tint 可选，留空时 App 回退本地化资源）。 */
internal data class DiscoveryTopicDto(
    val key: String = "",
    val label: String = "",
    val tint: String = "",
)

internal data class DiscoveryFeedDto(
    val posts: List<DiscoveryPostDto> = emptyList(),
    val header: DiscoveryHeaderDto = DiscoveryHeaderDto(),
    val topics: List<DiscoveryTopicDto> = emptyList(),
)

// ── 灵感流 domain（颜色已解析为 Compose Color，UI 直接用） ──

/** 顶部引导动作。text 为空时 UI 用 stringResource 兜底。 */
internal data class DiscoveryAction(
    val icon: String,
    val text: String,
    val action: String,
    val tint: Color,
)

/** 顶部引导头。title/desc 为空时 UI 用 stringResource 兜底。 */
internal data class DiscoveryHeader(
    val title: String,
    val desc: String,
    val icon: String,
    val tint: Color,
    val actions: List<DiscoveryAction>,
)

/** 主题标签。label 为空时 UI 用 stringResource 兜底。 */
internal data class DiscoveryTopic(
    val key: String,
    val label: String,
    val tint: Color,
)

/** 灵感流整体：header + topics + posts 三段。 */
internal data class DiscoveryFeed(
    val header: DiscoveryHeader,
    val topics: List<DiscoveryTopic>,
    val posts: List<AgentDiscoveryPost>,
)

/** 服务端 topic key → 本地化分类资源（沿用 App 内已有分类胶囊/筛选/图标）。 */
private fun topicKeyToRes(key: String): Int = when (key.trim().lowercase()) {
    "automation" -> R.string.agent_topic_automation
    "efficiency" -> R.string.agent_topic_efficiency
    "life", "lifestyle" -> R.string.agent_topic_life
    "learning" -> R.string.agent_topic_learning
    "device" -> R.string.agent_topic_device
    else -> R.string.agent_topic_automation
}

private fun DiscoveryPostDto.toPost(): AgentDiscoveryPost {
    val cov = cover
        .mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it.trim())) }.getOrNull() }
        .ifEmpty { EchoCoverGradients.last() }
    return AgentDiscoveryPost(
        id = id.ifBlank { title.hashCode().toString() },
        title = title, desc = desc, author = author,
        authorInitial = authorInitial.ifBlank { author.take(1) },
        likes = likes,
        topicRes = topicKeyToRes(topic),
        topicKey = topic.trim().lowercase(),
        tag = tag.ifBlank { topic },
        tagColor = parseColor(tagColor, OctopusTints.Routine),
        coverHeight = coverHeight.coerceIn(120, 240),
        cover = cov,
        usage = usage, successRate = successRate, duration = duration,
        permissions = permissions,
    )
}

private fun DiscoveryActionDto.toAction(): DiscoveryAction = DiscoveryAction(
    icon = icon,
    text = text,
    action = action,
    tint = parseColor(tint, OctopusTints.Browser),
)

private fun DiscoveryHeaderDto.toHeader(): DiscoveryHeader = DiscoveryHeader(
    title = title,
    desc = desc,
    icon = icon,
    tint = parseColor(tint, OctopusTints.Browser),
    actions = actions.map { it.toAction() },
)

private fun DiscoveryTopicDto.toTopic(): DiscoveryTopic = DiscoveryTopic(
    key = key.trim().lowercase(),
    label = label,
    tint = parseColor(tint, OctopusTints.Hot),
)

private fun DiscoveryFeedDto.toFeed(): DiscoveryFeed = DiscoveryFeed(
    header = header.toHeader(),
    topics = topics.map { it.toTopic() },
    posts = posts.map { it.toPost() },
)

/** 灵感发现流仓库：服务端 `/square/discovery` → 缓存 → 内置种子。 */
internal object DiscoveryRepository {
    private const val CACHE_KEY = "SQUARE_DISCOVERY_CACHE_JSON"
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun feed(): DiscoveryFeed = withContext(Dispatchers.IO) {
        RemoteConfig.refresh()  // 先取服务端下发的技能中心域名（拿不到则用缓存/默认）
        val base = AccountConfig.squareBaseUrl.trim().trimEnd('/')
        if (base.isNotBlank()) {
            runCatching {
                val req = Request.Builder().url("$base/square/discovery").get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val dto = gson.fromJson(body, DiscoveryFeedDto::class.java)
                        if (dto != null && dto.posts.isNotEmpty()) {
                            KVUtils.putString(CACHE_KEY, body)
                            return@withContext dto.toFeed()
                        }
                    }
                }
            }
        }
        cachedOrSeed()
    }

    private fun cachedOrSeed(): DiscoveryFeed {
        val cached = KVUtils.getString(CACHE_KEY, "")
        if (cached.isNotBlank()) {
            runCatching {
                val dto = gson.fromJson(cached, DiscoveryFeedDto::class.java)
                if (dto != null && dto.posts.isNotEmpty()) return dto.toFeed()
            }
        }
        return SEED
    }

    /** 离线兜底种子（header/topics 字段留空 → UI 用 stringResource 兜底）。 */
    private val SEED: DiscoveryFeed = DiscoveryFeed(
        header = DiscoveryHeader(
            title = "", desc = "", icon = "AutoAwesome", tint = OctopusTints.Browser,
            actions = listOf(
                DiscoveryAction("Search", "", "search", OctopusTints.Browser),
                DiscoveryAction("Psychology", "", "universe", OctopusTints.Memory),
                DiscoveryAction("Add", "", "publish", OctopusTints.Skill),
            ),
        ),
        topics = listOf(
            DiscoveryTopic("recommend", "", OctopusTints.Hot),
            DiscoveryTopic("automation", "", OctopusTints.Routine),
            DiscoveryTopic("efficiency", "", OctopusTints.Skill),
            DiscoveryTopic("life", "", OctopusTints.Cloud),
            DiscoveryTopic("learning", "", OctopusTints.Memory),
            DiscoveryTopic("device", "", OctopusTints.Window),
        ),
        posts = listOf(
            AgentDiscoveryPost("agent-travel", "旅行规划:一键搞定航班到行程", "输入目的地和预算,自动搜索景点、规划路线,并生成可分享的清单。", "旅行灵感", "旅", "3.2k", R.string.agent_topic_life, "life", "生活", OctopusTints.Routine, 168, EchoCoverGradients[0], "18.6k", "92%", "约 4 分钟", listOf("浏览器", "定位", "截图")),
            AgentDiscoveryPost("agent-weekly", "周报自动归档模板", "汇总聊天记录、任务清单和日程,自动写出老板点赞的周报。", "效率玩家", "效", "2.8k", R.string.agent_topic_efficiency, "efficiency", "效率", OctopusTints.Skill, 138, EchoCoverGradients[1], "12.4k", "95%", "约 2 分钟", listOf("日历", "剪贴板", "文档")),
            AgentDiscoveryPost("agent-phone", "旧手机变 7×24 执行器", "让备用机代收消息、截图、转发和定时任务,主力机保持安静。", "极客据点", "极", "1.7k", R.string.agent_topic_device, "device", "设备", OctopusTints.Window, 190, EchoCoverGradients[2], "8.1k", "89%", "约 6 分钟", listOf("无障碍", "通知", "后台")),
            AgentDiscoveryPost("agent-shopping", "比价助手帮我省了 2000+", "监控历史价格、优惠券和平台活动,降价即提醒。", "省钱 Bot", "省", "4.6k", R.string.agent_topic_automation, "automation", "自动化", OctopusTints.Hot, 156, EchoCoverGradients[3], "23.9k", "91%", "约 3 分钟", listOf("浏览器", "通知", "定时器")),
            AgentDiscoveryPost("agent-paper", "论文阅读器:10 分钟抓重点", "读取 PDF、网页和截图,自动抽取结论与可引用的观点。", "学术助手", "学", "986", R.string.agent_topic_learning, "learning", "学习", OctopusTints.Memory, 176, EchoCoverGradients[0], "6.5k", "94%", "约 5 分钟", listOf("文件", "浏览器", "剪贴板")),
            AgentDiscoveryPost("agent-voice", "语音助手:开车时替你回消息", "按下说话,自动识别收件人、调整语气并发送。", "车载达人", "车", "742", R.string.agent_topic_automation, "automation", "语音", OctopusTints.Trust, 146, EchoCoverGradients[1], "5.7k", "88%", "约 2 分钟", listOf("麦克风", "通知", "无障碍")),
        ),
    )
}

/**
 * App 远程配置：从主 API `<baseUrl>/config` 拉取技能中心域名等并缓存进 [AccountConfig]。
 * 即「子域名由服务端生成/控制」——后台改一处，全网 App 下次进广场即生效，无需发版。
 */
internal object RemoteConfig {
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private data class AppConfigDto(val squareBaseUrl: String? = null)

    /** 拉一次远程配置；失败静默（保留上次缓存/默认）。 */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val base = AccountConfig.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) return@withContext
            runCatching {
                val req = Request.Builder().url("$base/config").get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val cfg = gson.fromJson(resp.body?.string().orEmpty(), AppConfigDto::class.java)
                        cfg?.squareBaseUrl?.let { AccountConfig.setRemoteSquareBaseUrl(it) }
                    }
                }
            }
        }
    }
}
