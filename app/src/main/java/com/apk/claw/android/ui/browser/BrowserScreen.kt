package com.apk.claw.android.ui.browser

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.agent.AgentActionRecorder
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.featurescreens.UserscriptStoreActivity
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "BrowserScreen"

private val URL_REGEX = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")

sealed class BrowserPage {
    object Home : BrowserPage()
    object Search : BrowserPage()
    object Result : BrowserPage()
    object Webview : BrowserPage()
}

data class ChatMsg(val isUser: Boolean, val text: String, val sources: List<String> = emptyList(), val done: Boolean = false)

// HistoryEntry 定义在 HistoryStore.kt(同步需要 visitedTs 字段)

@Composable
fun BrowserScreen(
    initialUrl: String? = null,
    onClose: () -> Unit = {},
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val syncScope = rememberCoroutineScope()

    val engine = remember { BrowserEngineFactory.selectBest(context) }

    var pageState by remember { mutableStateOf<BrowserPage>(if (initialUrl.isNullOrEmpty()) BrowserPage.Home else BrowserPage.Webview) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    val messages = remember { mutableStateListOf<ChatMsg>() }

    var showMenu by remember { mutableStateOf(false) }
    var showTimeline by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var showBookmark by remember { mutableStateOf(false) }
    var showReader by remember { mutableStateOf(false) }
    var readerText by remember { mutableStateOf("") }
    var addressExpanded by remember { mutableStateOf(false) }
    var darkMode by rememberSaveable { mutableStateOf(false) }
    // 指纹保护开关:读 StealthManager 持久化值,变更后刷新 UI 状态 + 重新加载页面生效。
    var stealthEnabled by remember { mutableStateOf(com.apk.claw.android.octopus_mobile.browser.StealthManager.isEnabled()) }

    val history = remember { mutableStateListOf<HistoryEntry>().apply { addAll(HistoryStore.getAll().take(10)) } }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    val tabs by BrowserTabsStore.tabs.collectAsState()
    val currentTabId by BrowserTabsStore.currentId.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> engine.onPause()
                Lifecycle.Event.ON_RESUME -> engine.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.destroy()
            tts.value?.stop()
            tts.value?.shutdown()
            tts.value = null
            ToolRegistry.clearBrowserEngine()
        }
    }

    LaunchedEffect(engine) {
        ToolRegistry.setBrowserEngine(engine)
        BrowserTabsStore.ensureAtLeastOne(context.getString(R.string.browser_new_tab))
        // 把 UserscriptStore 已安装脚本同步进 BrowserPluginHost，确保浏览器加载页面时按最新列表注入
        runCatching { UserscriptStore.syncToPluginHost(context) }
    }

    LaunchedEffect(engine, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            engine.events().collect { event ->
                when (event) {
                    is EngineEvent.PageStarted -> {
                        isLoading = true
                        progress = 0
                    }
                    is EngineEvent.PageFinished -> {
                        isLoading = false
                        progress = 100
                        currentUrl = event.url
                        pageTitle = event.title
                        urlText = SearchEngines.extractQuery(event.url) ?: event.url
                        BrowserTabsStore.updateCurrent(event.url, event.title.ifBlank { event.url })
                        if (event.url.startsWith("http")) {
                            history.add(0, HistoryEntry(event.title.ifBlank { event.url }, event.url))
                            while (history.size > 10) history.removeAt(history.size - 1)
                            HistoryStore.add(event.title.ifBlank { event.url }, event.url)
                        }
                    }
                    is EngineEvent.ProgressChanged -> {
                        progress = event.percent
                    }
                    is EngineEvent.Error -> {
                        isLoading = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.browser_load_error, event.description),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is EngineEvent.DownloadStart -> {
                        DownloadHelper.enqueueDownload(
                            context = context,
                            url = event.url,
                            mimeType = event.mimeType,
                            suggestedFilename = event.suggestedFilename,
                            userAgent = event.userAgent,
                        )
                    }
                    is EngineEvent.ConsoleMessage -> {
                        Log.d(TAG, "[${event.level}] ${event.message}")
                    }
                    is EngineEvent.JsAlert -> {}
                }
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrEmpty()) {
            navigateTo(engine, initialUrl) { navUrl ->
                urlText = SearchEngines.extractQuery(navUrl) ?: navUrl
            }
            pageState = BrowserPage.Webview
        }
    }

    val navigate: (String) -> Unit = { input ->
        pageState = BrowserPage.Webview
        val url = if (input.contains(".") && !input.contains(" ")) {
            if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
        } else {
            SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
        }
        urlText = SearchEngines.extractQuery(url) ?: url
        currentUrl = url
        engine.navigate(url)
    }

    val submitHome: (String) -> Unit = { input ->
        val t = input.trim()
        if (t.isNotEmpty()) {
            if (isUrlLike(t)) {
                navigate(t)
            } else {
                val engine = com.apk.claw.android.octopus_mobile.browser.SearchEngines.byId(KVUtils.getSearchEngine())
                val isAiEngine = engine.id in setOf("perplexity", "kimi", "tongyi")
                if (isAiEngine) {
                    messages.clear()
                    messages.add(ChatMsg(isUser = true, text = t))
                    messages.add(ChatMsg(isUser = false, text = context.getString(R.string.browser_thinking_status)))
                    pageState = BrowserPage.Result
                    runAiStream(context, t) { full, srcs, done ->
                        if (messages.isNotEmpty() && !messages.last().isUser) {
                            messages[messages.lastIndex] = ChatMsg(isUser = false, text = full, sources = srcs, done = done)
                        }
                    }
                } else {
                    navigate(engine.searchUrl(t))
                }
            }
        }
    }

    val followUp: (String) -> Unit = { input ->
        val t = input.trim()
        if (t.isNotEmpty()) {
            messages.add(ChatMsg(isUser = true, text = t))
            messages.add(ChatMsg(isUser = false, text = context.getString(R.string.browser_thinking_status)))
            // 多轮上下文：传入当前问题之前的所有消息（不含刚加的 placeholder）
            val history = messages.subList(0, messages.size - 2).toList()
            runAiStream(context, t, history) { full, srcs, done ->
                if (messages.isNotEmpty() && !messages.last().isUser) {
                    messages[messages.lastIndex] = ChatMsg(isUser = false, text = full, sources = srcs, done = done)
                }
            }
        }
    }

    BackHandler {
        when (pageState) {
            is BrowserPage.Home -> onClose()
            is BrowserPage.Search -> pageState = BrowserPage.Home
            is BrowserPage.Result -> pageState = BrowserPage.Home
            is BrowserPage.Webview -> {
                engine.evaluateJs("window.history.length") { len ->
                    val n = len?.trim()?.trim('"')?.toIntOrNull() ?: 1
                    if (n > 1) engine.evaluateJs("window.history.back()")
                    else pageState = BrowserPage.Home
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BrowserWallpaper()

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).statusBarsPadding()) {
                BrowserWebViewContainer(engine = engine)

                when (pageState) {
                    is BrowserPage.Home -> BrowserHomeOverlay(
                        onActivateSearch = { pageState = BrowserPage.Search },
                        onClose = onClose,
                        onMenu = { showMenu = true },
                        onShowTabs = { showTabs = true },
                        tabCount = tabs.size,
                        embedded = embedded,
                    )
                    is BrowserPage.Search -> BrowserSearchOverlay(
                        onBack = { pageState = BrowserPage.Home },
                        onSubmit = { s -> submitHome(s) },
                        onOpenUrl = { url -> navigate(url) },
                        history = history.toList(),
                        commonSites = emptyList(),
                    )
                    is BrowserPage.Result -> BrowserResultOverlay(
                        messages = messages.toList(),
                        engineId = KVUtils.getSearchEngine(),
                        onBack = { pageState = BrowserPage.Home },
                        onFollowUp = { s -> followUp(s) },
                        onOpenSource = { url -> navigate(url) },
                    )
                    is BrowserPage.Webview -> {}
                }
            }

            if (isLoading && pageState is BrowserPage.Webview) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = OctopusColors.Primary,
                    trackColor = Color.Transparent,
                )
            }

            if (pageState is BrowserPage.Webview) {
                BrowserWebviewTopBar(
                    urlText = urlText,
                    onUrlTextChange = { urlText = it },
                    currentUrl = currentUrl,
                    addressExpanded = addressExpanded,
                    onAddressClick = { addressExpanded = true },
                    onAddressDismiss = { addressExpanded = false },
                    isLoading = isLoading,
                    onBack = {
                        engine.evaluateJs("window.history.length") { len ->
                            val n = len?.trim()?.trim('"')?.toIntOrNull() ?: 1
                            if (n > 1) engine.evaluateJs("window.history.back()")
                            else pageState = BrowserPage.Home
                        }
                    },
                    onBackLongPress = { showTimeline = true },
                    onReader = {
                        engine.evaluateJs("document.body.innerText") { result ->
                            val text = result?.trim('"')?.replace("\\n", "\n") ?: ""
                            readerText = text
                            showReader = true
                        }
                    },
                    onRefresh = {
                        if (isLoading) engine.evaluateJs("window.stop()") else engine.navigate(currentUrl)
                    },
                    onMenu = { showMenu = true },
                    onSubmit = {
                        val t = urlText.trim()
                        if (t.isNotEmpty()) { navigate(t); addressExpanded = false }
                    },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }

        AnimatedVisibility(
            visible = pageState is BrowserPage.Webview,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // 底部浮动工具栏（胶囊样式，与首页/Tab 风格一致）
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = OctopusColors.Surface,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.height(52.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 后退
                    BrowserCapsuleButton(
                        onClick = { engine.evaluateJs("window.history.back()") },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = OctopusColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 分享
                    BrowserCapsuleButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, currentUrl)
                            }
                            runCatching { context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.browser_share_chooser))) }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = null,
                            tint = OctopusColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 收藏
                    BrowserCapsuleButton(
                        onClick = { showBookmark = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = OctopusColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 菜单
                    BrowserCapsuleButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = null,
                            tint = OctopusColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    // AI 问答（主操作，蓝色高亮）
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(OctopusColors.Primary)
                            .clickable { showAi = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "AI",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = OctopusColors.OnPrimary,
                        )
                    }
                }
            }
        }

        if (showMenu) {
            BrowserMenuSheet(
                onDismiss = { showMenu = false },
                onDownloads = {
                    runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                },
                onShare = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, currentUrl)
                    }
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.browser_share_chooser))) }
                },
                onToggleDark = {
                    darkMode = !darkMode
                    val js = if (darkMode) {
                        "(function(){var s=document.documentElement.style;s.filter='invert(1) hue-rotate(180deg)';})()"
                    } else {
                        "(function(){var s=document.documentElement.style;s.filter='none';})()"
                    }
                    engine.evaluateJs(js)
                },
                onTranslate = {
                    val translateUrl = "https://translate.google.com/translate?sl=auto&tl=zh-CN&u=" + Uri.encode(currentUrl)
                    engine.navigate(translateUrl)
                },
                onBookmarks = { showBookmark = true },
                onToggleDesktop = {
                    val newMode = !KVUtils.getBrowserDesktopMode()
                    KVUtils.setBrowserDesktopMode(newMode)
                    engine.navigate(currentUrl)
                    Toast.makeText(context, if (newMode) context.getString(R.string.browser_desktop_mode_on) else context.getString(R.string.browser_desktop_mode_off), Toast.LENGTH_SHORT).show()
                },
                onToggleStealth = {
                    val newEnabled = !stealthEnabled
                    com.apk.claw.android.octopus_mobile.browser.StealthManager.setEnabled(newEnabled)
                    if (newEnabled) {
                        // 开启时自动轮换一次,并 Toast 提示当前 UA 平台。
                        val profile = com.apk.claw.android.octopus_mobile.browser.StealthManager.rotate()
                        Toast.makeText(
                            context,
                            context.getString(R.string.browser_stealth_switched, profile.label),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(context, R.string.browser_menu_stealth_off, Toast.LENGTH_SHORT).show()
                    }
                    stealthEnabled = newEnabled
                    // 重新加载当前页以应用新 UA + stealth JS(WebView UA 只在下次加载生效)。
                    if (currentUrl.startsWith("http")) engine.navigate(currentUrl)
                },
                onCloudSync = {
                    if (!BrowserSync.isReady()) {
                        Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "正在同步…", Toast.LENGTH_SHORT).show()
                        syncScope.launch {
                            val r = BrowserSync.syncAll(context)
                            val msg = if (r.notLoggedIn) {
                                "请先登录"
                            } else {
                                "已同步 ${r.bookmarks} 个书签 / ${r.history} 条历史 / ${r.tabs} 个标签"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onUserscriptStore = {
                    runCatching { context.startActivity(Intent(context, UserscriptStoreActivity::class.java)) }
                },
                onRecordAgent = {
                    if (AgentActionRecorder.isRecording) {
                        val routine = AgentActionRecorder.stopRecording()
                        val msg = if (routine != null) {
                            context.getString(R.string.agent_record_saved, routine.name)
                        } else {
                            context.getString(R.string.agent_record_empty)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        val goal = pageTitle.ifBlank { currentUrl.ifBlank { "浏览器 Agent 操作" } }
                        AgentActionRecorder.startRecording(goal)
                        Toast.makeText(context, R.string.agent_record_started, Toast.LENGTH_SHORT).show()
                    }
                },
                darkMode = darkMode,
                desktopMode = KVUtils.getBrowserDesktopMode(),
                stealthEnabled = stealthEnabled,
                isRecording = AgentActionRecorder.isRecording,
            )
        }

        if (showTimeline) {
            BrowserTimelineSheet(
                history = history.toList(),
                onDismiss = { showTimeline = false },
                onOpen = { url -> navigate(url) },
            )
        }

        if (showTabs) {
            BrowserTabsSheet(
                tabs = tabs,
                currentId = currentTabId,
                onDismiss = { showTabs = false },
                onSelect = { id ->
                    val tab = tabs.firstOrNull { it.id == id }
                    if (tab != null) {
                        BrowserTabsStore.select(id)
                        if (tab.url.isNotEmpty()) {
                            navigate(tab.url)
                        } else {
                            pageState = BrowserPage.Home
                            currentUrl = ""
                            urlText = ""
                        }
                    }
                    showTabs = false
                },
                onNew = {
                    BrowserTabsStore.newTab(context.getString(R.string.browser_new_tab))
                    pageState = BrowserPage.Home
                    currentUrl = ""
                    urlText = ""
                    showTabs = false
                },
                onClose = { id ->
                    BrowserTabsStore.close(id, context.getString(R.string.browser_new_tab))
                    val cur = BrowserTabsStore.current()
                    if (cur != null) {
                        if (cur.url.isNotEmpty()) navigate(cur.url)
                        else {
                            pageState = BrowserPage.Home
                            currentUrl = ""
                            urlText = ""
                        }
                    }
                },
            )
        }

        if (showAi) {
            BrowserAiSheet(
                engine = engine,
                onDismiss = { showAi = false },
                onRunAi = { question, onAnswer ->
                    runAi(context, engine, question, onAnswer)
                },
                onRunAgent = { task ->
                    showAi = false
                    runAgentOnPage(context, task)
                },
                onReader = { pageText -> showReader(context, pageText) },
                onSpeak = { pageText, ttsRef -> speakPage(context, pageText, ttsRef) },
                tts = tts,
            )
        }

        if (showBookmark) {
            BrowserBookmarkDialog(
                currentUrl = currentUrl,
                currentTitle = pageTitle.ifBlank { currentUrl },
                onDismiss = { showBookmark = false },
                onNavigate = { url -> navigate(url) },
            )
        }

        if (showReader) {
            BrowserReaderSheet(
                text = readerText,
                onDismiss = { showReader = false },
            )
        }
    }
}

@Composable
private fun BrowserWallpaper() {
    Box(modifier = Modifier.fillMaxSize().background(OctopusColors.Background))
}

@Composable
private fun BrowserCapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(OctopusShape.capsule)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun BrowserWebViewContainer(engine: BrowserEngine) {
    AndroidView(
        factory = { ctx -> engine.createView(ctx) },
        modifier = Modifier.fillMaxSize(),
    )
}

// ── P1-1: 首页极简化（参考 vivo 浏览器首页：留白+插画+胶囊搜索框+底部胶囊导航） ──

@Composable
private fun BrowserHomeOverlay(
    onActivateSearch: () -> Unit,
    onClose: () -> Unit,
    onMenu: () -> Unit = {},
    onShowTabs: () -> Unit = {},
    tabCount: Int,
    embedded: Boolean = false,
) {
    val engineId = remember { KVUtils.getSearchEngine() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))

            // 嵌入模式：右上角放菜单/标签按钮；独立模式：顶部关闭按钮
            if (embedded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    BrowserCapsuleButton(onClick = onShowTabs, modifier = Modifier.size(40.dp)) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.2.dp, OctopusColors.TextSecondary, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (tabCount > 99) "99+" else "$tabCount",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = OctopusColors.TextSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    BrowserCapsuleButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = null,
                            tint = OctopusColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(0.3f))
            }

            // 轻量插画：一个漂浮的圆形+弧形，抽象星球/气泡感，零资源
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    drawCircle(
                        color = Color(0xFFEAF2FF),
                        radius = 78.dp.toPx(),
                        center = center,
                    )
                    drawArc(
                        color = Color(0xFFFDECE0),
                        startAngle = -30f,
                        sweepAngle = 120f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 10.dp.toPx()),
                        size = size,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // 胶囊搜索按钮（点击进入搜索聚焦态）
            Surface(
                onClick = onActivateSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                color = OctopusColors.Surface,
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EngineGlyph(engineId = engineId)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.browser_home_search_hint),
                        fontSize = 15.sp,
                        color = OctopusColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = OctopusColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 非嵌入模式（独立 Activity）：底部胶囊导航条
            if (!embedded) {
                BottomCapsuleBar(
                    tabCount = tabCount,
                    onMenu = onMenu,
                    onShowTabs = onShowTabs,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .navigationBarsPadding(),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BottomCapsuleBar(
    tabCount: Int,
    onMenu: () -> Unit,
    onShowTabs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = OctopusColors.Surface,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = null,
                    tint = OctopusColors.TextPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable(onClick = onShowTabs),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(1.5.dp, OctopusColors.TextPrimary, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (tabCount > 99) "99+" else "$tabCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = OctopusColors.TextPrimary,
                    )
                }
            }
        }
    }
}

