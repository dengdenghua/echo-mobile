package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.apk.claw.android.ui.compose.component.formatCompactCount
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.launch

/**
 * 用户主页(小红书式)。
 *
 * - 顶部:返回 + 用户名
 * - 用户卡:头像(initial) + 昵称 + 关注/粉丝/作品统计 + 关注/取消关注按钮
 * - 作品列表:双列瀑布流(只显示该用户发布的帖)
 * - 未登录时只读,关注按钮失效
 */
@Composable
fun UserProfileScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit = {},
    onMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<SquarePostApi.UserProfileResult?>(null) }
    var following by remember { mutableStateOf(false) }
    var followersCount by remember { mutableStateOf(0) }
    var followBusy by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0=作品 1=赞过 2=收藏
    var likedPosts by remember { mutableStateOf<List<AgentPost>?>(null) }
    var favoritePosts by remember { mutableStateOf<List<AgentPost>?>(null) }

    LaunchedEffect(userId) {
        loading = true
        failed = false
        try {
            val r = SquarePostApi.userProfile(userId)
            profile = r
            following = r.user.isFollowing
            followersCount = r.user.followersCount
        } catch (e: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(userId, selectedTab) {
        if (selectedTab == 1 && likedPosts == null) {
            try {
                val dtos = SquarePostApi.userLikedPosts(userId)
                likedPosts = dtos.map { it.toAgentPost() }
            } catch (e: Exception) {
                likedPosts = emptyList()
            }
        }
        if (selectedTab == 2 && favoritePosts == null) {
            try {
                val dtos = SquarePostApi.userFavoritePosts(userId)
                favoritePosts = dtos.map { it.toAgentPost() }
            } catch (e: Exception) {
                favoritePosts = emptyList()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ProfileTopBar(name = profile?.user?.nickname.orEmpty(), onBack = onBack)
        when {
            loading -> EchoLoadingPanel(Modifier.fillMaxSize())
            failed -> CenterText(stringResource(R.string.user_profile_load_failed))
            else -> {
                val u = profile?.user
                if (u != null) {
                    ProfileHeader(
                        nickname = u.nickname.ifBlank { "匿名用户" },
                        posts = profile?.posts?.size ?: 0,
                        followingCount = u.followingCount,
                        followersCount = followersCount,
                        isFollowing = following,
                        followBusy = followBusy,
                        canFollow = AccountStore.token.isNotBlank(),
                        onToggleFollow = {
                            scope.launch {
                                followBusy = true
                                try {
                                    val r = if (following) {
                                        SquarePostApi.unfollow(userId)
                                    } else {
                                        SquarePostApi.follow(userId)
                                    }
                                    following = r.following
                                    followersCount = r.followersCount
                                } catch (e: Exception) {
                                    onMessage("操作失败: ${e.message}")
                                } finally {
                                    followBusy = false
                                }
                            }
                        },
                    )
                }
                // ── Tab 切换:作品 / 赞过 / 收藏 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                ) {
                    val tabLabels = listOf("作品", "赞过", "收藏")
                    tabLabels.forEachIndexed { index, label ->
                        val isSelected = index == selectedTab
                        Surface(
                            shape = OctopusShape.capsule,
                            color = if (isSelected) OctopusColors.Primary else Color.Transparent,
                            border = if (isSelected) null else BorderStroke(1.dp, OctopusColors.Border),
                            modifier = Modifier.clickable { selectedTab = index },
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
                                color = if (isSelected) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
                                fontSize = OctopusType.body,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                    }
                }

                // ── 内容列表 ──
                val displayPosts = when (selectedTab) {
                    0 -> profile?.posts?.map { it.toAgentPost() }.orEmpty()
                    1 -> likedPosts.orEmpty()
                    2 -> favoritePosts.orEmpty()
                    else -> emptyList()
                }
                if (displayPosts.isEmpty()) {
                    val msg = when (selectedTab) {
                        1 -> "还没有赞过任何内容"
                        2 -> "还没有收藏任何内容"
                        else -> stringResource(R.string.user_profile_no_posts)
                    }
                    CenterText(msg)
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(OctopusSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                        verticalItemSpacing = OctopusSpacing.md,
                    ) {
                        items(displayPosts, key = { "${selectedTab}_${it.id}" }) { post ->
                            ProfilePostCard(post, onClick = { onOpenPost(post.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTopBar(name: String, onBack: () -> Unit) {
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
            name.ifBlank { stringResource(R.string.user_profile_title) },
            modifier = Modifier.weight(1f),
            color = OctopusColors.TextPrimary,
            fontSize = OctopusType.titleLg,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileHeader(
    nickname: String,
    posts: Int,
    followingCount: Int,
    followersCount: Int,
    isFollowing: Boolean,
    followBusy: Boolean,
    canFollow: Boolean,
    onToggleFollow: () -> Unit,
) {
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.solidSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OctopusBackground.solidBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
    ) {
        Row(
            modifier = Modifier.padding(OctopusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像:用首字母 + 色块
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(OctopusTints.Skill, OctopusTints.Routine)
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    nickname.take(1).ifBlank { "U" },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(OctopusSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    nickname,
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(OctopusSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.user_profile_posts, posts),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                    )
                    Spacer(Modifier.width(OctopusSpacing.md))
                    Text(
                        stringResource(R.string.user_profile_following, followingCount),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                    )
                    Spacer(Modifier.width(OctopusSpacing.md))
                    Text(
                        stringResource(R.string.user_profile_followers, followersCount),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                    )
                }
            }
            if (canFollow) {
                if (isFollowing) {
                    OutlinedButton(
                        onClick = onToggleFollow,
                        enabled = !followBusy,
                        shape = OctopusShape.capsule,
                    ) {
                        Text(stringResource(R.string.user_profile_following_tag))
                    }
                } else {
                    Button(
                        onClick = onToggleFollow,
                        enabled = !followBusy,
                        shape = OctopusShape.capsule,
                        colors = ButtonDefaults.buttonColors(containerColor = OctopusColors.Primary),
                    ) {
                        Text(stringResource(R.string.user_profile_follow))
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = OctopusColors.TextMuted, fontSize = OctopusType.body)
    }
}

@Composable
private fun ProfilePostCard(post: AgentPost, onClick: () -> Unit) {
    val hasImageCover = post.coverUrl.isNotBlank()
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.solidSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OctopusBackground.solidBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .clickable(onClick = onClick),
    ) {
        Column {
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
                if (post.likesCount > 0) {
                    Spacer(Modifier.height(OctopusSpacing.xs))
                    Text(
                        "♥ ${formatCount(post.likesCount)}",
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.tag,
                    )
                }
            }
        }
    }
}

private fun formatCount(n: Int): String = formatCompactCount(n)
