@file:Suppress("TooManyFunctions")

package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.formatCompactCount
import com.apk.claw.android.account.AccountFeature
import com.apk.claw.android.ui.account.SignInPrompt
import com.apk.claw.android.plugin.SubscriptionGate
import com.apk.claw.android.registry.CommunityMiniAppInstaller
import com.apk.claw.android.registry.CommunitySquareApi
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.EchoProgressLineInlineWidth
import com.apk.claw.android.ui.compose.component.LoadFailedState
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.tvFocusable
import com.apk.claw.android.ui.compose.theme.tvOverscan
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/**
 * 帖子详情页(小红书式)。
 *
 * 展示:大图轮播 + 标题 + 正文 + 作者卡(点击跳主页)+ 点赞/评论/收藏按钮 + 评论列表 + 发评论输入框。
 * 数据通过 [SquarePostApi.postDetail] 拉取,点赞/评论/收藏实时调 API 并本地更新状态。
 */
@Composable
fun PostDetailScreen(
    postId: String,
    onBack: () -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    onMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var post by remember { mutableStateOf<AgentPost?>(null) }
    var comments by remember { mutableStateOf<List<SquarePostApi.CommentDto>>(emptyList()) }
    var newComment by remember { mutableStateOf("") }
    var acquiring by remember { mutableStateOf(false) }
    var showPayConfirm by remember { mutableStateOf(false) }
    var subscribing by remember { mutableStateOf(false) }
    var showSubConfirm by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<String?>(null) }  // 回复的评论 id,null=顶级评论
    var showShareDialog by remember { mutableStateOf(false) }
    var tryRunHtml by remember { mutableStateOf<String?>(null) }  // null=不显示,非null=显示 WebView
    var tryRunLoading by remember { mutableStateOf(false) }
    var forking by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            loadFailed = false
            try {
                val detail = SquarePostApi.postDetail(postId)
                val dto = detail.post
                post = dto.toAgentPost()
                val cmts = SquarePostApi.listComments(postId)
                comments = cmts.comments
            } catch (e: Exception) {
                // 原始异常（如 connection closed）只进日志；页面上给本地化的失败状态和重试
                XLog.w("PostDetail", "load post $postId failed", e)
                loadFailed = true
            }
            loading = false
        }
    }

    fun doAcquire() {
        scope.launch {
            acquiring = true
            try {
                val r = SquarePostApi.acquire(postId)
                if (r.appKind == "mini-app" && r.app != null) {
                    val download = CommunitySquareApi.parseDownloadPayload(r.app)
                    val err = CommunityMiniAppInstaller.install(ctx, download)
                    if (err != null) {
                        onMessage(ctx.getString(R.string.post_detail_install_failed, err))
                    } else {
                        onMessage(ctx.getString(R.string.post_detail_acquired_app, download.name))
                        post = post?.copy(owned = true)
                    }
                } else {
                    // routine 等类型的落地由后续增量处理,先标记已获取
                    onMessage(ctx.getString(R.string.post_detail_acquired))
                    post = post?.copy(owned = true)
                }
            } catch (e: Exception) {
                onMessage(e.message ?: ctx.getString(R.string.post_detail_acquire_failed))
            }
            acquiring = false
        }
    }

    fun doSubscribe() {
        scope.launch {
            subscribing = true
            try {
                val r = SquarePostApi.subscribe(postId)
                if (r.appKind == "mini-app" && r.app != null) {
                    val download = CommunitySquareApi.parseDownloadPayload(r.app)
                    val err = CommunityMiniAppInstaller.install(ctx, download)
                    if (err != null) {
                        onMessage(ctx.getString(R.string.post_detail_install_failed, err))
                        subscribing = false
                        return@launch
                    }
                }
                if (r.appRef.isNotBlank()) SubscriptionGate.mark(r.appRef)  // 标记订阅制,打开时校验
                val doneRes = if (r.duplicate) R.string.post_detail_sub_already else R.string.post_detail_sub_success
                onMessage(ctx.getString(doneRes))
                post = post?.copy(subActive = true)
            } catch (e: Exception) {
                onMessage(e.message ?: ctx.getString(R.string.post_detail_sub_failed))
            }
            subscribing = false
        }
    }

    LaunchedEffect(postId) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .tvOverscan(),
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OctopusSpacing.sm, end = OctopusSpacing.lg, top = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = OctopusColors.TextPrimary,
                )
            }
            Text(
                stringResource(R.string.post_detail_title),
                modifier = Modifier.weight(1f),
                color = OctopusColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            // 分享按钮
            IconButton(onClick = { showShareDialog = true }) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.post_detail_share),
                    tint = OctopusColors.TextPrimary,
                )
            }
        }

        when {
            loading -> EchoLoadingPanel()
            loadFailed || post == null -> LoadFailedState(
                title = stringResource(R.string.post_detail_load_failed),
                message = stringResource(R.string.post_detail_load_failed_desc),
                onRetry = { reload() },
            )
            else -> {
                val p = post!!
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = OctopusSpacing.lg),
                ) {
                    // ── 图片轮播 ──
                    if (p.images.isNotEmpty()) {
                        item { ImageCarousel(p.images) }
                    } else if (p.coverUrl.isNotBlank()) {
                        item { SingleCover(p.coverUrl) }
                    }

                    // ── 标题 + 正文 + 作者 ──
                    item { PostBody(p, onOpenAuthor = onOpenAuthor) }

                    // ── 可复刻应用帖:订阅制显示订阅卡,否则复刻/付费卡 ──
                    if (p.appRef.isNotBlank()) {
                        item {
                            if (p.subPriceCredits > 0) {
                                SubscribeCard(
                                    post = p,
                                    subscribing = subscribing,
                                    onSubscribe = {
                                        if (SignInPrompt.ensure(ctx, AccountFeature.COMMUNITY)) showSubConfirm = true
                                    },
                                )
                            } else {
                                AppAcquireCard(
                                    post = p,
                                    acquiring = acquiring,
                                    onAcquire = {
                                        when {
                                            !SignInPrompt.ensure(ctx, AccountFeature.COMMUNITY) -> Unit
                                            p.priceCredits > 0 && !p.owned -> showPayConfirm = true
                                            else -> doAcquire()
                                        }
                                    },
                                )
                            }
                        }
                        // 试运行按钮(仅小程序帖显示)
                        if (p.appKind == "mini-app") {
                            item {
                                Surface(
                                    shape = OctopusShape.medium,
                                    color = OctopusColors.SurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = OctopusSpacing.lg)
                                        .clip(OctopusShape.medium)
                                        .clickable(enabled = !tryRunLoading) {
                                            tryRunLoading = true
                                            scope.launch {
                                                try {
                                                    // 下载小程序 HTML(不安装)
                                                    val dl = CommunitySquareApi.download(p.appRef).getOrThrow()
                                                    tryRunHtml = dl.body
                                                } catch (e: Exception) {
                                                    onMessage(
                                                        ctx.getString(R.string.post_detail_try_run_error, e.message),
                                                    )
                                                }
                                                tryRunLoading = false
                                            }
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.md),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (tryRunLoading) {
                                            // 与文字同高,避免加载时按钮高度跳动
                                            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                                                EchoProgressLine(Modifier.width(EchoProgressLineInlineWidth))
                                            }
                                        } else {
                                            Text(
                                                stringResource(R.string.post_detail_try_run),
                                                color = OctopusColors.Primary,
                                                fontSize = OctopusType.body,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Fork 按钮(仅免费小程序帖且非作者自己时显示)
                        if (p.appKind == "mini-app" && p.priceCredits == 0 && !p.owned) {
                            item {
                                Surface(
                                    shape = OctopusShape.medium,
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, OctopusColors.Primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm)
                                        .clip(OctopusShape.medium)
                                        .clickable(enabled = !forking) {
                                            forking = true
                                            scope.launch {
                                                try {
                                                    val r = SquarePostApi.forkPost(postId)
                                                    if (r.ok) {
                                                        onMessage(ctx.getString(R.string.post_detail_forked))
                                                        post = post?.copy(owned = true)
                                                    } else {
                                                        val failed = ctx.getString(R.string.post_detail_fork_failed)
                                                        onMessage(r.message.ifBlank { failed })
                                                    }
                                                } catch (e: Exception) {
                                                    onMessage(
                                                        ctx.getString(R.string.post_detail_fork_error, e.message),
                                                    )
                                                }
                                                forking = false
                                            }
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.md),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (forking) {
                                                stringResource(R.string.post_detail_forking)
                                            } else {
                                                stringResource(R.string.post_detail_fork)
                                            },
                                            color = OctopusColors.Primary,
                                            fontSize = OctopusType.body,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 互动栏 ──
                    item {
                        InteractionBar(
                            post = p,
                            onLike = {
                                if (SignInPrompt.ensure(ctx, AccountFeature.COMMUNITY)) scope.launch {
                                    try {
                                        val r = if (p.liked) SquarePostApi.unlike(p.id) else SquarePostApi.like(p.id)
                                        post = p.copy(liked = r.liked, likesCount = r.likesCount)
                                    } catch (e: Exception) {
                                        onMessage(ctx.getString(R.string.post_detail_action_error, e.message))
                                    }
                                }
                            },
                            onFavorite = {
                                if (SignInPrompt.ensure(ctx, AccountFeature.COMMUNITY)) scope.launch {
                                    try {
                                        val r = if (p.favorited) SquarePostApi.unfavorite(p.id) else SquarePostApi.favorite(p.id)
                                        post = p.copy(favorited = r.favorited, favoritesCount = r.favoritesCount)
                                    } catch (e: Exception) {
                                        onMessage(ctx.getString(R.string.post_detail_action_error, e.message))
                                    }
                                }
                            },
                        )
                    }

                    // ── 评论列表 ──
                    item {
                        Text(
                            stringResource(R.string.post_detail_comments_section, comments.size),
                            modifier = Modifier.padding(start = OctopusSpacing.lg, top = OctopusSpacing.md, bottom = OctopusSpacing.xs),
                            color = OctopusColors.TextSecondary,
                            fontSize = OctopusType.label,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (comments.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(OctopusSpacing.lg), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.post_detail_no_comments), color = OctopusColors.TextMuted, fontSize = OctopusType.caption)
                            }
                        }
                    } else {
                        // 按 parentId 分组:顶级评论 + 子回复
                        val topLevel = comments.filter { it.parentId.isBlank() }
                        val repliesMap = comments.filter { it.parentId.isNotBlank() }.groupBy { it.parentId }
                        items(topLevel, key = { it.id }) { c ->
                            CommentRow(
                                c,
                                onReply = { replyText ->
                                    replyingTo = c.id
                                    newComment = replyText
                                },
                            )
                            // 子回复缩进展示
                            val replies = repliesMap[c.id].orEmpty()
                            if (replies.isNotEmpty()) {
                                Column(modifier = Modifier.padding(start = OctopusSpacing.xl + OctopusSpacing.lg)) {
                                    replies.forEach { reply ->
                                        CommentRow(
                                            reply,
                                            isReply = true,
                                            onReply = { replyText ->
                                                replyingTo = reply.id
                                                newComment = replyText
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 底部评论输入 ──
                CommentInputBar(
                    text = newComment,
                    placeholder = if (replyingTo != null) stringResource(R.string.post_detail_reply_hint) else null,
                    onTextChange = { newComment = it },
                    onSend = {
                        val text = newComment.trim()
                        if (text.isEmpty()) return@CommentInputBar
                        if (!SignInPrompt.ensure(ctx, AccountFeature.COMMUNITY)) return@CommentInputBar
                        scope.launch {
                            try {
                                val parentId = replyingTo ?: ""
                                val c = SquarePostApi.postComment(postId, text, parentId)
                                comments = comments + c
                                newComment = ""
                                replyingTo = null
                                post = post?.copy(commentsCount = (post?.commentsCount ?: 0) + 1)
                            } catch (e: Exception) {
                                onMessage(ctx.getString(R.string.post_detail_comment_error, e.message))
                            }
                        }
                    },
                )
            }
        }
    }

    if (showPayConfirm) {
        post?.let { p ->
            AlertDialog(
                onDismissRequest = { showPayConfirm = false },
                title = {
                    Text(stringResource(R.string.post_detail_acquire_confirm_title), color = OctopusColors.TextPrimary)
                },
                text = {
                    Text(
                        stringResource(R.string.post_detail_acquire_confirm_body, p.priceCredits, p.title),
                        color = OctopusColors.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPayConfirm = false
                        doAcquire()
                    }) { Text(stringResource(R.string.post_detail_acquire_confirm), color = OctopusColors.Primary) }
                },
                dismissButton = {
                    TextButton(onClick = { showPayConfirm = false }) {
                        Text(stringResource(R.string.common_cancel), color = OctopusColors.TextSecondary)
                    }
                },
                containerColor = OctopusBackground.cardSurface,
            )
        }
    }

    if (showSubConfirm) {
        post?.let { p ->
            val titleRes = if (p.subActive) R.string.post_detail_renew_title else R.string.post_detail_subscribe_title
            val confirmRes = if (p.subActive) R.string.post_detail_renew_month else R.string.post_detail_subscribe_month
            AlertDialog(
                onDismissRequest = { showSubConfirm = false },
                title = { Text(stringResource(titleRes), color = OctopusColors.TextPrimary) },
                text = {
                    Text(
                        stringResource(R.string.post_detail_subscribe_body, p.subPriceCredits, p.title),
                        color = OctopusColors.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSubConfirm = false
                        doSubscribe()
                    }) { Text(stringResource(confirmRes), color = OctopusColors.Primary) }
                },
                dismissButton = {
                    TextButton(onClick = { showSubConfirm = false }) {
                        Text(stringResource(R.string.common_cancel), color = OctopusColors.TextSecondary)
                    }
                },
                containerColor = OctopusBackground.cardSurface,
            )
        }
    }

    if (showShareDialog) {
        ShareQrDialog(postId = postId, onDismiss = { showShareDialog = false })
    }

    if (tryRunHtml != null) {
        val html = tryRunHtml!!
        Dialog(onDismissRequest = { tryRunHtml = null }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OctopusBackground.pageBrush()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(OctopusSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.post_detail_try_run),
                        color = OctopusColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { tryRunHtml = null }) {
                        Text(stringResource(R.string.common_close), color = OctopusColors.Primary)
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ImageCarousel(images: List<String>) {
    val pagerState = rememberPagerState(pageCount = { images.size })
    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            AsyncImage(
                model = fullUrl(images[page]),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // 指示器
        if (images.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = OctopusSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(images.size) { i ->
                    val color = if (i == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.4f)
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}

@Composable
private fun SingleCover(url: String) {
    AsyncImage(
        model = fullUrl(url),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(360.dp),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun PostBody(post: AgentPost, onOpenAuthor: (String) -> Unit) {
    Column(modifier = Modifier.padding(OctopusSpacing.lg), verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
        // 作者
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = post.authorId.isNotBlank()) { onOpenAuthor(post.authorId) },
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(post.authorColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(post.authorInitial, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(OctopusSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(post.author, color = OctopusColors.TextPrimary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (post.createdAt > 0) {
                    Text(DATE_FMT.format(Date(post.createdAt)), color = OctopusColors.TextMuted, fontSize = OctopusType.caption, maxLines = 1)
                }
            }
        }
        // 标题
        Text(post.title, color = OctopusColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        // 正文
        if (post.content.isNotBlank()) {
            val segments = parseCodeSegments(post.content)
            segments.forEach { seg ->
                if (seg.isCode) {
                    Surface(
                        shape = OctopusShape.small,
                        color = OctopusColors.SurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.xs),
                    ) {
                        Text(
                            seg.text,
                            modifier = Modifier.padding(OctopusSpacing.sm),
                            color = OctopusColors.TextPrimary,
                            fontSize = OctopusType.caption,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        )
                    }
                } else {
                    Text(seg.text, color = OctopusColors.TextPrimary, fontSize = OctopusType.body, lineHeight = 22.sp)
                }
            }
        }
        // 标签
        if (post.tag.isNotBlank()) {
            Surface(
                shape = OctopusShape.small,
                color = post.tagColor.copy(alpha = 0.12f),
                modifier = Modifier.padding(top = OctopusSpacing.xs),
            ) {
                Text(
                    "# ${post.tag}",
                    modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    color = post.tagColor,
                    fontSize = OctopusType.caption,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // Fork 溯源
        if (post.forkedFrom.isNotBlank()) {
            Text(
                stringResource(R.string.post_detail_forked_from),
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.tag,
                modifier = Modifier.padding(top = OctopusSpacing.xs),
            )
        }
    }
}

@Composable
private fun InteractionBar(
    post: AgentPost,
    onLike: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        ActionPill(
            icon = if (post.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            count = post.likesCount,
            tint = if (post.liked) OctopusColors.Resonance else OctopusColors.TextSecondary,
            label = stringResource(if (post.liked) R.string.post_detail_unlike else R.string.post_detail_like),
            onClick = onLike,
        )
        ActionPill(
            icon = Icons.AutoMirrored.Filled.Comment,
            count = post.commentsCount,
            tint = OctopusColors.TextSecondary,
            label = stringResource(R.string.post_detail_comments),
            onClick = {},
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onFavorite) {
            Icon(
                if (post.favorited) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = stringResource(
                    if (post.favorited) R.string.post_detail_unfavorite else R.string.post_detail_favorite,
                ),
                tint = if (post.favorited) OctopusColors.Primary else OctopusColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .tvFocusable()
            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            formatCount(count),
            color = tint,
            fontSize = OctopusType.caption,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CommentRow(
    c: SquarePostApi.CommentDto,
    isReply: Boolean = false,
    onReply: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(if (isReply) 24.dp else 28.dp)
                .clip(CircleShape)
                .background(OctopusColors.Primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.author.take(1),
                color = OctopusColors.Primary,
                fontSize = OctopusType.caption,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(OctopusSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(c.author, color = OctopusColors.TextSecondary, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(c.content, color = OctopusColors.TextPrimary, fontSize = OctopusType.body, lineHeight = 20.sp)
            Row(
                modifier = Modifier.padding(top = OctopusSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (c.createdAt > 0) {
                    Text(DATE_FMT.format(Date(c.createdAt)), color = OctopusColors.TextMuted, fontSize = OctopusType.tag, maxLines = 1)
                }
                Text(
                    stringResource(R.string.post_detail_reply),
                    color = OctopusColors.TextMuted,
                    fontSize = OctopusType.tag,
                    modifier = Modifier.clickable { onReply("") },
                )
            }
        }
    }
}

@Composable
private fun CommentInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String? = null,
) {
    Surface(
        color = OctopusBackground.cardSurface,
        shadowElevation = 0.dp,
    ) {
        // 平铺底栏:顶部 1dp 细线代替阴影
        Column {
            HorizontalDivider(thickness = 1.dp, color = OctopusColors.Border)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OctopusSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            placeholder ?: stringResource(R.string.post_detail_comment_hint),
                            color = OctopusColors.TextMuted,
                            fontSize = OctopusType.body,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = OctopusShape.medium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = OctopusColors.SurfaceVariant,
                        unfocusedContainerColor = OctopusColors.SurfaceVariant,
                        focusedIndicatorColor = OctopusColors.Accent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = OctopusColors.TextPrimary,
                        unfocusedTextColor = OctopusColors.TextPrimary,
                    ),
                )
                Spacer(Modifier.width(OctopusSpacing.sm))
                Surface(
                    shape = OctopusShape.medium,
                    color = if (text.isNotBlank()) OctopusColors.Primary else OctopusColors.SurfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(enabled = text.isNotBlank(), onClick = onSend)
                        .tvFocusable(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.post_detail_send),
                            color = if (text.isNotBlank()) OctopusColors.OnPrimary else OctopusColors.TextMuted,
                            fontSize = OctopusType.caption,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** 订阅制应用帖的操作按钮:订阅 N 积分/月 / 已订阅·续订(订阅中禁用)。 */
@Composable
private fun SubscribeCard(post: AgentPost, subscribing: Boolean, onSubscribe: () -> Unit) {
    val label = when {
        subscribing -> stringResource(R.string.post_detail_subscribing)
        post.subActive -> stringResource(R.string.post_detail_sub_active_renew, post.subPriceCredits)
        else -> stringResource(R.string.post_detail_subscribe_price, post.subPriceCredits)
    }
    val filled = !post.subActive
    Surface(
        shape = OctopusShape.medium,
        color = if (filled) OctopusColors.Primary else OctopusColors.SurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm)
            .clip(OctopusShape.medium)
            .clickable(enabled = !subscribing, onClick = onSubscribe),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = OctopusSpacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Autorenew,
                contentDescription = null,
                tint = if (filled) OctopusColors.OnPrimary else OctopusColors.Primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(OctopusSpacing.xs))
            Text(
                label,
                color = if (filled) OctopusColors.OnPrimary else OctopusColors.TextPrimary,
                fontSize = OctopusType.body,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 可复刻应用帖的操作按钮:免费复刻 / N 积分复刻 / 已复刻·重新安装(复刻中禁用)。 */
@Composable
private fun AppAcquireCard(post: AgentPost, acquiring: Boolean, onAcquire: () -> Unit) {
    val label = when {
        acquiring -> stringResource(R.string.post_detail_acquiring)
        post.owned -> stringResource(R.string.post_detail_owned_reinstall)
        post.priceCredits > 0 -> stringResource(R.string.post_detail_acquire_price, post.priceCredits)
        else -> stringResource(R.string.post_detail_acquire_free)
    }
    val filled = !post.owned
    Surface(
        shape = OctopusShape.medium,
        color = if (filled) OctopusColors.Primary else OctopusColors.SurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm)
            .clip(OctopusShape.medium)
            .clickable(enabled = !acquiring, onClick = onAcquire),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = OctopusSpacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = if (filled) Color.White else OctopusColors.Primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(OctopusSpacing.xs))
            Text(
                label,
                color = if (filled) Color.White else OctopusColors.TextPrimary,
                fontSize = OctopusType.body,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 服务端返回的图片 URL 是 /static/xxx 相对路径,需要拼上 squareBaseUrl 才能加载。 */
internal fun fullUrl(url: String): String {
    if (url.startsWith("http")) return url
    val base = com.apk.claw.android.account.AccountConfig.squareBaseUrl.trim().trimEnd('/')
    return base + url
}

/** 数字格式化:<1000 原样,≥1000 显示 1.2k,≥10000 显示 1.2w。 */
private fun formatCount(n: Int): String = formatCompactCount(n)

/** 帖子分享二维码 Dialog:生成帖子直达链接的 QR。 */
@Composable
private fun ShareQrDialog(postId: String, onDismiss: () -> Unit) {
    val shareUrl = com.apk.claw.android.account.AccountConfig.squareBaseUrl.trim().trimEnd('/') + "/p/$postId"
    val qrBitmap = remember(shareUrl) { generateQrBitmap(shareUrl, 400) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.post_detail_share_title), color = OctopusColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.post_detail_qr_desc),
                        modifier = Modifier.size(200.dp),
                    )
                } else {
                    Text(stringResource(R.string.post_detail_qr_failed), color = OctopusColors.TextMuted)
                }
                Spacer(Modifier.height(OctopusSpacing.md))
                Text(
                    shareUrl,
                    color = OctopusColors.TextSecondary,
                    fontSize = OctopusType.caption,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close), color = OctopusColors.Primary)
            }
        },
        containerColor = OctopusBackground.cardSurface,
    )
}

/** ZXing:把文本编码为二维码 Bitmap(与 SettingsViewModel.generateQrBitmap 同一套)。 */
private fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? {
    return try {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.MARGIN to 1,
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private data class CodeSegment(val text: String, val isCode: Boolean)

/** 把正文按 ```code``` 代码块拆成普通文本段和代码段。 */
private fun parseCodeSegments(content: String): List<CodeSegment> {
    val segments = mutableListOf<CodeSegment>()
    val regex = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0
    for (m in regex.findAll(content)) {
        if (m.range.first > lastEnd) {
            segments.add(CodeSegment(content.substring(lastEnd, m.range.first), false))
        }
        segments.add(CodeSegment(m.groupValues[1].trim(), true))
        lastEnd = m.range.last + 1
    }
    if (lastEnd < content.length) {
        segments.add(CodeSegment(content.substring(lastEnd), false))
    }
    return segments
}