// ── P1-1b: 搜索聚焦态（点击首页搜索框后展开） ──

@Composable
private fun BrowserSearchOverlay(
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    history: List<HistoryEntry>,
    commonSites: List<CommonSiteItem>,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var engineId by remember { mutableStateOf(KVUtils.getSearchEngine()) }
    var showEngines by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusColors.Background)
            .statusBarsPadding(),
    ) {
        // 顶部搜索条：<  [Ai  输入框  🎙]  搜索
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserCapsuleButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = OctopusColors.TextPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = OctopusColors.SurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(OctopusShape.capsule)
                            .clickable { showEngines = true }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    ) {
                        EngineGlyph(engineId = engineId)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = OctopusColors.TextMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browser_home_search_hint),
                                fontSize = 15.sp,
                                color = OctopusColors.TextMuted,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = OctopusColors.TextPrimary),
                            cursorBrush = SolidColor(OctopusColors.Primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (text.trim().isNotEmpty()) onSubmit(text.trim())
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                    }
                    if (text.isNotEmpty()) {
                        BrowserCapsuleButton(
                            onClick = { text = "" },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = OctopusColors.TextMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = OctopusColors.TextMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "搜索",
                fontSize = 16.sp,
                color = Color(0xFF3B82F6),
                modifier = Modifier
                    .clickable(enabled = text.trim().isNotEmpty()) { onSubmit(text.trim()) }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }

        // 下方内容
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
        ) {
            // 历史记录
            val recent = history.take(8)
            if (recent.isNotEmpty()) {
                Section("历史")
                recent.forEach { entry ->
                    HistoryRow(title = entry.title.ifBlank { entry.url }, subtitle = domainOf(entry.url)) {
                        onOpenUrl(entry.url)
                    }
                }
                Spacer(Modifier.height(OctopusSpacing.lg))
            }

            // 常用网站
            if (commonSites.isNotEmpty()) {
                Section("常用网站")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                ) {
                    commonSites.take(4).forEach { site ->
                        CommonSiteTile(
                            title = site.title,
                            url = site.url,
                            onClick = { onOpenUrl(site.url) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (showEngines) {
        SearchEngineSheet(
            currentId = engineId,
            onDismiss = { showEngines = false },
            onSelect = { id ->
                KVUtils.setSearchEngine(id)
                engineId = id
                showEngines = false
            },
        )
    }
}

@Composable
private fun EngineGlyph(engineId: String) {
    // 用 tag 首字代替 favicon（和 SearchEngine.tag 一致，避免加载网络图标）
    val engine = remember(engineId) { com.apk.claw.android.octopus_mobile.browser.SearchEngines.byId(engineId) }
    val color = when (engineId) {
        "google" -> Color(0xFF4285F4)
        "bing" -> Color(0xFF008373)
        "baidu" -> Color(0xFF2932E1)
        "duckduckgo" -> Color(0xFFDE5833)
        "perplexity" -> Color(0xFF20B8CD)
        "kimi" -> Color(0xFF7B61FF)
        "tongyi" -> Color(0xFF6236FF)
        else -> Color(0xFF3B82F6)
    }
    Text(
        text = engine.tag,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEngineSheet(
    currentId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.lg)
                .padding(bottom = OctopusSpacing.xxl),
        ) {
            Text(
                text = "切换搜索引擎",
                fontSize = 15.sp,
                color = OctopusColors.TextMuted,
                modifier = Modifier.padding(bottom = OctopusSpacing.md),
            )
            com.apk.claw.android.octopus_mobile.browser.SearchEngines.ALL.forEach { engine ->
                val selected = engine.id == currentId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OctopusShape.medium)
                        .clickable { onSelect(engine.id) }
                        .padding(horizontal = OctopusSpacing.sm, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EngineGlyph(engineId = engine.id)
                    Spacer(Modifier.width(OctopusSpacing.lg))
                    Text(
                        text = engine.label,
                        fontSize = 16.sp,
                        color = OctopusColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = OctopusColors.TextPrimary,
        modifier = Modifier.padding(bottom = OctopusSpacing.sm),
    )
}

@Composable
private fun HistoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = OctopusColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = OctopusColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = OctopusColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CommonSiteTile(title: String, url: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(OctopusShape.medium)
            .clickable(onClick = onClick)
            .padding(vertical = OctopusSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(OctopusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = OctopusColors.Primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            color = OctopusColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── P1-2: 对话式结果页 ──

@Composable
private fun BrowserResultOverlay(
    messages: List<ChatMsg>,
    engineId: String,
    onBack: () -> Unit,
    onFollowUp: (String) -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val context = LocalContext.current
    var followText by remember { mutableStateOf("") }
    val listState = rememberScrollState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            listState.animateScrollTo(listState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusColors.Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserCapsuleButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = OctopusColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(OctopusSpacing.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(OctopusShape.capsule)
                    .background(OctopusColors.SurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                EngineGlyph(engineId = engineId)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = SearchEngines.byId(engineId).label,
                    fontSize = 13.sp,
                    color = OctopusColors.TextSecondary,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(listState)
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(OctopusSpacing.sm))
            messages.forEach { msg ->
                ChatBubble(
                    msg = msg,
                    engineId = engineId,
                    onOpenSource = onOpenSource,
                )
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(OctopusSpacing.md))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = OctopusColors.SurfaceVariant,
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = followText,
                        onValueChange = { followText = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = OctopusColors.TextPrimary),
                        cursorBrush = SolidColor(OctopusColors.Primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (followText.trim().isNotEmpty()) {
                                onFollowUp(followText.trim())
                                followText = ""
                            }
                        }),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                if (followText.isEmpty()) {
                                    Text(
                                        text = "接着问…",
                                        fontSize = 14.sp,
                                        color = OctopusColors.TextMuted,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (followText.trim().isNotEmpty()) OctopusColors.Primary else OctopusColors.SurfaceVariant)
                    .clickable(enabled = followText.trim().isNotEmpty()) {
                        onFollowUp(followText.trim())
                        followText = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = if (followText.trim().isNotEmpty()) OctopusColors.OnPrimary else OctopusColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    msg: ChatMsg,
    engineId: String,
    onOpenSource: (String) -> Unit,
) {
    val context = LocalContext.current
    val arrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    val bubbleColor = if (msg.isUser) OctopusColors.Primary else OctopusColors.Surface
    val textColor = if (msg.isUser) OctopusColors.OnPrimary else OctopusColors.TextPrimary
    val bubbleShape = if (msg.isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    // 流式打字光标：AI 未 done 时末尾闪烁 ▍
    val cursorAlpha = if (!msg.isUser && !msg.done) {
        rememberInfiniteTransition(label = "cursor").let {
            it.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "cursorAlpha",
            ).value
        }
    } else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement,
    ) {
        if (!msg.isUser) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, end = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(OctopusColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                EngineGlyph(engineId = engineId)
            }
        }

        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
            ) {
                if (cursorAlpha > 0f) {
                    // 流式输出中：文本 + 闪烁光标
                    val annotated = buildAnnotatedString {
                        append(msg.text)
                        withStyle(SpanStyle(color = textColor.copy(alpha = cursorAlpha))) {
                            append("▍")
                        }
                    }
                    Text(
                        text = annotated,
                        fontSize = 14.sp,
                        color = textColor,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    Text(
                        text = msg.text,
                        fontSize = 14.sp,
                        color = textColor,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            if (!msg.isUser && msg.sources.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 2.dp),
                ) {
                    msg.sources.take(3).forEach { url ->
                        Text(
                            text = domainOf(url),
                            fontSize = 11.sp,
                            color = OctopusColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(OctopusShape.capsule)
                                .background(OctopusColors.SurfaceVariant)
                                .clickable { onOpenSource(url) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (!msg.isUser && msg.done) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    BubbleAction(icon = Icons.Default.ContentCopy, text = "复制") {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("answer", msg.text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    BubbleAction(icon = Icons.Default.Share, text = "分享") {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, msg.text)
                        }
                        runCatching { context.startActivity(Intent.createChooser(shareIntent, "分享回答")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(OctopusShape.capsule)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = OctopusColors.TextMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp, color = OctopusColors.TextMuted)
    }
}

// ── P1-3: 网页模式全屏沉浸 ──

@Composable
private fun BrowserWebviewTopBar(
    urlText: String,
    onUrlTextChange: (String) -> Unit,
    currentUrl: String,
    addressExpanded: Boolean,
    onAddressClick: () -> Unit,
    onAddressDismiss: () -> Unit,
    isLoading: Boolean,
    onBack: () -> Unit,
    onBackLongPress: () -> Unit,
    onReader: () -> Unit,
    onRefresh: () -> Unit,
    onMenu: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mutedColor = OctopusColors.TextSecondary

    if (addressExpanded) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(OctopusColors.Surface)
                .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserCapsuleButton(onClick = onAddressDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.advanced_action_close),
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(OctopusSpacing.sm))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(OctopusShape.capsule)
                    .background(OctopusColors.SurfaceVariant)
                    .padding(start = OctopusSpacing.md, end = OctopusSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = urlText,
                    onValueChange = onUrlTextChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = OctopusColors.TextPrimary),
                    cursorBrush = SolidColor(OctopusColors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (urlText.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.browser_url_hint),
                                    fontSize = 13.sp,
                                    color = mutedColor,
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                BrowserCapsuleButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.browser_refresh_button),
                        tint = mutedColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(OctopusColors.Surface)
                .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(OctopusShape.capsule)
                    .combinedClickable(onClick = onBack, onLongClick = onBackLongPress),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.browser_back_button),
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(OctopusSpacing.sm))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(OctopusShape.capsule)
                    .background(OctopusColors.SurfaceVariant)
                    .clickable { onAddressClick() }
                    .padding(horizontal = OctopusSpacing.md),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = domainOf(currentUrl).ifBlank { stringResource(R.string.browser_url_hint) },
                    fontSize = 13.sp,
                    color = mutedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(OctopusSpacing.sm))

            BrowserCapsuleButton(onClick = onReader, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = stringResource(R.string.browser_reader_button),
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(OctopusSpacing.xs))
            BrowserCapsuleButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.browser_refresh_button),
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(OctopusSpacing.xs))
            BrowserCapsuleButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.browser_settings_title),
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserReaderSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.lg)
                .padding(bottom = OctopusSpacing.xxl),
        ) {
            Text(
                text = stringResource(R.string.browser_reader_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.TextPrimary,
            )
            Spacer(Modifier.height(OctopusSpacing.md))
            Text(
                text = text.ifBlank { stringResource(R.string.browser_reader_empty) },
                fontSize = 15.sp,
                color = OctopusColors.TextPrimary,
                lineHeight = 24.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

// ── P1-4: 时间线 + 菜单精简 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenuSheet(
    onDismiss: () -> Unit,
    onDownloads: () -> Unit,
    onShare: () -> Unit,
    onToggleDark: () -> Unit,
    onTranslate: () -> Unit,
    onBookmarks: () -> Unit,
    onToggleDesktop: () -> Unit,
    onToggleStealth: () -> Unit,
    onCloudSync: () -> Unit,
    onUserscriptStore: () -> Unit,
    onRecordAgent: () -> Unit,
    darkMode: Boolean,
    desktopMode: Boolean,
    stealthEnabled: Boolean,
    isRecording: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.lg)
                .padding(bottom = OctopusSpacing.xxl),
        ) {
            MenuRow(stringResource(R.string.browser_menu_downloads), Icons.Filled.Download) { onDismiss(); onDownloads() }
            MenuRow(stringResource(R.string.browser_menu_share), Icons.Filled.Share) { onDismiss(); onShare() }
            MenuRow(
                stringResource(if (darkMode) R.string.browser_menu_light_mode else R.string.browser_menu_dark_mode),
                Icons.Filled.DarkMode,
            ) { onDismiss(); onToggleDark() }
            MenuRow(stringResource(R.string.browser_menu_translate), Icons.Filled.Translate) { onDismiss(); onTranslate() }
            MenuRow(stringResource(R.string.browser_bookmarks_button), Icons.Filled.Bookmark) { onDismiss(); onBookmarks() }
            MenuRow(
                stringResource(if (desktopMode) R.string.browser_menu_mobile_mode else R.string.browser_menu_desktop_mode),
                Icons.Filled.DesktopWindows,
            ) { onDismiss(); onToggleDesktop() }
            MenuRow(
                stringResource(if (stealthEnabled) R.string.browser_menu_stealth_on else R.string.browser_menu_stealth_protection),
                Icons.Filled.Shield,
            ) { onDismiss(); onToggleStealth() }
            MenuRow(stringResource(R.string.browser_menu_cloud_sync), Icons.Filled.Sync) { onDismiss(); onCloudSync() }
            MenuRow(stringResource(R.string.browser_menu_userscript_store), Icons.Filled.Extension) { onDismiss(); onUserscriptStore() }
            MenuRow(
                stringResource(if (isRecording) R.string.browser_menu_stop_recording else R.string.browser_menu_record_agent),
                Icons.Filled.FiberManualRecord,
            ) { onDismiss(); onRecordAgent() }
        }
    }
}

@Composable
private fun MenuRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = title, tint = OctopusColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(OctopusSpacing.md))
        Text(title, fontSize = 15.sp, color = OctopusColors.TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTimelineSheet(
    history: List<HistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.lg)
                .padding(bottom = OctopusSpacing.xxl),
        ) {
            Text(
                text = stringResource(R.string.browser_timeline_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.TextPrimary,
            )
            Spacer(Modifier.height(OctopusSpacing.md))
            if (history.isEmpty()) {
                Text(
                    text = stringResource(R.string.browser_timeline_empty),
                    fontSize = 14.sp,
                    color = OctopusColors.TextMuted,
                )
            } else {
                history.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = OctopusSpacing.xs)
                            .clip(OctopusShape.large)
                            .background(OctopusColors.SurfaceVariant)
                            .clickable { onDismiss(); onOpen(entry.url) }
                            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title.ifBlank { entry.url },
                                fontSize = 14.sp,
                                color = OctopusColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = domainOf(entry.url),
                                fontSize = 11.sp,
                                color = OctopusColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 标签页 Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTabsSheet(
    tabs: List<BrowserTabsStore.Tab>,
    currentId: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onClose: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.lg)
                .padding(bottom = OctopusSpacing.xxl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${tabs.size} 个标签",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OctopusColors.TextPrimary,
                )
                BrowserCapsuleButton(onClick = onNew, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = OctopusColors.TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(OctopusSpacing.md))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(OctopusShape.medium)
                            .background(if (tab.id == currentId) OctopusColors.Primary.copy(alpha = 0.08f) else OctopusColors.SurfaceVariant)
                            .clickable { onSelect(tab.id) }
                            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tab.title.ifBlank { stringResource(R.string.browser_new_tab) },
                                fontSize = 14.sp,
                                color = OctopusColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (tab.url.isNotEmpty()) {
                                Text(
                                    text = domainOf(tab.url),
                                    fontSize = 11.sp,
                                    color = OctopusColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(OctopusSpacing.sm))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { onClose(tab.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = OctopusColors.TextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── AI Sheet (保留 P0: DOM 问答) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserAiSheet(
    engine: BrowserEngine,
    onDismiss: () -> Unit,
    onRunAi: (String, (String) -> Unit) -> Unit,
    onRunAgent: (String) -> Unit,
    onReader: (String?) -> Unit,
    onSpeak: (String?, MutableState<TextToSpeech?>) -> Unit,
    tts: MutableState<TextToSpeech?>,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    // P0-2: 优先用 DOM (document.body.innerText) 取页面文本,无障碍树作为 fallback。
    var pageText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        engine.evaluateJs("(function(){try{return document.body&&document.body.innerText||''}catch(e){return ''}})()") { result ->
            val domText = result?.trim('"')?.replace("\\n", "\n")?.takeIf { it.isNotBlank() }
            pageText = domText ?: com.apk.claw.android.service.ClawAccessibilityService.getInstance()
                ?.let { runCatching { it.screenTree }.getOrNull() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OctopusColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.lg)
                .padding(bottom = OctopusSpacing.xxl),
        ) {
            Text(
                text = stringResource(R.string.browser_ai_sheet_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = OctopusColors.TextPrimary,
            )
            if (pageText.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.browser_accessibility_warning),
                    fontSize = 11.sp,
                    color = OctopusColors.Warning,
                    modifier = Modifier.padding(top = OctopusSpacing.sm),
                )
            }

            Row(modifier = Modifier.padding(top = OctopusSpacing.md)) {
                AiChip(stringResource(R.string.browser_chip_summarize)) {
                    thinking = true
                    onRunAi(context.getString(R.string.browser_ai_prompt_summarize)) {
                        answer = it; thinking = false
                    }
                }
                Spacer(Modifier.width(OctopusSpacing.sm))
                AiChip(stringResource(R.string.browser_chip_key_points)) {
                    thinking = true
                    onRunAi(context.getString(R.string.browser_ai_prompt_key_points)) {
                        answer = it; thinking = false
                    }
                }
                Spacer(Modifier.width(OctopusSpacing.sm))
                AiChip(stringResource(R.string.browser_chip_translate)) {
                    thinking = true
                    onRunAi(context.getString(R.string.browser_ai_prompt_translate)) {
                        answer = it; thinking = false
                    }
                }
            }
            Row(modifier = Modifier.padding(top = OctopusSpacing.sm)) {
                AiChip(stringResource(R.string.browser_chip_reader)) { onDismiss(); onReader(pageText) }
                Spacer(Modifier.width(OctopusSpacing.sm))
                AiChip(stringResource(R.string.browser_chip_speak)) { onDismiss(); onSpeak(pageText, tts) }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = OctopusSpacing.lg),
            ) {
                BasicTextField(
                    value = question,
                    onValueChange = { question = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = OctopusColors.TextPrimary),
                    cursorBrush = SolidColor(OctopusColors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (question.isNotBlank()) {
                            thinking = true
                            onRunAi(question) { answer = it; thinking = false }
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (question.isEmpty()) {
                                Text(stringResource(R.string.browser_input_ask_or_operate), fontSize = 14.sp, color = OctopusColors.TextSecondary)
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(OctopusShape.capsule)
                        .background(OctopusColors.SurfaceVariant)
                        .padding(horizontal = OctopusSpacing.lg),
                )
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(
                    text = stringResource(R.string.browser_ask_button),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.OnPrimary,
                    modifier = Modifier
                        .clip(OctopusShape.capsule)
                        .background(OctopusColors.Primary)
                        .clickable {
                            if (question.isNotBlank()) {
                                thinking = true
                                onRunAi(question) { answer = it; thinking = false }
                            }
                        }
                        .padding(horizontal = OctopusSpacing.lg, vertical = 10.dp),
                )
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(
                    text = stringResource(R.string.browser_execute_button),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = OctopusColors.OnPrimary,
                    modifier = Modifier
                        .clip(OctopusShape.capsule)
                        .background(OctopusColors.Warning)
                        .clickable {
                            if (question.isNotBlank()) {
                                onDismiss(); onRunAgent(question)
                            }
                        }
                        .padding(horizontal = OctopusSpacing.lg, vertical = 10.dp),
                )
            }

            if (thinking) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = OctopusSpacing.lg).size(20.dp),
                    color = OctopusColors.Primary,
                )
            } else if (answer.isNotBlank()) {
                Text(
                    text = answer,
                    fontSize = 14.sp,
                    color = OctopusColors.TextPrimary,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 0.dp, max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = OctopusSpacing.lg),
                )
            }
        }
    }
}

@Composable
private fun AiChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = OctopusColors.Primary,
        modifier = Modifier
            .clip(OctopusShape.large)
            .background(OctopusColors.Primary.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = OctopusSpacing.sm),
    )
}

@Composable
private fun BrowserBookmarkDialog(
    currentUrl: String,
    currentTitle: String,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val bookmarks = remember { BookmarkManager.getAll() }
    val isBookmarked = remember(currentUrl) { BookmarkManager.isBookmarked(currentUrl) }
    val isCommonSite = remember(currentUrl) { CommonSiteStore.contains(currentUrl) }

    val items = mutableListOf<String>().apply {
        add(context.getString(if (isBookmarked) R.string.browser_remove_bookmark else R.string.browser_bookmark_current))
        add(context.getString(if (isCommonSite) R.string.browser_remove_common_site else R.string.browser_add_common_site))
        add(context.getString(R.string.browser_saved_bookmarks_header))
        bookmarks.forEach { add("${it.title}\n${it.url}") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_bookmarks_button)) },
        text = {
            LazyColumn {
                items(items.size, key = { it }) { index ->
                    Text(
                        text = items[index],
                        fontSize = if (index > 2) 14.sp else 15.sp,
                        color = OctopusColors.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (index) {
                                    0 -> {
                                        if (isBookmarked) BookmarkManager.remove(currentUrl)
                                        else BookmarkManager.add(currentUrl, currentTitle.ifEmpty { currentUrl })
                                        onDismiss()
                                    }
                                    1 -> {
                                        if (isCommonSite) CommonSiteStore.remove(currentUrl)
                                        else CommonSiteStore.add(currentUrl, currentTitle.ifEmpty { currentUrl })
                                        onDismiss()
                                    }
                                    else -> {
                                        val bookmark = bookmarks[index - 3]
                                        onDismiss(); onNavigate(bookmark.url)
                                    }
                                }
                            }
                            .padding(vertical = OctopusSpacing.md),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.advanced_action_close)) }
        },
    )
}

// ── 工具函数 ──

private fun navigateTo(engine: BrowserEngine, input: String, onUrl: ((String) -> Unit)? = null) {
    val url = if (input.contains(".") && !input.contains(" ")) {
        if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
    } else {
        SearchEngines.byId(KVUtils.getSearchEngine()).searchUrl(input)
    }
    onUrl?.invoke(url)
    engine.navigate(url)
}

private fun isUrlLike(input: String): Boolean =
    input.startsWith("http://") || input.startsWith("https://") ||
        (input.contains(".") && !input.contains(" "))

private fun domainOf(url: String): String {
    return try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
}

private fun isPageCommand(context: Context, s: String): Boolean {
    if (s.startsWith("http") || (s.contains(".") && !s.contains(" "))) return false
    val t = s.lowercase()
    val markers = context.getString(R.string.browser_page_cmd_markers).split(",").map { it.trim() }
    return markers.any { t.contains(it) }
}

private fun runAgentOnPage(context: Context, task: String) {
    if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
        Toast.makeText(context, context.getString(R.string.browser_configure_api_key), Toast.LENGTH_SHORT).show()
        return
    }
    val prompt = context.getString(R.string.browser_agent_prompt, task)
    com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(prompt, onTool = { _, _, _, _ -> }, onText = {}, onDone = {}, onError = {})
}

private fun runAi(context: Context, engine: BrowserEngine, question: String, onAnswer: (String) -> Unit) {
    if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
        onAnswer(context.getString(R.string.browser_configure_api_key_text))
        return
    }
    onAnswer(context.getString(R.string.browser_thinking_status))
    // P0-2: DOM 优先,无障碍树兜底。evaluateJs 异步回调里组装 prompt 并启动 Agent。
    engine.evaluateJs("(function(){try{return document.body&&document.body.innerText||''}catch(e){return ''}})()") { result ->
        val domText = result?.trim('"')?.replace("\\n", "\n")?.takeIf { it.isNotBlank() }
        val pageText = domText ?: com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?.let { runCatching { it.screenTree }.getOrNull() }
        val ctx = if (pageText.isNullOrBlank()) "" else "\n\n【当前网页内容】\n" + pageText.take(4000)
        val prompt = context.getString(R.string.browser_ai_system_prompt, question, ctx)
        val sb = StringBuilder()
        com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
            prompt,
            onTool = { _, _, _, _ -> },
            onText = { t -> sb.append(t); onAnswer(sb.toString()) },
            onDone = { d -> onAnswer(if (sb.isNotEmpty()) sb.toString() else d) },
            onError = { e -> onAnswer(context.getString(R.string.browser_error_message, e)) },
        )
    }
}

/** 流式 AI 回答：实时回传 text/sources/done，用于多轮对话 UI。
 *  [history] 传入之前的对话消息（不含当前问题），用于让 AI 保持多轮上下文。*/
private fun runAiStream(
    context: Context,
    question: String,
    history: List<ChatMsg> = emptyList(),
    onUpdate: (text: String, sources: List<String>, done: Boolean) -> Unit,
) {
    if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
        val msg = context.getString(R.string.browser_configure_api_key_text)
        onUpdate(msg, emptyList(), true)
        return
    }
    // 拼接多轮上下文：最多取最近 6 条已完成的消息（3 轮 QA），避免 prompt 过长
    val ctxMsgs = history.takeLast(6).filter { it.done || it.isUser }
    val ctxBlock = if (ctxMsgs.isEmpty()) {
        ""
    } else {
        val sb = StringBuilder("\n\n【之前的对话】\n")
        ctxMsgs.forEach { m ->
            sb.append(if (m.isUser) "用户: " else "助手: ")
            sb.append(m.text.take(800)).append("\n")
        }
        sb.toString()
    }
    val prompt = context.getString(R.string.browser_search_prompt, question) + ctxBlock
    val sb = StringBuilder()
    onUpdate(context.getString(R.string.browser_thinking_status), emptyList(), false)
    com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
        prompt,
        onTool = { _, _, _, _ -> },
        onText = { t ->
            sb.append(t)
            onUpdate(sb.toString(), URL_REGEX.findAll(sb.toString()).map { it.value }.distinct().take(5).toList(), false)
        },
        onDone = { d ->
            val final = if (sb.isNotEmpty()) sb.toString() else d
            onUpdate(final, URL_REGEX.findAll(final).map { it.value }.distinct().take(5).toList(), true)
        },
        onError = { e ->
            val msg = context.getString(R.string.browser_error_message, e)
            onUpdate(msg, emptyList(), true)
        },
    )
}

