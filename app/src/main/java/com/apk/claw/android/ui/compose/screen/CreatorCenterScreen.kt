package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.account.AccountRepository
import com.apk.claw.android.account.CreatorAsset
import com.apk.claw.android.account.CreatorDashboardData
import com.apk.claw.android.account.CreatorLeader
import com.apk.claw.android.account.CreatorRankingData
import com.apk.claw.android.account.CreatorRevenueTxn
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 配色别名(跟随主题切换) ────────────────────────────────
private val PrimaryColor get() = OctopusColors.Primary
private val AccentColor get() = OctopusColors.Accent
private val SuccessColor get() = OctopusColors.Success
private val WarningColor get() = OctopusColors.Warning
private val SurfaceVariantColor get() = OctopusColors.SurfaceVariant
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted

private val DATE_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.US)

/**
 * 创作者中心 —— 数据飞轮入口。
 *
 * 展示创作者的总收益/下载/发布数,已发布作品及审核状态,近 30 天收益流水,
 * 以及创作者排行榜(TOP 50)。所有数据来自 [AccountRepository.creatorDashboard]
 * 与 [AccountRepository.creatorRanking],后端在用户每次付费时按 70% 比例
 * 给作者账户发放积分(CREATOR_REVENUE_SHARE)。
 */
@Composable
fun CreatorCenterScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var dashboard by remember { mutableStateOf<CreatorDashboardData?>(null) }
    var ranking by remember { mutableStateOf<CreatorRankingData?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            loadFailed = false
            val d = AccountRepository.creatorDashboard()
            val r = AccountRepository.creatorRanking()
            val dd = d.getOrNull()
            val rr = r.getOrNull()
            if (dd == null && rr == null) {
                loadFailed = true
                val msg = d.exceptionOrNull()?.message ?: r.exceptionOrNull()?.message
                if (!msg.isNullOrEmpty()) onMessage(msg)
            } else {
                dashboard = dd?.data
                ranking = rr?.data
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding(),
    ) {
        CreatorTopBar(onBack = onBack, onReload = { reload() })

        when {
            loading -> EchoLoadingPanel(Modifier.fillMaxSize())
            loadFailed -> EmptyState(
                message = stringResource(R.string.creator_center_load_failed),
                actionText = stringResource(R.string.creator_center_retry),
                onAction = { reload() },
            )
            dashboard == null && ranking == null -> EmptyState(
                message = stringResource(R.string.creator_center_empty),
                actionText = null,
                onAction = null,
            )
            else -> CreatorCenterContent(dashboard = dashboard, ranking = ranking)
        }
    }
}

@Composable
private fun CreatorTopBar(onBack: () -> Unit, onReload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = OctopusSpacing.sm,
                end = OctopusSpacing.lg,
                top = OctopusSpacing.sm,
                bottom = OctopusSpacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = TextPrimary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.creator_center_title),
                color = TextPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text(
                stringResource(R.string.creator_center_entry_desc),
                color = TextMuted, fontSize = OctopusType.caption, maxLines = 1,
            )
        }
        IconButton(onClick = onReload) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
private fun CreatorCenterContent(
    dashboard: CreatorDashboardData?,
    ranking: CreatorRankingData?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        item { HeroMetrics(dashboard) }
        renderAssets(dashboard)
        renderRevenue(dashboard)
        renderRanking(ranking)
    }
}

private fun LazyListScope.renderAssets(dashboard: CreatorDashboardData?) {
    item { SectionHeader(stringResource(R.string.creator_center_section_assets)) }
    val assets = dashboard?.assets.orEmpty()
    if (assets.isEmpty()) {
        item { InlineEmpty(stringResource(R.string.creator_center_assets_empty)) }
    } else {
        items(assets, key = { it.id.ifEmpty { it.slug } }) { a -> AssetRow(a) }
    }
}

private fun LazyListScope.renderRevenue(dashboard: CreatorDashboardData?) {
    item { SectionHeader(stringResource(R.string.creator_center_section_revenue)) }
    val rev = dashboard?.recentRevenue.orEmpty()
    if (rev.isEmpty()) {
        item { InlineEmpty(stringResource(R.string.creator_center_revenue_empty)) }
    } else {
        items(rev, key = { "${it.ts}-${it.refId}" }) { t -> RevenueRow(t) }
    }
}

private fun LazyListScope.renderRanking(ranking: CreatorRankingData?) {
    item { SectionHeader(stringResource(R.string.creator_center_section_ranking)) }
    ranking?.let { r -> item { MyRankRow(r) } }
    val leaders = ranking?.leaders.orEmpty()
    if (leaders.isNotEmpty()) {
        items(leaders, key = { it.userId }) { l -> LeaderRow(l) }
    }
}

// ── Hero 指标 ─────────────────────────────────────────

