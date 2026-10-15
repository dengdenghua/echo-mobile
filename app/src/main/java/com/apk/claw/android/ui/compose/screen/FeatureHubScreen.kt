package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.LoadFailedState
import com.apk.claw.android.ui.compose.component.OctopusCard
import com.apk.claw.android.ui.compose.component.OctopusPill
import com.apk.claw.android.ui.compose.component.OctopusTextPill
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.tvFocusable
import com.apk.claw.android.ui.compose.theme.tvOverscan
import com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity
import com.apk.claw.android.ui.featurescreens.CloudDriveActivity
import com.apk.claw.android.ui.featurescreens.EvolutionActivity
import com.apk.claw.android.ui.featurescreens.MemoryActivity
import com.apk.claw.android.ui.featurescreens.MultiWindowActivity
import com.apk.claw.android.ui.featurescreens.HubActivity
import com.apk.claw.android.ui.featurescreens.MiniAppMarketplaceActivity
import com.apk.claw.android.ui.featurescreens.SkillsActivity
import com.apk.claw.android.ui.featurescreens.TrustCenterActivity
import com.apk.claw.android.ui.featurescreens.VideoLibraryActivity

/**
 * 「广场」中心 —— 双 Tab:
 *   - 探索: 社交圈子/热门/推荐（INS 风格占位，待接内容源）
 *   - 工具箱: 双列卡片网格（技能/插件/云盘/记忆…），每张带描述
 */
private val SkillTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Skill
private val RoutineTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Routine
private val CloudTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Cloud
private val MemoryTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Memory
private val VideoTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Video
private val WindowTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Window
private val BrowserTint get() = com.apk.claw.android.ui.compose.theme.OctopusTints.Browser

// ── 探索页占位数据 ──
private data class CirclePreview(
    val nameRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val members: String,
    val tagRes: Int,
)

private val sampleCircles = listOf(
    CirclePreview(R.string.circle_ai_players, Icons.Filled.SmartToy, SkillTint, "1.2k", R.string.circle_tag_hot),
    CirclePreview(R.string.circle_efficiency, Icons.Filled.Bolt, RoutineTint, "856", R.string.circle_tag_recommended),
    CirclePreview(R.string.circle_digital, Icons.Filled.PhoneAndroid, WindowTint, "2.3k", R.string.circle_tag_hot),
    CirclePreview(R.string.circle_geek, Icons.Filled.Code, MemoryTint, "634", R.string.circle_tag_new),
    CirclePreview(R.string.circle_lazy, Icons.Filled.Weekend, CloudTint, "1.8k", R.string.circle_tag_recommended),
    CirclePreview(R.string.circle_tv_cast, Icons.Filled.Tv, VideoTint, "421", R.string.circle_tag_new),
)

// AgentDiscoveryPost / 灵感发现流数据已迁至 SquareCatalog.kt（服务端 API + 缓存 + 种子回退）。

@Composable
fun FeatureHubScreen(
    onOpenSearch: () -> Unit = {},
    onCreatePost: () -> Unit = {},
    onNavigateToUniverse: () -> Unit = {},
    onNavigateToSkillMarketplace: () -> Unit = {},
    onNavigateToPluginMarketplace: () -> Unit = {},
    onOpenPostDetail: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(OctopusBackground.pageBrush()).statusBarsPadding().tvOverscan()) {
            // 顶部双 Tab
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = OctopusSpacing.xl, end = OctopusSpacing.xl, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
                verticalAlignment = Alignment.Bottom,
            ) {
                TabLabel(stringResource(R.string.feat_explore_title), selected = tab == 0) { tab = 0 }
                Spacer(Modifier.width(OctopusSpacing.xl))
                TabLabel(stringResource(R.string.feat_toolbox_title), selected = tab == 1) { tab = 1 }
            }

            when (tab) {
                0 -> ExploreTab(
                    onOpenSearch = onOpenSearch,
                    onCreatePost = onCreatePost,
                    onNavigateToUniverse = onNavigateToUniverse,
                    onOpenPostDetail = onOpenPostDetail,
                )
                else -> MarketTab(
                    onOpenSkillStore = onNavigateToSkillMarketplace,
                    onOpenPluginStore = onNavigateToPluginMarketplace,
                    onOpenAppStore = { ctx.open(MiniAppMarketplaceActivity::class.java) },
                    onOpenMySkills = { HubActivity.start(ctx, HubActivity.TAB_SKILLS) },
                    onOpenMyApps = { HubActivity.start(ctx, HubActivity.TAB_APPS) },
                )
            }
        }

    }
}

