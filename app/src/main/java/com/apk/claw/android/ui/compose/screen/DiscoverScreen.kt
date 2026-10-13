package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.browser.BrowserWallpaperStore
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.plugin.PluginManifest
import com.apk.claw.android.ui.browser.BookmarkItem
import com.apk.claw.android.ui.browser.BookmarkManager
import com.apk.claw.android.ui.browser.BrowserActivity
import com.apk.claw.android.ui.browser.CommonSiteItem
import com.apk.claw.android.ui.browser.CommonSiteStore
import com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity
import com.apk.claw.android.ui.featurescreens.CloudDriveActivity
import com.apk.claw.android.ui.featurescreens.MultiWindowActivity
import com.apk.claw.android.ui.featurescreens.RoutinesActivity
import com.apk.claw.android.ui.featurescreens.VideoLibraryActivity
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import androidx.compose.ui.window.Dialog
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.utils.KVUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val PrimaryColor get() = OctopusColors.Primary
private val SurfaceColor get() = OctopusColors.Surface
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted
private val BorderColor get() = OctopusColors.Border

/** 浏览器首页标题文字色（纯色背景）。 */
@Composable
private fun BrowserHomeTextColor(): Color = TextPrimary

private enum class SearchMode { Web, Ai, All }

private data class BrowserSearchOption(
    val id: String,
    val label: String,
    val favicon: String?,
    val mode: SearchMode,
)

private fun urlHost(url: String): String =
    runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()