private fun showReader(context: Context, pageText: String?) {
    val readable = extractReadableText(context, pageText)
    if (readable.isBlank()) {
        Toast.makeText(context, context.getString(R.string.browser_cannot_read_content), Toast.LENGTH_SHORT).show()
        return
    }
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setMessage(readable)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

private fun speakPage(context: Context, pageText: String?, ttsRef: MutableState<TextToSpeech?>) {
    val readable = extractReadableText(context, pageText).take(3000)
    if (readable.isBlank()) {
        Toast.makeText(context, context.getString(R.string.browser_no_content_to_speak), Toast.LENGTH_SHORT).show()
        return
    }
    val t = ttsRef.value
    if (t != null && t.isSpeaking) {
        t.stop(); return
    }
    if (ttsRef.value == null) {
        ttsRef.value = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef.value?.language = Locale.CHINESE
                ttsRef.value?.speak(readable, TextToSpeech.QUEUE_FLUSH, null, "page")
            }
        }
    } else {
        ttsRef.value?.language = Locale.CHINESE
        ttsRef.value?.speak(readable, TextToSpeech.QUEUE_FLUSH, null, "page")
    }
}

private fun extractReadableText(context: Context, tree: String?): String {
    if (tree.isNullOrBlank()) return ""
    val chrome = context.getString(R.string.browser_chrome_filter).split(",").map { it.trim() }.toSet()
    val seen = LinkedHashSet<String>()
    Regex("(?:text|desc)=\"([^\"]+)\"").findAll(tree).forEach { m ->
        val s = m.groupValues[1].trim()
        if (s.length >= 2 && s !in chrome && !s.startsWith("http")) seen.add(s)
    }
    return seen.joinToString("\n")
}