/** 顶部 Tab 文本：选中=大号加粗+主题色下划线；未选=灰色常规。 */
@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).tvFocusable()) {
        Text(
            text,
            color = if (selected) OctopusColors.TextPrimary else OctopusColors.TextMuted,
            fontSize = if (selected) OctopusType.headline else OctopusType.titleSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(OctopusSpacing.xs))
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(if (selected) 22.dp else 0.dp)
                .background(OctopusColors.Primary, RoundedCornerShape(2.dp)),
        )
    }
}

// ── 探索 Tab：灵感瀑布流 ──────────────────────────

@Composable
@Suppress("LongMethod")
private fun ExploreTab(
    onOpenSearch: () -> Unit,
    onCreatePost: () -> Unit,
    onNavigateToUniverse: () -> Unit,
    onOpenPostDetail: (String) -> Unit,
) {
    var selectedTopic by remember { mutableStateOf("recommend") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0=推荐 1=关注 2=热门
    val tabs = listOf(
        stringResource(R.string.agent_square_tab_recommend),
        stringResource(R.string.agent_square_tab_following),
        stringResource(R.string.agent_square_tab_hot),
    )

    val sort = when (selectedTab) {
        2 -> "hot"
        else -> "latest"
    }
    val following = selectedTab == 1

    // null = loading. offline = the square could not be reached and nothing was cached: say so, with a retry,
    // instead of showing made-up example posts.
    var reloadKey by remember { mutableIntStateOf(0) }
    var offline by remember { mutableStateOf(false) }
    val posts by produceState<List<AgentPost>?>(initialValue = null, selectedTopic, selectedTab, reloadKey) {
        value = null
        val feed = SquareRepository.remoteFeed(selectedTopic, sort = sort, following = following)
        offline = feed == null
        value = feed.orEmpty()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalItemSpacing = OctopusSpacing.md,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            AgentDiscoveryHeader(
                header = null,
                onSearch = onOpenSearch,
                onUniverse = onNavigateToUniverse,
                onCreate = onCreatePost,
            )
        }

        // ── 推荐/关注/热门 Tab ──
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
            ) {
                tabs.forEachIndexed { index, label ->
                    val isSelected = index == selectedTab
                    Surface(
                        shape = OctopusShape.capsule,
                        color = if (isSelected) OctopusColors.Primary else OctopusColors.SurfaceDeep.copy(alpha = 0.5f),
                        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
                        modifier = Modifier.clickable { selectedTab = index },
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
                            color = if (isSelected) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
                            fontSize = OctopusType.body,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // ── topic 胶囊(仅推荐/热门 Tab 显示,关注 Tab 不需要分类过滤) ──
        if (selectedTab != 1) {
            item(span = StaggeredGridItemSpan.FullLine) {
                AgentTopicChips(
                    topics = null,
                    selectedTopic = selectedTopic,
                    onSelect = { selectedTopic = it },
                )
            }
        }

        item(span = StaggeredGridItemSpan.FullLine) {
            val title = when (selectedTab) {
                1 -> stringResource(R.string.square_section_following)
                2 -> if (selectedTopic == "recommend") stringResource(R.string.square_section_hot)
                     else stringResource(R.string.square_section_hot_topic, topicLabel(selectedTopic))
                else -> if (selectedTopic == "recommend") stringResource(R.string.agent_trending_now)
                        else stringResource(R.string.agent_topic_inspiration, topicLabel(selectedTopic))
            }
            SectionHeaderWithAction(title, Icons.Filled.Whatshot, com.apk.claw.android.ui.compose.theme.OctopusTints.Hot)
        }

        val data = posts
        if (data == null) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = OctopusSpacing.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(OctopusIconSize.medium),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(OctopusSpacing.sm))
                    Text(
                        stringResource(R.string.browser_loading_text),
                        color = OctopusColors.TextSecondary,
                        fontSize = OctopusType.body,
                    )
                }
            }
        } else if (offline) {
            item(span = StaggeredGridItemSpan.FullLine) {
                LoadFailedState(
                    title = stringResource(R.string.square_offline_title),
                    message = stringResource(R.string.square_offline_desc),
                    onRetry = { reloadKey++ },
                    modifier = Modifier.padding(vertical = OctopusSpacing.xl),
                )
            }
        } else if (data.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                val msg = stringResource(
                    if (selectedTab == 1) R.string.square_following_empty else R.string.square_feed_empty,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(msg, color = OctopusColors.TextMuted, fontSize = OctopusType.body)
                }
            }
        } else {
            staggeredItems(data, key = { it.id }) { post ->
                AgentPostCard(post, onClick = { onOpenPostDetail(post.id) })
            }
        }
    }
}

