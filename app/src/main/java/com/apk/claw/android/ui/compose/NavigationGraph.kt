package com.apk.claw.android.ui.compose

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.FeatureHubScreen
import com.apk.claw.android.ui.compose.screen.GhostChatSessionStore
import com.apk.claw.android.ui.compose.screen.SettingsScreen
import com.apk.claw.android.ui.compose.screen.PluginMarketplaceScreen
import com.apk.claw.android.ui.compose.screen.SkillMarketplaceScreen
import com.apk.claw.android.ui.compose.screen.UniverseScreen
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
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
    // 统一的轻量反馈入口：替代分散的 Toast
    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            CompactBottomBar(
                currentRoute = selectedBottomRoute,
                onSelect = { screen ->
                    if (selectedBottomRoute == screen.route && currentDestination?.route == screen.route) return@CompactBottomBar
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    ) { innerPadding ->
        OctopusNavHost(
            navController = navController,
            showMessage = showMessage,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

// 底部 dock 专用颜色别名（跟随主题切换）
private val NavPrimaryColor get() = OctopusColors.Primary
private val NavTextMutedColor get() = OctopusColors.TextMuted

@Composable
private fun CompactBottomBar(
    currentRoute: String?,
    onSelect: (Screen) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = OctopusSpacing.md, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = OctopusColors.Surface,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
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
    val contentColor = if (selected) NavPrimaryColor else NavTextMutedColor
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) NavPrimaryColor.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                screen.icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            if (selected) {
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
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
        composable(Screen.Chat.route) { ChatScreen() }
        composable(Screen.Features.route) {
            FeatureHubScreen(
                // 灵感 tab 即广场:search→广场搜索,publish/+→发帖(合并原独立「灵感广场」的搜索/发帖入口)
                onOpenSearch = { navController.navigate("square_search") },
                onCreatePost = { navController.navigate("square_create_post") },
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
