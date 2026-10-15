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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.formatCompactCount
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 广场搜索页(小红书式)。
 *
 * - 顶部搜索框(回车触发搜索)
 * - 结果用双列瀑布流(复用 [AgentPost] 卡片样式)
 * - 空态/无结果/加载中/失败四种状态
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SearchTopBar(
            query = query,
            onQueryChange = { query = it },
            onBack = onBack,
            onSubmit = {
                if (query.isNotBlank()) submitted = query.trim()
            },
        )
        if (submitted.isBlank()) {
            EmptyState(text = stringResource(R.string.square_search_empty))
        } else {
            SearchResults(q = submitted, onOpenPost = onOpenPost)
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
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
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            // M3 输入框最小高度 56dp；强行压到 48dp 会把占位文字切掉一半
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            placeholder = { Text(stringResource(R.string.square_search_hint)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = OctopusColors.TextMuted,
                )
            },
            singleLine = true,
            shape = OctopusShape.capsule,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = OctopusColors.SurfaceDeep,
                unfocusedContainerColor = OctopusColors.SurfaceDeep,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        // 这是搜索提交，不是发布；给足触控区并与右缘留出间距
        TextButton(onClick = onSubmit, modifier = Modifier.padding(start = OctopusSpacing.xs)) {
            Text(
                stringResource(R.string.square_search_title),
                color = OctopusColors.Primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SearchResults(q: String, onOpenPost: (String) -> Unit) {
    val state by produceState<SearchUiState>(initialValue = SearchUiState.Loading, q) {
        value = try {
            val dtos = withContext(Dispatchers.IO) { SquarePostApi.search(q) }
            SearchUiState.Success(dtos.map { it.toAgentPost() })
        } catch (e: Exception) {
            SearchUiState.Failed
        }
    }
    when (state) {
        SearchUiState.Loading -> EchoLoadingPanel(Modifier.fillMaxSize())
        SearchUiState.Failed -> EmptyState(text = stringResource(R.string.square_search_failed))
        is SearchUiState.Success -> {
            val posts = (state as SearchUiState.Success).posts
            if (posts.isEmpty()) {
                EmptyState(text = stringResource(R.string.square_search_no_result))
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(OctopusSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                    verticalItemSpacing = OctopusSpacing.md,
                ) {
                    items(posts, key = { it.id }) { post ->
                        SearchPostCard(post, onClick = { onOpenPost(post.id) })
                    }
                }
            }
        }
    }
}

private sealed interface SearchUiState {
    data object Loading : SearchUiState
    data object Failed : SearchUiState
    data class Success(val posts: List<AgentPost>) : SearchUiState
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = OctopusColors.TextMuted, fontSize = OctopusType.body)
    }
}

/** 搜索结果卡片(轻量版,无作者头像,只有封面+标题+点赞)。 */
@Composable
private fun SearchPostCard(post: AgentPost, onClick: () -> Unit) {
    val isMiniApp = post.kind == "mini-app"
    val hasImageCover = post.coverUrl.isNotBlank()
    val likeText = when {
        post.likesCount > 0 -> formatCount(post.likesCount)
        post.likes.isNotBlank() -> post.likes
        else -> ""
    }
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
                if (likeText.isNotBlank()) {
                    Spacer(Modifier.height(OctopusSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            post.author,
                            modifier = Modifier.weight(1f),
                            color = OctopusColors.TextMuted,
                            fontSize = OctopusType.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "♥ $likeText",
                            color = OctopusColors.TextMuted,
                            fontSize = OctopusType.tag,
                        )
                    }
                }
            }
        }
    }
}

private fun formatCount(n: Int): String = formatCompactCount(n)