/** topic key → 本地化标签文案（远端 label 留空时兜底）。 */
@Composable
private fun topicLabel(key: String): String = when (key.trim().lowercase()) {
    "recommend" -> stringResource(R.string.agent_square_tab_recommend)
    "automation" -> stringResource(R.string.agent_topic_automation)
    "efficiency" -> stringResource(R.string.agent_topic_efficiency)
    "life", "lifestyle" -> stringResource(R.string.agent_topic_life)
    "learning" -> stringResource(R.string.agent_topic_learning)
    "device" -> stringResource(R.string.agent_topic_device)
    else -> stringResource(R.string.agent_square_tab_recommend)
}

/** icon key → ImageVector（远端下发 key，App 端映射；未知 key 用 AutoAwesome 兜底）。 */
private fun iconKeyToVector(key: String): ImageVector = when (key.trim().lowercase()) {
    "search" -> Icons.Filled.Search
    "psychology" -> Icons.Filled.Psychology
    "add" -> Icons.Filled.Add
    "autoawesome" -> Icons.Filled.AutoAwesome
    "bolt" -> Icons.Filled.Bolt
    "trendingup" -> Icons.Filled.TrendingUp
    "weekend" -> Icons.Filled.Weekend
    "phoneandroid" -> Icons.Filled.PhoneAndroid
    "whatshot" -> Icons.Filled.Whatshot
    else -> Icons.Filled.AutoAwesome
}

@Composable
private fun AgentDiscoveryHeader(
    header: DiscoveryHeader?,
    onSearch: () -> Unit,
    onUniverse: () -> Unit,
    onCreate: () -> Unit,
) {
    val title = header?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.agent_inspiration_plaza)
    val icon = iconKeyToVector(header?.icon ?: "AutoAwesome")
    val tint = header?.tint ?: BrowserTint
    val actions = (
        header?.actions?.takeIf { it.isNotEmpty() } ?: listOf(
            DiscoveryAction("Search", "", "search", BrowserTint),
            DiscoveryAction("Psychology", "", "universe", MemoryTint),
            DiscoveryAction("Add", "", "publish", SkillTint),
        )
        ).filter {
        // Universe(My Ghost)未完成先隐藏 —— 服务端下发的同名 action 一并过滤,见 FeatureFlags
        com.apk.claw.android.FeatureFlags.UNIVERSE_ENABLED ||
            !it.action.trim().equals("universe", ignoreCase = true)
    }
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OctopusSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(tint.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(
                    title,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                actions.forEach { action ->
                    val label = action.text.takeIf { it.isNotBlank() } ?: actionLabel(action.action)
                    AgentActionPill(
                        icon = iconKeyToVector(action.icon),
                        text = label,
                        tint = action.tint,
                        modifier = Modifier.weight(1f),
                        onClick = { handleAction(action.action, onSearch, onUniverse, onCreate) },
                    )
                }
            }
        }
    }
}

