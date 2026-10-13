package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.registry.CommunitySquareApi
import com.apk.claw.android.registry.RegistryClient
import com.apk.claw.android.registry.mobileFit
import com.apk.claw.android.ui.compose.component.LoadFailedState
import com.apk.claw.android.ui.compose.component.OctopusCard
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * 市场(广场 → 市场子 tab)—— 三类可安装内容的统一货架:技能 / 插件 / 应用(小程序)。
 *
 * 纯货架:只放可下载安装的东西;社区帖子/灵感在「灵感」子 tab,不在这里重复。
 * 每类:分类筛选 + 排序[排行 | 最新 | 趋势] + 卡片流。分类用 registry 的 category 字段
 * (现成);排行/趋势依赖服务端下载量埋点(见任务清单),数据到位前按最新序占位。
 * 数据复用现有商城的 API,点击卡片跳对应商城完成安装,不在此重写安装管线。
 */

private val PillShape = RoundedCornerShape(50)
private val CardShape = RoundedCornerShape(12.dp)
private const val ACTIVE_ALPHA = 0.16f
private val KindIconSize = 16.dp
private val KindIconGap = 6.dp
private val CardIconBox = 40.dp
private val CardIconSize = 20.dp
private val CategoryFontSize = 12.sp
private val TagFontSize = 11.sp
private const val COUNT_K = 1000

/** 货架三类。 */
internal enum class MarketKind(val titleRes: Int, val icon: ImageVector, val tint: Color) {
    SKILL(R.string.market_kind_skill, Icons.Filled.Bolt, OctopusTints.Skill),
    PLUGIN(R.string.market_kind_plugin, Icons.Filled.Extension, OctopusTints.Routine),
    APP(R.string.market_kind_app, Icons.Filled.Apps, OctopusTints.Browser),
}

/** 排序维度。RANKING/TRENDING 需服务端下载量数据,未到位前退化为 LATEST 序。 */
internal enum class MarketSort(val titleRes: Int, val apiValue: String) {
    RANKING(R.string.market_sort_ranking, "downloads"),
    LATEST(R.string.market_sort_latest, "latest"),
    TRENDING(R.string.market_sort_trending, "trending"),
}

/** 三类归一的货架条目。downloads:累计下载量,仅小程序(走本服务 /square/assets)有真实值;
 *  技能/插件走 enterprise registry 无此埋点,恒为 0。 */
internal data class MarketItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String?,
    val version: String,
    val downloads: Int = 0,
)

@Composable
fun MarketTab(
    onOpenSkillStore: () -> Unit,
    onOpenPluginStore: () -> Unit,
    onOpenAppStore: () -> Unit,
    onOpenMySkills: () -> Unit,
    onOpenMyApps: () -> Unit,
) {
    var kind by remember { mutableStateOf(MarketKind.SKILL) }
    var category by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(MarketSort.LATEST) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MarketItem>>(emptyList()) }

    // 排序在服务端做(小程序按 sort=downloads/trending;趋势=下载速度需 created_at,客户端拿不到),
    // 故切换 sort 需重新拉取。切 kind 时的 category 复位放在 MarketKindTabs 的回调里。
    LaunchedEffect(kind, sort, reloadKey) {
        loading = true
        val result = loadMarket(kind, sort)
        items = result.getOrDefault(emptyList())
        failed = result.isFailure
        loading = false
    }

    val categories = remember(items) {
        items.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
    }
    // 列表已按服务端排序返回,这里只做本地分类过滤,不再二次排序。
    val shown = remember(items, category) {
        items.filter { category == null || it.category == category }
    }
    val openStore: () -> Unit = when (kind) {
        MarketKind.SKILL -> onOpenSkillStore
        MarketKind.PLUGIN -> onOpenPluginStore
        MarketKind.APP -> onOpenAppStore
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部「我的/已安装」入口(用户拍板放这):我的技能 / 我的小程序 —— 已装管理,非货架
        MyStuffRow(onOpenMySkills, onOpenMyApps)
        MarketKindTabs(kind) { kind = it; sort = MarketSort.LATEST; category = null }
        MarketSortRow(sort) { sort = it }
        if (categories.isNotEmpty()) {
            MarketCategoryRow(categories, category) { category = it }
        }
        MarketContent(
            MarketLoadState(loading, failed),
            shown,
            kind,
            onOpenStore = openStore,
            onRetry = { reloadKey++ },
        )
    }
}

/** 三类加载归一。失败(网络不通且没有缓存)返回 failure:界面显示失败状态 + 重试,不当成「没有内容」。
 *  小程序(APP)把 sort 透传服务端拿真实排序 + 下载量;技能/插件走 enterprise registry
 *  无 sort/无下载埋点,恒按其默认序(最新)返回,sort 对它们是无害的空操作。 */