private fun openBrowser(context: Context, query: String?) {
    val intent = Intent(context, BrowserActivity::class.java)
    if (!query.isNullOrBlank()) intent.putExtra(BrowserActivity.EXTRA_URL, query)
    runCatching { context.startActivity(intent) }
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")  // 桌面屏:状态 + LazyColumn + 文件夹/删除弹窗,天然偏长
fun DiscoverScreen(onOpenUrl: ((String?) -> Unit)? = null) {
    val context = LocalContext.current
    // 默认：启动独立浏览器 Activity（底部导航 Tab 用）。
    // 被浏览器内嵌为"新标签首页"时，宿主传入 onOpenUrl → 在当前 WebView 内导航，避免嵌套再起一个浏览器。
    val openUrl: (String?) -> Unit = onOpenUrl ?: { url -> openBrowser(context, url) }
    var query by remember { mutableStateOf("") }
    var engineId by remember { mutableStateOf(KVUtils.getSearchEngine()) }
    var engineMenuOpen by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(SearchMode.Web) }
    val aiSearchLabel = stringResource(R.string.ai_browser_mode_ai)
    val allSearchLabel = stringResource(R.string.ai_browser_mode_all)
    val searchOptions = remember(aiSearchLabel, allSearchLabel) {
        SearchEngines.ALL.map { BrowserSearchOption(it.id, it.label, it.favicon, SearchMode.Web) } + listOf(
            BrowserSearchOption("ai", aiSearchLabel, null, SearchMode.Ai),
            BrowserSearchOption("all", allSearchLabel, null, SearchMode.All),
        )
    }
    val selectedSearchOption = remember(engineId, searchMode, searchOptions) {
        searchOptions.firstOrNull {
            if (searchMode == SearchMode.Web) it.id == engineId else it.mode == searchMode
        } ?: searchOptions.first()
    }
    val submit = {
        val text = query.trim()
        openUrl(text.ifBlank { null })
        query = ""
    }
    // 小程序/书签都可能在别的页面(能力→小程序 / 浏览器设置)被改动，回到这个 tab 时要重读一遍，
    // 跟 SettingsScreen 的 refreshTick 是同一个套路。
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val miniApps = remember(refreshTick) { MiniAppRegistry.all() }
    val bookmarks = remember(refreshTick) { BookmarkManager.getAll() }
    val commonSites = remember(refreshTick) { CommonSiteStore.getAll() }
    var siteToDelete by remember { mutableStateOf<CommonSiteItem?>(null) }
    var appToDelete by remember { mutableStateOf<PluginManifest?>(null) }
    var openFolder by remember { mutableStateOf<BrowserFolder?>(null) }

    val wallpaper = remember(refreshTick) { BrowserWallpaperStore.loadBitmap(context)?.asImageBitmap() }
    Box(modifier = Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            // 压暗一层,保证图标/文字在任意壁纸上可读。
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = WALLPAPER_SCRIM_ALPHA)))
        }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(if (wallpaper == null) Modifier.background(browserWallpaperBrush()) else Modifier)
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = HOME_TOP_GAP,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        item {
            BrowserOmnibox(
                value = query,
                onValueChange = { query = it },
                selectedOption = selectedSearchOption,
                options = searchOptions,
                engineMenuOpen = engineMenuOpen,
                onEngineMenuChange = { engineMenuOpen = it },
                onEngineSelected = { option ->
                    searchMode = option.mode
                    if (option.mode == SearchMode.Web) {
                        engineId = option.id
                        KVUtils.setSearchEngine(option.id)
                    }
                },
                onSubmit = submit,
            )
        }

        // 搜索框与应用图标之间多留白,别挤在一起
        item { Spacer(Modifier.height(OctopusSpacing.lg)) }

        // ── 极简手机桌面:统一图标网格(iOS/HyperOS 风),少分类;工具、书签收进文件夹 ──
        item {
            LauncherHomeGrid(
                miniApps = miniApps,
                commonSites = commonSites,
                bookmarks = bookmarks,
                onLaunchApp = { MiniAppRegistry.launch(context, it) },
                onLongPressApp = { appToDelete = it },
                onOpenSite = { openUrl(it) },
                onLongPressSite = { siteToDelete = it },
                onOpenFolder = { openFolder = it },
            )
        }
    }
    }

    siteToDelete?.let { site ->
        AlertDialog(
            onDismissRequest = { siteToDelete = null },
            title = { Text(stringResource(R.string.browser_home_remove_common_site_title)) },
            text = { Text(site.title.ifBlank { site.url }) },
            confirmButton = {
                TextButton(onClick = {
                    CommonSiteStore.remove(site.url)
                    siteToDelete = null
                    Toast.makeText(context, context.getString(R.string.browser_home_removed_toast), Toast.LENGTH_SHORT).show()
                    refreshTick++
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { siteToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 长按「我的应用(小程序)」图标 → 确认删除(卸载目录 + 移出注册 + 刷新首页)。
    appToDelete?.let { app ->
        AlertDialog(
            onDismissRequest = { appToDelete = null },
            title = { Text("删除应用") },
            text = { Text(app.name.ifBlank { app.id }) },
            confirmButton = {
                TextButton(onClick = {
                    com.apk.claw.android.ClawApplication.instance.pluginManager.uninstallMiniApp(app.id)
                    appToDelete = null
                    Toast.makeText(context, context.getString(R.string.browser_home_removed_toast), Toast.LENGTH_SHORT).show()
                    refreshTick++
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { appToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 文件夹弹窗:工具 / 书签 的收纳,点开在卡片里铺图标网格(复用现成 Section)
    openFolder?.let { folder ->
        Dialog(onDismissRequest = { openFolder = null }) {
            OctopusPanel(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md)) {
                    Text(
                        stringResource(
                            if (folder == BrowserFolder.Tools) R.string.browser_home_tools
                            else R.string.browser_home_bookmarks,
                        ),
                        color = TextPrimary,
                        fontSize = OctopusType.bodyStrong,
                        fontWeight = FontWeight.Bold,
                    )
                    when (folder) {
                        BrowserFolder.Tools -> BrowserToolsSection {
                            openFolder = null
                            context.startActivity(Intent(context, it))
                        }
                        BrowserFolder.Bookmarks -> BookmarksSection(bookmarks) {
                            openFolder = null
                            openUrl(it)
                        }
                    }
                }
            }
        }
    }
}

/** 每行固定列数的 launcher 图标网格:最后一行落单补等宽 Spacer 保持左对齐(手机桌面观感)。 */
private const val LAUNCHER_COLUMNS = 4

/** 桌面整体下移量:搜索框不贴顶,重心往下一点。 */
private val HOME_TOP_GAP = 40.dp

/** 书签在首页最多铺两排图标(4 列 × 2 行),多的在浏览器书签页看全。 */
private const val MAX_BOOKMARK_ICONS = LAUNCHER_COLUMNS * 2

@Composable
private fun <T> LauncherGrid(items: List<T>, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
        items.chunked(LAUNCHER_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs), modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(LAUNCHER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val WALLPAPER_SCRIM_ALPHA = 0.32f

/** 浏览器桌面的「壁纸」:扁平纯色页面背景(自定义壁纸时改由 Image 层显示)。 */
@Composable
private fun browserWallpaperBrush(): Brush = SolidColor(OctopusColors.Background)

/** unavatar.io:按域名聚合各家真·官方 logo(Clearbit/Twitter/favicon…),比 s2 favicon 清晰;host 空返回 null。 */
private fun faviconUrl(host: String): String? =
    host.takeIf { it.isNotBlank() }?.let { "https://unavatar.io/$it" }

/** 站点磁贴图标:官方 logo 铺满圆角磁贴;加载中/失败落回彩色首字母(比绿地球好看)。 */
@Composable
private fun SiteLogo(host: String, title: String, key: String) {
    val url = faviconUrl(host)
    if (url == null) {
        MiniAppTileIcon(name = title, id = key)
        return
    }
    coil.compose.SubcomposeAsyncImage(
        model = url,
        contentDescription = title,
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentScale = ContentScale.Fit,
        loading = { MiniAppTileIcon(name = title, id = key) },
        error = { MiniAppTileIcon(name = title, id = key) },
    )
}

/** 小程序图标配色:每个按 name+id 哈希取一组渐变 + 首字母,像真·App 图标一样彩色可区分。 */
private val MINI_APP_GRADS = EchoCoverGradients

/** 彩色小程序图标:渐变底 + 白色首字母,填满 [HomeTile] 的 32dp 图标位。 */
@Composable
private fun MiniAppTileIcon(name: String, id: String) {
    val grad = MINI_APP_GRADS[((name + id).hashCode() and Int.MAX_VALUE) % MINI_APP_GRADS.size]
    val initial = name.trim().take(1).ifBlank { "小" }.uppercase()
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(grad)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Color.White, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.Bold)
    }
}

/** favicon/glyph 类图标的浅色圆角底座:让小尺寸站点图标在壁纸上也有清晰的「App 图标」轮廓。 */
@Composable
private fun LauncherIconSquare(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(SurfaceColor), contentAlignment = Alignment.Center) { content() }
}

/** 书签：[BookmarkManager] 里存的收藏，图标尝试用站点自己的 favicon.ico，加载失败落回书签图标。 */
@Composable
private fun BookmarksSection(bookmarks: List<BookmarkItem>, onOpen: (String) -> Unit) {
    LauncherGrid(bookmarks.take(MAX_BOOKMARK_ICONS)) { b, mod ->
        val host = remember(b.url) { urlHost(b.url) }
        LauncherIcon(label = b.title.ifBlank { host }, modifier = mod, onClick = { onOpen(b.url) }) {
            SiteLogo(host = host, title = b.title.ifBlank { host }, key = b.url)
        }
    }
}

@Composable
private fun BrowserOmnibox(
    value: String,
    onValueChange: (String) -> Unit,
    selectedOption: BrowserSearchOption,
    options: List<BrowserSearchOption>,
    engineMenuOpen: Boolean,
    onEngineMenuChange: (Boolean) -> Unit,
    onEngineSelected: (BrowserSearchOption) -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(22.dp),
        color = SurfaceColor,
        border = BorderStroke(0.5.dp, OctopusBackground.cardBorder),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = OctopusSpacing.md, end = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clickable { onEngineMenuChange(true) }
                        .padding(horizontal = OctopusSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchOptionIcon(selectedOption, Modifier.size(OctopusIconSize.medium))
                    Spacer(Modifier.width(OctopusSpacing.sm))
                    Text(selectedOption.label, color = TextSecondary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
                }
                DropdownMenu(
                    expanded = engineMenuOpen,
                    onDismissRequest = { onEngineMenuChange(false) },
                    containerColor = SurfaceColor,
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            leadingIcon = { SearchOptionIcon(option, Modifier.size(OctopusIconSize.medium)) },
                            text = { Text(option.label, color = TextPrimary, fontSize = OctopusType.bodyStrong) },
                            trailingIcon = {
                                if (option.id == selectedOption.id) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.medium))
                                }
                            },
                            onClick = {
                                onEngineSelected(option)
                                onEngineMenuChange(false)
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.padding(horizontal = OctopusSpacing.md).width(1.dp).height(24.dp).background(BorderColor))

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = OctopusType.title),
                    cursorBrush = SolidColor(PrimaryColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                )
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.ai_browser_omnibox_hint),
                        color = TextSecondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                }
            }

            IconButton(modifier = Modifier.size(44.dp), onClick = onSubmit) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.browser_url_hint), tint = PrimaryColor)
            }
        }
    }
}

