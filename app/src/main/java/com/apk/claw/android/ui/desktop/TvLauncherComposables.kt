package com.apk.claw.android.ui.desktop

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ── 卡片 / Banner 规格常量(top-level 具名:满足 MagicNumber 豁免) ──
private const val CARD_SIZE = 140              // 卡片默认边长(dp)
private const val CARD_CORNER = 16             // 卡片圆角(dp)
private const val CARD_ICON_SIZE = 48          // 卡片内图标尺寸(dp)
private const val BANNER_HEIGHT = 200          // Banner 高度(dp)
private const val BANNER_AUTOPLAY_MS = 5000L   // Banner 自动轮播间隔
private const val CARD_FOCUS_SCALE = 1.10f     // 聚焦放大倍数
private const val CARD_FOCUS_SHADOW = 16f      // 聚焦投影高度
private const val INSTALLED_APPS_LIMIT = 10    // 已安装应用展示数量上限(避免几百个卡 UI)

/** TV 启动器卡片数据。 */
data class TvLauncherItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: ImageVector? = null,
    val iconRes: Int? = null,
    val accentColor: Color = Holo.TextHud,
    val badge: String? = null,
    val action: () -> Unit = {},
)

/** 顶部推荐 Banner 数据。 */
data class TvFeaturedItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val backgroundGradient: List<Color>,
    val action: () -> Unit = {},
)

/**
 * TV 启动器行:标题 + 横向滚动卡片列表。每行独立横向滚动。
 * [firstItemFocus] 非空时应用到第一张卡片(供 TV 遥控器进入桌面时初始聚焦)。
 */
@Composable
fun TvLauncherRow(
    title: String,
    items: List<TvLauncherItem>,
    onItemClick: (TvLauncherItem) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocus: FocusRequester? = null,
) {
    Column(modifier) {
        Text(
            title,
            color = Holo.TextHud,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                TvLauncherCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    modifier = if (firstItemFocus != null && index == 0) {
                        Modifier.focusRequester(firstItemFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * 单个卡片:聚焦时 1.1x 放大(spring 动画)+ 2dp 高亮边框 + 16dp 投影 + 圆角 16dp 深色背景。
 * 内部:icon(48dp)+ title + subtitle;右上角 badge(若有)。
 */
@Composable
fun TvLauncherCard(
    item: TvLauncherItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) CARD_FOCUS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "cardScale",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 2.dp else 0.dp,
        label = "cardBorder",
    )
    Box(
        modifier
            .size(CARD_SIZE.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) CARD_FOCUS_SHADOW else 0f
                shape = RoundedCornerShape(CARD_CORNER.dp)
                clip = false
            }
            .clip(RoundedCornerShape(CARD_CORNER.dp))
            .background(Holo.Panel.copy(alpha = 0.88f))
            .border(borderWidth, if (focused) Holo.Accent else Color.Transparent, RoundedCornerShape(CARD_CORNER.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                item.icon != null -> Icon(
                    item.icon, contentDescription = null,
                    tint = item.accentColor, modifier = Modifier.size(CARD_ICON_SIZE.dp),
                )
                item.iconRes != null -> Icon(
                    painterResource(item.iconRes), contentDescription = null,
                    tint = item.accentColor, modifier = Modifier.size(CARD_ICON_SIZE.dp),
                )
                else -> Icon(
                    Icons.Filled.Apps, contentDescription = null,
                    tint = item.accentColor, modifier = Modifier.size(CARD_ICON_SIZE.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                item.title, color = Holo.TextHud, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle, color = Holo.TextSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 右上角 badge(如「新」「热」)
        item.badge?.let { badge ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape)
                    .background(Holo.Accent).padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(badge, color = Holo.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 顶部推荐 Banner:渐变背景(无图片依赖),左下角大标题 + 副标题,底部圆点指示器,
 * 5 秒自动切换。聚焦时边框高亮(D-pad 可选)。
 */
@Composable
fun TvFeaturedBanner(
    items: List<TvFeaturedItem>,
    onItemClick: (TvFeaturedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })
    // 5 秒自动切换到下一页(循环)。
    LaunchedEffect(items.size) {
        while (true) {
            delay(BANNER_AUTOPLAY_MS)
            val next = (pagerState.currentPage + 1) % items.size
            runCatching { pagerState.animateScrollToPage(next) }
        }
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(BANNER_HEIGHT.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clip(RoundedCornerShape(CARD_CORNER.dp))
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) Holo.Accent else Color.Transparent,
                RoundedCornerShape(CARD_CORNER.dp),
            ),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(item.backgroundGradient))
                    .clickable { onItemClick(item) },
            ) {
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(
                        item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        // 底部圆点指示器:当前页高亮放大。
        Row(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(items.size) { i ->
                val active = i == pagerState.currentPage
                Box(
                    Modifier.size(if (active) 8.dp else 6.dp).clip(CircleShape)
                        .background(if (active) Holo.Accent else Color.White.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

/**
 * 异步加载已安装应用(后台线程,避免卡 UI)。按名排序后取前 [limit] 个,
 * 用通用 [Icons.Filled.Apps] 图标占位(不加载真实 app icon Drawable,保持简单)。
 * 点击启动对应 app。
 */
@Composable
fun rememberInstalledApps(context: Context, limit: Int = INSTALLED_APPS_LIMIT): List<TvLauncherItem> {
    val state = produceState(initialValue = emptyList<TvLauncherItem>()) {
        val pm = context.packageManager
        val apps = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val ris = pm.queryIntentActivities(intent, 0)
            ris.map { ri ->
                TvLauncherItem(
                    id = ri.activityInfo.packageName,
                    title = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("应用"),
                    subtitle = "应用",
                    icon = Icons.Filled.Apps,
                    accentColor = Holo.TextHud,
                    action = {
                        val launchIntent = pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
                        if (launchIntent != null) context.startActivity(launchIntent)
                    },
                )
            }.sortedBy { it.title }.take(limit)
        }
        value = apps
    }
    return state.value
}