private suspend fun loadMarket(kind: MarketKind, sort: MarketSort): Result<List<MarketItem>> = when (kind) {
    MarketKind.SKILL -> RegistryClient.listSkillsResult().map { list ->
        list.filter { it.mobileFit }.map { MarketItem(it.id, it.name, it.description, it.category, it.version) }
    }
    MarketKind.PLUGIN -> RegistryClient.listPluginsResult().map { list ->
        list.filter { it.mobileFit }.map { MarketItem(it.id, it.name, it.description, it.category, it.version) }
    }
    MarketKind.APP -> CommunitySquareApi.list(sort.apiValue).map { list ->
        list.map { MarketItem(it.effectiveSlug, it.name, it.description, it.category, it.version, it.downloadCount) }
    }
}

private data class MarketLoadState(val loading: Boolean, val failed: Boolean)

@Composable
private fun MarketContent(
    state: MarketLoadState,
    shown: List<MarketItem>,
    kind: MarketKind,
    onOpenStore: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = kind.tint)
        }
        state.failed -> LoadFailedState(
            title = stringResource(R.string.market_load_failed),
            message = stringResource(R.string.market_load_failed_desc),
            onRetry = onRetry,
        )
        shown.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.market_empty), color = OctopusColors.TextMuted, fontSize = OctopusType.body)
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            items(shown, key = { it.id }) { item -> MarketItemCard(item, kind, onOpenStore) }
        }
    }
}

@Composable
private fun MarketKindTabs(selected: MarketKind, onSelect: (MarketKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        MarketKind.entries.forEach { k ->
            val active = k == selected
            Row(
                modifier = Modifier
                    .clip(PillShape)
                    .background(if (active) k.tint.copy(alpha = ACTIVE_ALPHA) else Color.Transparent)
                    .border(1.dp, if (active) k.tint else OctopusColors.Border, PillShape)
                    .clickable { onSelect(k) }
                    .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    k.icon,
                    contentDescription = null,
                    tint = if (active) k.tint else OctopusColors.TextSecondary,
                    modifier = Modifier.size(KindIconSize),
                )
                Spacer(Modifier.size(KindIconGap))
                Text(
                    stringResource(k.titleRes),
                    color = if (active) k.tint else OctopusColors.TextSecondary,
                    fontSize = OctopusType.body,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MarketSortRow(selected: MarketSort, onSelect: (MarketSort) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        MarketSort.entries.forEach { s ->
            val active = s == selected
            Text(
                text = stringResource(s.titleRes),
                color = if (active) OctopusColors.Primary else OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(PillShape).clickable { onSelect(s) }
                    .padding(horizontal = OctopusSpacing.sm, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MarketCategoryRow(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs),
    ) {
        CategoryPill(stringResource(R.string.market_category_all), selected == null) { onSelect(null) }
        categories.forEach { c -> CategoryPill(c, selected == c) { onSelect(c) } }
    }
}

@Composable
private fun CategoryPill(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (active) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
        fontSize = CategoryFontSize,
        modifier = Modifier.clip(PillShape)
            .background(if (active) OctopusColors.Primary else OctopusColors.SurfaceVariant)
            .clickable { onClick() }.padding(horizontal = OctopusSpacing.sm, vertical = 4.dp),
    )
}

@Composable
private fun MarketItemCard(item: MarketItem, kind: MarketKind, onClick: () -> Unit) {
    OctopusCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(OctopusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(CardIconBox).clip(CardShape).background(kind.tint.copy(alpha = ACTIVE_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kind.icon, contentDescription = null, tint = kind.tint, modifier = Modifier.size(CardIconSize))
            }
            Spacer(Modifier.size(OctopusSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name.ifBlank { item.id },
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        color = OctopusColors.TextSecondary,
                        fontSize = OctopusType.caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = buildList {
                    item.category?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (item.downloads > 0) {
                        // 下载量紧凑显示:≥1000 折成 "1.2k"(内联而非独立函数,避免 TooManyFunctions)。
                        val label = if (item.downloads >= COUNT_K) {
                            "%.1fk".format(item.downloads / COUNT_K.toFloat())
                        } else {
                            item.downloads.toString()
                        }
                        add("↓ $label")
                    }
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta.joinToString("  ·  "), color = kind.tint, fontSize = TagFontSize)
                }
            }
        }
    }
}

@Composable
private fun MyStuffRow(onMySkills: () -> Unit, onMyApps: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        MyStuffPill(Icons.Filled.Bolt, stringResource(R.string.market_mine_skills), Modifier.weight(1f), onMySkills)
        MyStuffPill(Icons.Filled.Apps, stringResource(R.string.market_mine_apps), Modifier.weight(1f), onMyApps)
    }
}

@Composable
private fun MyStuffPill(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(OctopusColors.SurfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = OctopusColors.TextSecondary,
            modifier = Modifier.size(KindIconSize),
        )
        Spacer(Modifier.size(KindIconGap))
        Text(text, color = OctopusColors.TextPrimary, fontSize = OctopusType.caption, fontWeight = FontWeight.Bold)
    }
}