@Composable
private fun SearchOptionIcon(option: BrowserSearchOption, modifier: Modifier = Modifier) {
    when {
        option.favicon != null -> coil.compose.AsyncImage(
            model = option.favicon,
            contentDescription = option.label,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Fit,
        )
        option.mode == SearchMode.Ai -> Icon(Icons.Filled.Psychology, contentDescription = null, tint = PrimaryColor, modifier = modifier)
        else -> Icon(Icons.Filled.Public, contentDescription = null, tint = PrimaryColor, modifier = modifier)
    }
}

/** 手机桌面式单个图标格:上方圆角方形图标(56dp)、下方居中标签。onLongClick 非空才支持长按(常用网站删除)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherIcon(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = OctopusSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            label,
            color = BrowserHomeTextColor(),
            fontSize = OctopusType.tag,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OctopusPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    backgroundColor: Color = OctopusBackground.cardSurface,
    contentPadding: androidx.compose.ui.unit.Dp = OctopusSpacing.md,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor, shape)
            .border(0.5.dp, OctopusBackground.cardBorder, shape)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/** 从「广场 → 更多」分流来的浏览器相关工具入口(市场重构第二阶段)。 */
private enum class BrowserFolder { Tools, Bookmarks }

/** 统一桌面网格的一格:小程序 / 常用网站 / 文件夹 三态。 */
private sealed interface LauncherEntry {
    data class App(val manifest: PluginManifest) : LauncherEntry
    data class Site(val site: CommonSiteItem) : LauncherEntry
    data class Folder(val folder: BrowserFolder, val labelRes: Int) : LauncherEntry
}