/** action key → 本地化文案兜底。 */
@Composable
private fun actionLabel(action: String): String = when (action.trim().lowercase()) {
    "search" -> stringResource(R.string.agent_action_search)
    "universe" -> stringResource(R.string.agent_action_universe)
    "publish" -> stringResource(R.string.agent_action_publish)
    else -> stringResource(R.string.agent_action_search)
}

private fun handleAction(
    action: String,
    onSearch: () -> Unit,
    onUniverse: () -> Unit,
    onCreate: () -> Unit,
) {
    when (action.trim().lowercase()) {
        "search" -> onSearch()
        // 双保险:入口已在渲染层过滤,这里再拦一道,开关关着就不导航
        "universe" -> if (com.apk.claw.android.FeatureFlags.UNIVERSE_ENABLED) onUniverse()
        "publish" -> onCreate()
    }
}

@Composable
private fun AgentActionPill(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OctopusPill(icon = icon, text = text, tint = tint, modifier = modifier, onClick = onClick)
}

@Composable
private fun AgentTopicChips(
    topics: List<DiscoveryTopic>?,
    selectedTopic: String,
    onSelect: (String) -> Unit,
) {
    val list = topics?.takeIf { it.isNotEmpty() } ?: listOf(
        DiscoveryTopic("recommend", "", com.apk.claw.android.ui.compose.theme.OctopusTints.Hot),
        DiscoveryTopic("automation", "", RoutineTint),
        DiscoveryTopic("efficiency", "", SkillTint),
        DiscoveryTopic("life", "", CloudTint),
        DiscoveryTopic("learning", "", MemoryTint),
        DiscoveryTopic("device", "", WindowTint),
    )
    // 单行横向滑动:比 2 行网格更矮,省掉一整行高度(胶囊按内容自适应宽度,超出即横滑)。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        list.forEach { topic ->
            val label = topic.label.takeIf { it.isNotBlank() } ?: topicLabel(topic.key)
            OctopusTextPill(
                text = label,
                tint = topic.tint,
                selected = selectedTopic == topic.key,
                onClick = { onSelect(topic.key) },
            )
        }
    }
}

/** 圈子卡片：头像 + 名称 + 成员数 + 标签，带进入动画 */
@Composable
private fun CircleCard(
    circle: CirclePreview,
    index: Int,
    onOpenActivity: (Class<*>) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300, delayMillis = index * 80),
        label = "circle_alpha",
    )
    val offsetY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(300, delayMillis = index * 80),
        label = "circle_offset",
    )

    OctopusCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .graphicsLayer { translationY = offsetY },
        onClick = { onOpenActivity(SkillsActivity::class.java) },
    ) {
        Column(
            modifier = Modifier.padding(OctopusSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 头像
            val avatarDesc = stringResource(R.string.feat_explore_circle_avatar, stringResource(circle.nameRes))
            Box(
                modifier = Modifier.size(48.dp).background(circle.tint.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    circle.icon,
                    contentDescription = avatarDesc,
                    tint = circle.tint,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            // 名称
            Text(
                stringResource(circle.nameRes),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.bodyStrong,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(OctopusSpacing.xs))
            // 成员数
            Text(
                "${circle.members} ${stringResource(R.string.feat_explore_members)}",
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            // 标签
            val tagText = stringResource(circle.tagRes)
            Surface(
                shape = OctopusShape.small,
                color = when (circle.tagRes) {
                    R.string.circle_tag_hot -> com.apk.claw.android.ui.compose.theme.OctopusTints.Hot.copy(alpha = 0.12f)
                    else -> OctopusColors.Primary.copy(alpha = 0.10f)
                },
            ) {
                Text(
                    tagText,
                    modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    color = when (circle.tagRes) {
                        R.string.circle_tag_hot -> com.apk.claw.android.ui.compose.theme.OctopusTints.Hot
                        else -> OctopusColors.Primary
                    },
                    fontSize = OctopusType.tag,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 分区标题 + 图标 */
@Composable
private fun SectionHeaderWithAction(text: String, icon: ImageVector, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = OctopusSpacing.xs, end = OctopusSpacing.xs, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(text, color = OctopusColors.TextSecondary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}


private fun Context.open(target: Class<*>) {
    runCatching { startActivity(Intent(this, target)) }
}
