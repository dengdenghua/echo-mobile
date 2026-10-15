package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusThemeStyle
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.tvFocusable
import com.apk.claw.android.ui.compose.theme.tvOverscan

/**
 * 灵感广场 —— 双列灵感瀑布流，展示自动化技能、用法、作品卡片。
 *
 * 支持图文帖(小红书式,有图片封面)与小程序分享帖(渐变封面)混合展示。
 * 卡片点击跳详情页(PostDetailScreen),+ 号跳发帖页(CreatePostScreen)。
 */
private val tabs = listOf(
    R.string.agent_square_tab_recommend,
    R.string.agent_square_tab_following,
    R.string.agent_square_tab_hot,
)

@Composable
fun AgentSquareScreen(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onCreatePost: () -> Unit = {},
    onOpenPost: (String) -> Unit = {},
) {
    // 广场目录来自服务端 API（可后台随意改），null=加载中；离线又没有缓存时只显示本地技能。
    val remote by produceState<List<AgentPost>?>(initialValue = null) {
        value = SquareRepository.remoteFeed().orEmpty()
    }
    val local = remember { SquareRepository.localFeed() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
            .tvOverscan(),
    ) {
        AgentSquareTopBar(onBack, onOpenSearch, onCreatePost)
        CategoryTabs()
        AgentFeed(remote = remote, local = local, onOpenPost = onOpenPost)
    }
}

@Composable
private fun AgentSquareTopBar(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onCreatePost: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = OctopusColors.TextPrimary,
                modifier = Modifier.size(OctopusIconSize.large),
            )
        }
        Text(
            stringResource(R.string.agent_square_title),
            modifier = Modifier.weight(1f),
            color = OctopusColors.TextPrimary,
            fontSize = OctopusType.titleLg,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onOpenSearch) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.cd_search),
                tint = OctopusColors.TextPrimary,
                modifier = Modifier.size(OctopusIconSize.large),
            )
        }
        IconButton(onClick = onCreatePost) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_create),
                tint = OctopusColors.Primary,
                modifier = Modifier.size(OctopusIconSize.large),
            )
        }
    }
}

@Composable
private fun CategoryTabs() {
    var selected by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        tabs.forEachIndexed { index, tabRes ->
            val isSelected = index == selected
            Surface(
                shape = OctopusShape.capsule,
                color = if (isSelected) OctopusColors.Primary else OctopusColors.SurfaceDeep.copy(alpha = 0.5f),
                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
                modifier = Modifier.clickable { selected = index }.tvFocusable(),
            ) {
                Text(
                    stringResource(tabRes),
                    modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
                    color = if (isSelected) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
                    fontSize = OctopusType.body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AgentFeed(
    remote: List<AgentPost>?,
    local: List<AgentPost>,
    onOpenPost: (String) -> Unit,
) {
    // 加载中且无本地内容 → 居中转圈；否则本地技能在前 + 服务端目录在后，合成一个瀑布流。
    if (remote == null && local.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OctopusColors.Primary)
        }
        return
    }
    val posts = local + (remote ?: emptyList())
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(OctopusSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        verticalItemSpacing = OctopusSpacing.md,
    ) {
        items(posts, key = { it.id }) { post ->
            AgentPostCard(post, onClick = { onOpenPost(post.id) })
        }
    }
}

@Composable
@Suppress("LongMethod")
internal fun AgentPostCard(post: AgentPost, onClick: () -> Unit = {}) {
    val isMiniApp = post.kind == "mini-app"
    val hasImageCover = post.coverUrl.isNotBlank()
    val likeText = when {
        post.likesCount > 0 -> formatCount(post.likesCount)
        post.likes.isNotBlank() -> post.likes
        else -> ""
    }
    Surface(
        shape = OctopusShape.large,
        // 帖子是文字密集内容,用实底表面 —— 不跟随玻璃透明度,玻璃调到最透也可读。
        color = OctopusBackground.solidSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OctopusBackground.solidBorder),
        shadowElevation = OctopusThemeStyle.cardShadow(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .clickable(onClick = onClick)
            .tvFocusable(),
    ) {
        Column {
            // 封面:图文帖有图片封面优先用 AsyncImage;否则回退渐变封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(post.coverHeightDp.dp)
                    .background(Brush.verticalGradient(post.coverGradient)),
            ) {
                if (hasImageCover) {
                    AsyncImage(
                        model = post.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // 标签胶囊(优先显示"小程序"标识;否则用 post.tag)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(OctopusSpacing.sm)
                        .background(Color.Black.copy(alpha = 0.38f), OctopusShape.capsule)
                        .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(6.dp).background(post.tagColor, CircleShape))
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        if (isMiniApp) "小程序" else post.tag,
                        color = Color.White,
                        fontSize = OctopusType.tag,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // 应用帖徽标:可复刻 / N积分(TopEnd,与分类标签对角)
                if (post.appRef.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(OctopusSpacing.sm)
                            .background(OctopusColors.Primary.copy(alpha = 0.92f), OctopusShape.capsule)
                            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = OctopusColors.OnPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            if (post.priceCredits > 0) "${post.priceCredits}积分" else "可复刻",
                            color = OctopusColors.OnPrimary,
                            fontSize = OctopusType.tag,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // 多图指示器(右下角,images 数量 > 1 时显示)
                if (post.images.size > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(OctopusSpacing.sm)
                            .background(Color.Black.copy(alpha = 0.5f), OctopusShape.capsule)
                            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "1/${post.images.size}",
                            color = Color.White,
                            fontSize = OctopusType.tag,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(OctopusSpacing.md)) {
                Text(
                    post.title,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                )
                if (post.content.isNotBlank() && !isMiniApp) {
                    // 图文帖正文预览:1 行省略
                    Spacer(Modifier.height(OctopusSpacing.xs))
                    Text(
                        post.content,
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(OctopusSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(post.authorColor.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            post.authorInitial,
                            color = post.authorColor,
                            fontSize = OctopusType.tag,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        post.author,
                        modifier = Modifier.weight(1f),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (likeText.isNotBlank()) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = OctopusColors.TextMuted.copy(alpha = 0.6f),
                            modifier = Modifier.size(OctopusIconSize.small),
                        )
                        Spacer(Modifier.width(OctopusSpacing.xs))
                        Text(
                            likeText,
                            color = OctopusColors.TextMuted,
                            fontSize = OctopusType.tag,
                        )
                    }
                }
            }
        }
    }
}

/** 数值缩写:<1k 直显,>=1k 用 1.2w 形式。 */
private fun formatCount(count: Int): String = when {
    count < 1000 -> count.toString()
    count < 10000 -> String.format("%.1fk", count / 1000.0)
    else -> String.format("%.1fw", count / 10000.0)
}
