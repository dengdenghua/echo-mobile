package com.apk.claw.android.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ime
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apk.claw.android.account.AccountFeature
import com.apk.claw.android.ui.account.SignInPrompt
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.FeatureHubScreen
import com.apk.claw.android.ui.compose.screen.GhostChatSessionStore
import com.apk.claw.android.ui.compose.screen.SettingsScreen
import com.apk.claw.android.ui.compose.screen.PluginMarketplaceScreen
import com.apk.claw.android.ui.compose.screen.SkillMarketplaceScreen
import com.apk.claw.android.ui.compose.screen.UniverseScreen
import com.apk.claw.android.ui.compose.theme.EchoChatStyle
import com.apk.claw.android.ui.compose.theme.OctopusShape
import kotlinx.coroutines.launch

/**
 * 顶级 Composable：Scaffold 壳 + 底部导航栏 + NavHost。
 * 从 [MainActivity] 拆出，便于：
 *  1. 在 Preview / 测试中独立渲染整张图
 *  2. 后续接入 deep link / 多图导航时只动这一个文件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OctopusApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val selectedBottomRoute = when (currentDestination?.route) {
        Screen.Universe.route -> Screen.Features.route
        else -> currentDestination?.route
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 统一的轻量反馈入口：替代分散的 Toast
    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 对话页抽屉打开时收起 Dock,见 LocalBottomBarHidden
    val bottomBarHidden = remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = EchoChatStyle.background,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // 键盘弹出或对话页抽屉打开时收起 Dock
            AnimatedVisibility(
                visible = !bottomBarHidden.value && !keyboardVisible,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                CompactBottomBar(
                    currentRoute = selectedBottomRoute,
                    onSelect = { screen ->
                        if (selectedBottomRoute != screen.route || currentDestination?.route != screen.route) {
                            navController.navigateToTab(screen)
                        }
                    },
                )
            }
        }
    ) { innerPadding ->
        CompositionLocalProvider(LocalBottomBarHidden provides bottomBarHidden) {
            OctopusNavHost(
                navController = navController,
                showMessage = showMessage,
                // consumeWindowInsets:Dock 已经占掉的底部(含导航栏)算作已消费,页面里的 imePadding()
                // 只补「键盘高出 Dock 的那部分」,输入框不会悬在键盘上方一个 Dock 高的位置。
                // 横屏时三键导航栏/挖孔在侧边,内容区左右让开(Dock 自己也让开,见 CompactBottomBar)。
                // clipToBounds:收起的会话抽屉在内容区左侧外面,不裁剪会露在让出来的挖孔边条里。
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .clipToBounds(),
            )
        }
    }
}

/** 切换底部 Tab:回到起始页再进入,保存/恢复各 Tab 的状态。 */
private fun NavHostController.navigateToTab(screen: Screen) {
    navigate(screen.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// 底部 dock 专用颜色别名（跟随主题切换）
private val NavTextMutedColor get() = EchoChatStyle.muted

@Composable
private fun CompactBottomBar(
    currentRoute: String?,
    onSelect: (Screen) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = 28.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = OctopusShape.dock,
            color = Color.Transparent,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Screen.bottomBar.forEach { screen ->
                    val selected = currentRoute == screen.route
                    BottomTabItem(
                        screen = screen,
                        selected = selected,
                        onClick = { onSelect(screen) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomTabItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(screen.labelRes)
    val contentColor by animateColorAsState(
        if (selected) EchoChatStyle.text else NavTextMutedColor, label = "dockTint",
    )
    val backgroundColor by animateColorAsState(
        if (selected) EchoChatStyle.softSurface else Color.Transparent, label = "dockSelection",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(OctopusShape.large).background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(screen.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * 应用导航图：声明所有 destination 与对应 Composable 的映射。
 * 后续如要加 deep link、嵌套图、动画过渡，只动这里。
 */
@Composable
fun OctopusNavHost(
    navController: androidx.navigation.NavHostController,
    showMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        // 对话即首页：落地直接进入对话主界面
        startDestination = Screen.Chat.route,
        modifier = modifier,
        // Tab 切换的淡入淡出 + 轻微放大「fade-through」过渡
        enterTransition = { fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
    ) {
        composable(Screen.Discover.route) {
            // 发现 Tab 直接挂载新极简浏览器首页（带插画+胶囊搜索框+引擎切换+对话式 AI 结果）
            com.apk.claw.android.ui.browser.BrowserScreen(
                onClose = {},
                embedded = true,
            )
        }
        composable(Screen.Chat.route) {
            // 抽屉齿轮进入设置 Tab(与底部 Dock 的「设置」是同一页)
            ChatScreen(onOpenSettings = { navController.navigateToTab(Screen.Settings) })
        }
        composable(Screen.Features.route) {
            val context = LocalContext.current
            FeatureHubScreen(
                // 灵感 tab 即广场:search→广场搜索,publish/+→发帖(合并原独立「灵感广场」的搜索/发帖入口)
                onOpenSearch = { navController.navigate("square_search") },
                // 发帖要账号:本地模式先说「登录后可用」,别让人写完才被拦。
                onCreatePost = {
                    if (SignInPrompt.ensure(context, AccountFeature.COMMUNITY)) {
                        navController.navigate("square_create_post")
                    }
                },
                onNavigateToUniverse = { navController.navigate(Screen.Universe.route) },
                onNavigateToSkillMarketplace = { navController.navigate(Screen.SkillMarketplace.route) },
                onNavigateToPluginMarketplace = { navController.navigate(Screen.PluginMarketplace.route) },
                onOpenPostDetail = { pid -> navController.navigate("square_post/$pid") },
            )
        }
        composable(Screen.SkillMarketplace.route) {
            SkillMarketplaceScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.PluginMarketplace.route) {
            PluginMarketplaceScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.CreatorCenter.route) {
            com.apk.claw.android.ui.compose.screen.CreatorCenterScreen(
                onBack = { navController.popBackStack() },
                onMessage = showMessage,
            )
        }
        // 「灵感广场」(AgentSquare)独立入口已移除 —— 灵感 tab(FeatureHub 探索)即广场,
        // 其 search/发帖 直连下面的 square_search / square_create_post。
        composable("square_create_post") {
            com.apk.claw.android.ui.compose.screen.CreatePostScreen(
                onBack = { navController.popBackStack() },
                onPublished = {
                    navController.popBackStack()
                    showMessage("发布成功,等待审核")
                },
                onMessage = showMessage,
            )
        }
        composable("square_post/{postId}") { backStackEntry ->
            val pid = backStackEntry.arguments?.getString("postId").orEmpty()
            com.apk.claw.android.ui.compose.screen.PostDetailScreen(
                postId = pid,
                onBack = { navController.popBackStack() },
                onOpenAuthor = { uid -> navController.navigate("square_user/$uid") },
                onMessage = showMessage,
            )
        }
        composable("square_search") {
            com.apk.claw.android.ui.compose.screen.SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenPost = { pid -> navController.navigate("square_post/$pid") },
            )
        }
        composable("square_user/{userId}") { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("userId").orEmpty()
            com.apk.claw.android.ui.compose.screen.UserProfileScreen(
                userId = uid,
                onBack = { navController.popBackStack() },
                onOpenPost = { pid -> navController.navigate("square_post/$pid") },
                onMessage = showMessage,
            )
        }
        composable(Screen.Universe.route) {
            UniverseScreen(
                onBack = { navController.popBackStack() },
                onOpenGhostChat = { feed ->
                    GhostChatSessionStore.openSession(feed)
                    navController.navigate(Screen.Chat.route) {
                        launchSingleTop = true
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                    }
                },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onMessage = showMessage,
                onNavigateToCreatorCenter = { navController.navigate(Screen.CreatorCenter.route) },
            )
        }
    }
}