@Composable
private fun HeroMetrics(dashboard: CreatorDashboardData?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HeroMetricCell(
            label = stringResource(R.string.creator_center_metric_earnings),
            value = (dashboard?.totalEarnings ?: 0).toString(),
            icon = Icons.Filled.Star,
            tint = PrimaryColor,
            modifier = Modifier.weight(1f),
        )
        HeroMetricCell(
            label = stringResource(R.string.creator_center_metric_downloads),
            value = (dashboard?.totalDownloads ?: 0).toString(),
            icon = Icons.Filled.CloudDownload,
            tint = AccentColor,
            modifier = Modifier.weight(1f),
        )
        HeroMetricCell(
            label = stringResource(R.string.creator_center_metric_published),
            value = (dashboard?.publishedCount ?: 0).toString(),
            icon = Icons.Filled.EmojiEvents,
            tint = WarningColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HeroMetricCell(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val shape = OctopusShape.large
    Box(
        modifier = modifier
            .clip(shape)
            .background(OctopusBackground.cardSurface, shape)
            .border(1.dp, OctopusBackground.cardBorder, shape)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text(label, color = TextMuted, fontSize = OctopusType.tag, maxLines = 1)
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            Text(
                value,
                color = TextPrimary, fontSize = OctopusType.headline,
                fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

// ── 分区标题 ──────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = OctopusType.label,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = OctopusSpacing.sm, bottom = OctopusSpacing.xs),
    )
}

// ── 作品行(内联状态徽章) ────────────────────────────────

@Composable
private fun AssetRow(asset: CreatorAsset) {
    val shape = OctopusShape.large
    val (badgeText, badgeColor) = statusBadge(asset.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OctopusBackground.cardSurface, shape)
            .border(1.dp, OctopusBackground.cardBorder, shape)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                asset.name.ifEmpty { asset.slug },
                color = TextPrimary, fontSize = OctopusType.body,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Spacer(Modifier.height(OctopusSpacing.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
            ) {
                Surface(shape = OctopusShape.small, color = badgeColor.copy(alpha = 0.12f)) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                        color = badgeColor,
                        fontSize = OctopusType.micro,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Text(
                    stringResource(R.string.creator_center_downloads_suffix, asset.downloads),
                    color = TextMuted, fontSize = OctopusType.caption, maxLines = 1,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "+${asset.earnings}",
                color = SuccessColor, fontSize = OctopusType.bodyStrong,
                fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text(
                stringResource(R.string.creator_center_earnings_suffix, asset.earnings),
                color = TextMuted, fontSize = OctopusType.tag, maxLines = 1,
            )
        }
    }
}

@Composable
private fun statusBadge(status: String): Pair<String, Color> = when (status.lowercase()) {
    "published", "ok", "approved" ->
        stringResource(R.string.creator_center_status_published) to SuccessColor
    "pending", "review" ->
        stringResource(R.string.creator_center_status_pending) to WarningColor
    "rejected", "blocked" ->
        stringResource(R.string.creator_center_status_rejected) to OctopusColors.Error
    else -> status.ifEmpty { "-" } to TextMuted
}

// ── 收益流水行 ────────────────────────────────────────

@Composable
private fun RevenueRow(txn: CreatorRevenueTxn) {
    val shape = OctopusShape.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OctopusBackground.cardSurface, shape)
            .border(1.dp, OctopusBackground.cardBorder, shape)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(SuccessColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = SuccessColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                txn.detail.ifEmpty { txn.refId.ifEmpty { "收益" } },
                color = TextPrimary, fontSize = OctopusType.body, maxLines = 1,
            )
            Text(
                if (txn.ts > 0) DATE_FMT.format(Date(txn.ts)) else "",
                color = TextMuted, fontSize = OctopusType.caption, maxLines = 1,
            )
        }
        Text(
            "+${txn.delta}",
            color = SuccessColor, fontSize = OctopusType.bodyStrong,
            fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

// ── 排行榜 ────────────────────────────────────────────

@Composable
private fun MyRankRow(data: CreatorRankingData) {
    val shape = OctopusShape.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PrimaryColor.copy(alpha = 0.10f), shape)
            .border(1.dp, PrimaryColor.copy(alpha = 0.30f), shape)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = PrimaryColor,
            modifier = Modifier.size(OctopusIconSize.medium),
        )
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.creator_center_my_rank),
                color = TextSecondary, fontSize = OctopusType.caption, maxLines = 1,
            )
            Text(
                if (data.myRank > 0) stringResource(R.string.creator_center_rank_format, data.myRank)
                else stringResource(R.string.creator_center_no_rank),
                color = TextPrimary, fontSize = OctopusType.bodyStrong,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
        Text(
            "+${data.myEarnings}",
            color = PrimaryColor, fontSize = OctopusType.bodyStrong,
            fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

@Composable
private fun LeaderRow(leader: CreatorLeader) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(SurfaceVariantColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                leader.displayName.take(2).uppercase(),
                color = TextMuted, fontSize = OctopusType.tag,
                fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                leader.displayName,
                color = TextPrimary, fontSize = OctopusType.body, maxLines = 1,
            )
            Text(
                "${leader.works} 作品 · ${leader.downloads} 下载",
                color = TextMuted, fontSize = OctopusType.caption, maxLines = 1,
            )
        }
        Text(
            "+${leader.earnings}",
            color = SuccessColor, fontSize = OctopusType.body,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

// ── 空态 ──────────────────────────────────────────────

@Composable
private fun InlineEmpty(message: String) {
    val shape = OctopusShape.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OctopusBackground.cardSurface, shape)
            .border(1.dp, OctopusBackground.cardBorder, shape)
            .padding(OctopusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = TextMuted, fontSize = OctopusType.caption, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmptyState(
    message: String,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = TextMuted, fontSize = OctopusType.body, textAlign = TextAlign.Center)
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(OctopusSpacing.md))
                Surface(
                    shape = OctopusShape.medium,
                    color = PrimaryColor.copy(alpha = 0.12f),
                    modifier = Modifier.clickable(onClick = onAction),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = PrimaryColor,
                            modifier = Modifier.size(OctopusIconSize.small),
                        )
                        Spacer(Modifier.width(OctopusSpacing.xs))
                        Text(
                            actionText,
                            color = PrimaryColor,
                            fontSize = OctopusType.body,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