/** 统一桌面网格:小程序 + 常用网站 铺图标,工具/书签各收一个文件夹 tile(点开走 onOpenFolder)。 */
@Composable
private fun LauncherHomeGrid(
    miniApps: List<PluginManifest>,
    commonSites: List<CommonSiteItem>,
    bookmarks: List<BookmarkItem>,
    onLaunchApp: (String) -> Unit,
    onLongPressApp: (PluginManifest) -> Unit,
    onOpenSite: (String) -> Unit,
    onLongPressSite: (CommonSiteItem) -> Unit,
    onOpenFolder: (BrowserFolder) -> Unit,
) {
    val entries = buildList {
        miniApps.forEach { add(LauncherEntry.App(it)) }
        commonSites.forEach { add(LauncherEntry.Site(it)) }
        add(LauncherEntry.Folder(BrowserFolder.Tools, R.string.browser_home_tools))
        if (bookmarks.isNotEmpty()) {
            add(LauncherEntry.Folder(BrowserFolder.Bookmarks, R.string.browser_home_bookmarks))
        }
    }
    LauncherGrid(entries) { entry, mod ->
        when (entry) {
            is LauncherEntry.App -> {
                val name = entry.manifest.name.ifBlank { entry.manifest.id }
                LauncherIcon(
                    label = name,
                    modifier = mod,
                    onClick = { onLaunchApp(entry.manifest.id) },
                    onLongClick = { onLongPressApp(entry.manifest) },
                ) { MiniAppTileIcon(name = name, id = entry.manifest.id) }
            }
            is LauncherEntry.Site -> {
                val host = urlHost(entry.site.url)
                LauncherIcon(
                    label = entry.site.title.ifBlank { host },
                    modifier = mod,
                    onClick = { onOpenSite(entry.site.url) },
                    onLongClick = { onLongPressSite(entry.site) },
                ) { SiteLogo(host = host, title = entry.site.title.ifBlank { host }, key = entry.site.url) }
            }
            is LauncherEntry.Folder -> LauncherIcon(
                label = stringResource(entry.labelRes),
                modifier = mod,
                onClick = { onOpenFolder(entry.folder) },
            ) { FolderIcon(folder = entry.folder, bookmarks = bookmarks) }
        }
    }
}

/** 文件夹图标:iOS/HyperOS 风,圆角磨砂底 + 2×2 内容预览小图标。 */
@Composable
@Suppress("MagicNumber")  // 文件夹预览小图标的间距/尺寸,就地写数值更直观
private fun FolderIcon(folder: BrowserFolder, bookmarks: List<BookmarkItem>) {
    val previews: List<Pair<ImageVector, Color>> = when (folder) {
        BrowserFolder.Tools -> BROWSER_TOOLS.take(4).map { it.icon to it.tint }
        BrowserFolder.Bookmarks ->
            List(minOf(4, bookmarks.size).coerceAtLeast(1)) { Icons.Filled.Bookmark to OctopusTints.CatKnowledge }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceColor.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            previews.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    pair.forEach { (ic, tint) ->
                        Icon(ic, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

private data class BrowserToolEntry(
    val labelRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val activity: Class<*>,
)

private val BROWSER_TOOLS = listOf(
    BrowserToolEntry(
        R.string.feat_browser_settings, R.string.feat_browser_desc,
        Icons.Filled.Public, OctopusTints.Browser, BrowserSettingsActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_multiwindow, R.string.feat_multiwindow_desc,
        Icons.Filled.GridView, OctopusTints.Window, MultiWindowActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_clouddrive, R.string.feat_clouddrive_desc,
        Icons.Filled.CloudQueue, OctopusTints.Cloud, CloudDriveActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_routines, R.string.feat_routines_desc,
        Icons.Filled.Schedule, OctopusTints.Routine, RoutinesActivity::class.java,
    ),
    BrowserToolEntry(
        R.string.feat_video, R.string.feat_video_desc,
        Icons.Filled.Movie, OctopusTints.Video, VideoLibraryActivity::class.java,
    ),
)

@Composable
private fun BrowserToolsSection(onOpen: (Class<*>) -> Unit) {
    LauncherGrid(BROWSER_TOOLS) { tool, mod ->
        LauncherIcon(
            label = stringResource(tool.labelRes),
            modifier = mod,
            onClick = { onOpen(tool.activity) },
        ) {
            LauncherIconSquare {
                Icon(
                    tool.icon,
                    contentDescription = null,
                    tint = tool.tint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
