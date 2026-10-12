package com.apk.claw.android.ui.desktop

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.KeepAliveBack
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.ui.compose.screen.AgentSquareScreen
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.DiscoverScreen
import com.apk.claw.android.ui.voice.VoiceCallActivity
import com.apk.claw.android.ui.device.DeviceListActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import com.apk.claw.android.ui.compose.theme.tvOverscan
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「数字管家桌面」—— 科幻全息交互桌面(v2)。
 *
 * 统一触屏 + D-pad 焦点导航,适配手机/平板/TV 投屏:
 *  - 全息半透明浮动窗口(聚焦时更实、失焦更透,core 青边框高亮)
 *  - 所有交互元素可 D-pad 聚焦,方向键遍历
 *  - overscan 安全区(四边 48dp),TV 投屏不裁切
 *  - 放大字号(3m 观看距离可读)
 *  - 复用 [BrowserEngine] + [ChatAgentBridge],触屏与遥控器同一套交互
 *
 * 锁横屏 + `configChanges` 防旋转 recreate 闪断 WebView(见 AndroidManifest)。
 */
class DesktopActivity : AppCompatActivity() {

    private var engine: BrowserEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 无障碍保活:根页滑动返回改退后台(页内浏览返回由下方 BackHandler 优先接管,不冲突)。
        KeepAliveBack.install(this)
        hideSystemStatusBar()
        // 常亮:桌面模式面向支起来/投显示器的场景,前台时不熄屏(离开 Activity 自动解除)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val eng = BrowserEngineFactory.selectBest(this)
        engine = eng
        // 注册为当前浏览器引擎:此后 Agent 的 browser_* 工具作用到桌面这块 WebView。
        // 规矩:前台谁显示 WebView 谁 setBrowserEngine;桌面模式在前台时不再并存 BrowserActivity。
        ToolRegistry.setBrowserEngine(eng)
        setContent {
            OctopusTheme {
                DesktopWorkspace(eng)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 桌面模式常驻 + KEEP_SCREEN_ON,后台时尤其需要暂停 WebView 的 JS 定时器 / 网络 / 音频。
        engine?.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine?.onResume()
        // 沉浸态在切走再回来时可能被系统恢复,重进时再隐一次。
        hideSystemStatusBar()
    }

    /**
     * 桌面/TV 横屏为沉浸态:隐藏系统状态栏(时间/信号/电量),避免与右上角控制中心([TvTopChrome])
     * 重叠;下滑可临时唤出。只隐状态栏、保留导航手势区,不影响返回。
     */
    private fun hideSystemStatusBar() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.destroy()
        ToolRegistry.clearBrowserEngine()
        engine = null
    }
}

@Composable
private fun DesktopWorkspace(engine: BrowserEngine) {
    var currentUrl by remember { mutableStateOf(engine.currentUrl()) }
    var pageTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    // 引擎事件驱动:用户手动导航或 Agent 导航都会走这里
    LaunchedEffect(engine) {
        engine.events().collect { ev ->
            when (ev) {
                is EngineEvent.PageStarted -> {
                    loading = true; progress = 0
                    // onPageStarted 已把 currentUrlValue 设为新 URL,取来作「正在打开 X」
                    currentUrl = engine.currentUrl()
                }
                is EngineEvent.ProgressChanged -> {
                    progress = ev.percent
                    if (ev.percent in 1..99) loading = true
                    if (ev.percent >= 100) loading = false
                }
                is EngineEvent.PageFinished -> {
                    loading = false; progress = 100
                    currentUrl = ev.url; pageTitle = ev.title
                    // 同步到共享标签 store(竖屏浏览器也看得到)
                    com.apk.claw.android.ui.browser.BrowserTabsStore.updateCurrent(ev.url, ev.title)
                }
                is EngineEvent.Error -> loading = false
                else -> {}
            }
        }
    }

    // 桌面:浏览器是常驻底板;发现/广场/mini-app 以可拖浮动窗口打开(移植 OpenRoom windowManager)。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // TV 首页:同一时刻只聚焦一件内容(选中磁贴/头像 → 内容层);null = 停在首页磁贴网格。
    var openContent by remember { mutableStateOf<WinContent?>(null) }
    // 钉住:内容层从居中浮层切到右侧停靠面板(Copilot 式:左桌面 + 右对话)。记住用户偏好,不随开关重置。
    var pinned by remember { mutableStateOf(false) }
    val openWindow: (WinContent) -> Unit = { kind -> openContent = kind }
    // app_action 未运行时请桌面把 mini-app 开成窗口(后台线程 → 切主线程 openWindow)
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.apk.claw.android.plugin.MiniAppWindowController.opener = { appId ->
            val m = MiniAppRegistry.get(appId)
            if (m == null) false else { mainHandler.post { openWindow(WinContent.Mini(appId, m.name)) }; true }
        }
        onDispose { com.apk.claw.android.plugin.MiniAppWindowController.opener = null }
    }

    // HUD 遥测:母体连接态(时钟下沉到 TopBar/Taskbar 各自内部,避免全工作区每秒重组)
    val connState by appViewModel.connectionState.collectAsState()
    val connColor = when (connState) {
        ConnectionState.ONLINE -> Holo.Accent
        ConnectionState.CONNECTING, ConnectionState.CONNECTED,
        ConnectionState.HELLO_SENT, ConnectionState.RECONNECTING -> Holo.Warning
        else -> Holo.AccentDim
    }
    // 对话:主 Agent 会话([ChatAgentBridge])。convo 与对话模式(ChatScreen)共享 SessionStore/ChatStore
    // 持久化 —— 同一角色在 TV 模式和对话模式下共享同一份对话历史,不再各存各的。
    val convo = remember { androidx.compose.runtime.snapshots.SnapshotStateList<DeskMsg>() }
    // 当前角色的当前会话 ID(从 SessionStore 取/建),消息通过 ChatStore 持久化。
    var currentSessionId by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var toolNote by remember { mutableStateOf("") }
    var seq by remember { mutableLongStateOf(0L) }
    // Hero 壁纸走「连环画」式:不每条都生成(太耗算力/额度),每隔几轮出新一「格」,延续同一故事线,
    // 且不把用户对话烧进图里。可在顶栏控制中心关。
    var sceneUrl by remember { mutableStateOf<String?>(null) }
    var sceneGen by rememberSaveable { mutableStateOf(true) }
    var sceneTurn by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 当前角色:统一读取一次,下传给 windowSpec / 子组件,避免散读 CharacterRegistry.current。
    // 不用 remember{} —— current 的 getter 订阅 idx 状态,切角色时自动重组(此时才读)。
    val character = CharacterRegistry.current

    // 将 ChatMessage(ChatStore 持久化格式)转为 DeskMsg(TV 桌面气泡格式)。
    fun chatMessagesToDesk(msgs: List<com.apk.claw.android.ui.compose.screen.ChatMessage>): List<DeskMsg> {
        val result = mutableListOf<DeskMsg>()
        for (m in msgs) {
            when (m) {
                is com.apk.claw.android.ui.compose.screen.ChatMessage.UserMessage ->
                    result.add(DeskMsg(seq++, fromUser = true, text = m.text))
                is com.apk.claw.android.ui.compose.screen.ChatMessage.AgentMessage ->
                    result.add(DeskMsg(seq++, fromUser = false, text = m.text))
                else -> {}
            }
        }
        return result
    }
    // 将 DeskMsg 转回 ChatMessage 用于持久化。
    fun deskToChatMessages(desk: List<DeskMsg>): List<com.apk.claw.android.ui.compose.screen.ChatMessage> =
        desk.map { m ->
            if (m.fromUser) com.apk.claw.android.ui.compose.screen.ChatMessage.UserMessage(m.text)
            else com.apk.claw.android.ui.compose.screen.ChatMessage.AgentMessage(m.text)
        }
    // 持久化当前 convo 到 ChatStore。
    fun persistConvo() {
        if (currentSessionId.isNotEmpty()) {
            com.apk.claw.android.ui.compose.screen.ChatStore.save(
                currentSessionId, deskToChatMessages(convo)
            )
        }
    }
    // 加载指定角色的会话历史到 convo(先持久化上一个角色的,并cancel运行中任务防串台)。
    fun loadCharacterSpace(charId: String) {
        if (running) {
            com.apk.claw.android.ui.compose.screen.ChatAgentBridge.cancel()
            running = false; toolNote = ""
        }
        persistConvo()
        val now = System.currentTimeMillis()
        val sessions = com.apk.claw.android.ui.compose.screen.SessionStore.ensureAtLeastOne(now, emptyList(), charId)
        val cid = com.apk.claw.android.ui.compose.screen.SessionStore.currentId(charId)
            ?.takeIf { id -> sessions.any { it.id == id } }
            ?: sessions.first().id
        com.apk.claw.android.ui.compose.screen.SessionStore.setCurrent(cid, charId)
        currentSessionId = cid
        convo.clear()
        val stored = com.apk.claw.android.ui.compose.screen.ChatStore.load(cid) ?: emptyList()
        convo.addAll(chatMessagesToDesk(stored))
    }
    // 角色变化时切换会话空间(LaunchedEffect 确保副作用安全,初始进入也触发一次加载)。
    LaunchedEffect(character.id) {
        loadCharacterSpace(character.id)
    }
    // 离开 TV 桌面(返回/切走 Activity)时兜底持久化,防丢失最近一条回复。
    DisposableEffect(Unit) {
        onDispose { persistConvo() }
    }

    val send: (String) -> Unit = fn@{ raw ->
        val t = raw.trim()
        if (t.isEmpty() || running) return@fn
        convo.add(DeskMsg(seq++, fromUser = true, text = t))
        if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
            convo.add(DeskMsg(seq++, fromUser = false, text = "请先配置模型(顶部「技能」旁或设置 → 模型),再和我对话。"))
            persistConvo()
            return@fn
        }
        // 连环画式壁纸:每 TV_SCENE_EVERY 轮才出新一「格」(省算力/额度),延续同一故事线;
        // prompt 不再引用用户消息、且明确 no text —— 不把对话烧进图里。异步不阻塞对话。
        if (sceneGen && sceneTurn % TV_SCENE_EVERY == 0) {
            val c = character
            val panel = sceneTurn / TV_SCENE_EVERY + 1
            scope.launch {
                com.apk.claw.android.media.MediaRepository.generateImage(scenePrompt(c, panel), "1280x720")
                    .onSuccess { sceneUrl = it.url }
            }
        }
        sceneTurn++
        running = true; toolNote = ""
        val buf = StringBuilder()
        var streamId: Long? = null
        // 回调都 post 到主线程(见 ChatAgentBridge),可直接改 Compose 状态。首个 token 才建角色气泡,
        // 后续按 id 原地更新(流式)。
        val put = { s: String ->
            val id = streamId
            if (id == null) { val m = DeskMsg(seq++, fromUser = false, text = s); streamId = m.id; convo.add(m) }
            else { val i = convo.indexOfFirst { it.id == id }; if (i >= 0) convo[i] = convo[i].copy(text = s) }
        }
        // 多轮上下文:带上最近几轮对话摘要(dropLast 排除刚 add 的本条),指代能接上文。
        val convContext = com.apk.claw.android.agent.ConversationContext.build(
            convo.dropLast(1).map { it.fromUser to it.text },
        )
        com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
            prompt = t,
            conversationContext = convContext,
            // TV 模式角色扮演:注入当前角色人设,切角色即换人应答(否则永远一口 Octopus 腔)。
            persona = character.personaPrompt(),
            onTool = { _, name, _, _ -> toolNote = "· 使用 $name" },
            onText = { tok -> buf.append(tok); put(buf.toString()) },
            onDone = { ans ->
                put(ans.ifBlank { buf.toString() }); running = false; toolNote = ""
                persistConvo()
            },
            onError = { e -> put("⚠️ $e"); running = false; toolNote = ""; persistConvo() },
        )
    }
    val stop = { com.apk.claw.android.ui.compose.screen.ChatAgentBridge.cancel(); running = false; toolNote = "" }
    // 是否在浏览网页:是则中间显玻璃浏览器盒,否则中间就是壁纸 + 浮动窗口
    val browsing = currentUrl.isNotBlank() && currentUrl != "about:blank"

    // TV 遥控兼容:无触屏设备(TV 盒子)进桌面时,把焦点落到第一个图标,D-pad 立即可用;
    // 手机有触屏 → 不抢焦点(触屏直接点,避免无端高亮)。
    val firstIconFocus = remember { FocusRequester() }
    val hasTouch = remember {
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }
    LaunchedEffect(Unit) { if (!hasTouch) runCatching { firstIconFocus.requestFocus() } }
    // 遥控/系统返回键:有全屏内容层/浏览器时先关掉,否则才退出桌面。
    BackHandler(enabled = browsing || openContent != null) {
        when {
            browsing -> { engine.navigate("about:blank"); currentUrl = "about:blank"; pageTitle = "" }
            else -> openContent = null
        }
    }

    // Apple TV 式首页:壁纸全出血打底 + 顶栏 + 大图焦点磁贴网格;选中磁贴/头像 → 全屏内容层。
    // 头像作为常驻「悬浮桌面 agent」浮在右下角,点开即对话。
    Box(Modifier.fillMaxSize().tvOverscan()) {
        HoloBackground(Modifier.fillMaxSize())
        // 响应式:无触屏(TV/盒子)= 3 米外 10-foot 观看 → 图标/字号放大;手机触屏用常规尺寸。
        // 比例不写死:图标架给自然高度,Hero 用 weight(1f) 吃掉剩余高度,任意屏幕比例(手机 2.2:1 /
        // 电视 16:9 / 平板)都自动协调。
        val bigUi = !hasTouch
        // 钉住且有内容 → 右侧分屏:桌面让出 dockW 缩到左侧,对话面板并排在右(不重叠、不遮桌面)。
        val docked = pinned && openContent != null
        val dockW = if (bigUi) 460.dp else 360.dp
        // 桌面内容(Apple TV 式):Hero 全宽头 + 图标网格,整屏纵向瀑布流——向下滚/翻页看更多图标。
        // Hero 点击 = 和角色对话。分屏窄区列数减少,自然多排、往下翻。
        val desktopContent = @Composable {
            Box(Modifier.fillMaxSize()) {
                // 场景壁纸全屏打底(若有):与底层 HoloBackground 叠加,柔和压暗保证卡片可读。
                if (sceneUrl != null) {
                    coil.compose.AsyncImage(
                        model = sceneUrl,
                        contentDescription = character.zh,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(
                        Modifier.matchParentSize()
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(TvWallScrim)),
                    )
                }
                // ── 数据填充 ── 不 remember:角色切换/新装小程序即时反映(列表小,重建无开销)。
                val featuredItems = listOf(
                    TvFeaturedItem(
                        "f1", character.zh, "${character.codename} · ${character.role}",
                        listOf(Holo.Surface2, Holo.Panel),
                        action = { openWindow(WinContent.Chat) },
                    ),
                    TvFeaturedItem(
                        "f2", "广场热门", "发现有趣的 Agent 与对话",
                        listOf(Holo.Surface2, Holo.Panel),
                        action = { openWindow(WinContent.Square) },
                    ),
                    TvFeaturedItem(
                        "f3", "小程序中心", "扩展 Echo 的能力边界",
                        listOf(Holo.Surface2, Holo.Panel),
                        action = { openWindow(WinContent.Discover) },
                    ),
                )
                val quickActions = listOf(
                    TvLauncherItem(
                        "qa_chat", "对话", "和 ${character.zh} 聊聊",
                        icon = Icons.Filled.ChatBubbleOutline, accentColor = Holo.TextHud,
                        action = { openWindow(WinContent.Chat) },
                    ),
                    TvLauncherItem(
                        "qa_square", "广场", "Agent 广场",
                        icon = Icons.Filled.Explore, accentColor = Holo.TextHud,
                        action = { openWindow(WinContent.Square) },
                    ),
                    TvLauncherItem(
                        "qa_desktop", "桌面", "系统桌面",
                        icon = Icons.Filled.DesktopWindows, accentColor = Holo.TextHud,
                        action = {
                            val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME)
                                .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(home) }
                        },
                    ),
                    TvLauncherItem(
                        "qa_settings", "设置", "应用设置",
                        icon = Icons.Filled.Settings, accentColor = Holo.TextHud,
                        action = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(ctx, com.apk.claw.android.ui.settings.SettingsActivity::class.java),
                                )
                            }
                        },
                    ),
                )
                val squareItems = listOf(
                    TvLauncherItem("sq1", "热门 Agent", "广场精选", icon = Icons.Filled.AutoAwesome, accentColor = Holo.TextHud, badge = "热", action = { openWindow(WinContent.Square) }),
                    TvLauncherItem("sq2", "新上线", "本周新品", icon = Icons.Filled.PushPin, accentColor = Holo.TextHud, badge = "新", action = { openWindow(WinContent.Square) }),
                    TvLauncherItem("sq3", "创作灵感", "找点灵感", icon = Icons.Filled.AutoAwesome, accentColor = Holo.TextHud, action = { openWindow(WinContent.Square) }),
                    TvLauncherItem("sq4", "效率工具", "提效合集", icon = Icons.Filled.Devices, accentColor = Holo.TextHud, action = { openWindow(WinContent.Square) }),
                )
                // 小程序:读 MiniAppRegistry(不 remember,新装即时出现);空态给一个去广场的入口。
                val miniAppItems = MiniAppRegistry.all().let { all ->
                    if (all.isEmpty()) {
                        listOf(TvLauncherItem("ma_empty", "去广场安装", "暂无小程序", icon = Icons.Filled.Apps, accentColor = Holo.TextHud, action = { openWindow(WinContent.Square) }))
                    } else {
                        all.map { m ->
                            TvLauncherItem(
                                id = "ma_${m.id}", title = m.name.ifBlank { m.id }, subtitle = "小程序",
                                icon = Icons.Filled.Apps, accentColor = Holo.TextHud,
                                action = { openWindow(WinContent.Mini(m.id, m.name)) },
                            )
                        }
                    }
                }
                val skillItems = listOf(
                    TvLauncherItem("sk1", "对话增强", "更自然的对话", icon = Icons.Filled.ChatBubbleOutline, accentColor = Holo.TextHud, action = { runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.SkillsActivity::class.java)) } }),
                    TvLauncherItem("sk2", "图像生成", "文字变图片", icon = Icons.Filled.AutoAwesome, accentColor = Holo.TextHud, action = { runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.SkillsActivity::class.java)) } }),
                    TvLauncherItem("sk3", "代码助手", "编程好帮手", icon = Icons.Filled.Keyboard, accentColor = Holo.TextHud, action = { runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.SkillsActivity::class.java)) } }),
                    TvLauncherItem("sk4", "语音识别", "说说话就行", icon = Icons.Filled.Mic, accentColor = Holo.TextHud, action = { runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.SkillsActivity::class.java)) } }),
                )
                val mediaItems = listOf(
                    TvLauncherItem("md1", "视频", "在线视频", icon = Icons.Filled.Videocam, accentColor = Holo.TextHud, action = { openWindow(WinContent.Discover) }),
                    TvLauncherItem("md2", "音乐", "听点音乐", icon = Icons.Filled.Mic, accentColor = Holo.TextHud, action = { openWindow(WinContent.Discover) }),
                    TvLauncherItem("md3", "图片", "图库浏览", icon = Icons.Filled.AutoAwesome, accentColor = Holo.TextHud, action = { openWindow(WinContent.Discover) }),
                    TvLauncherItem("md4", "资讯", "新闻阅读", icon = Icons.Filled.Language, accentColor = Holo.TextHud, action = { openWindow(WinContent.Discover) }),
                )
                val installedApps = rememberInstalledApps(ctx)
                // 多行卡片瀑布:Banner + 各分类行,每行横向独立滚动。
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
                ) {
                    item { TvFeaturedBanner(featuredItems, onItemClick = { it.action() }) }
                    item { TvLauncherRow("快捷入口", quickActions, onItemClick = { it.action() }, firstItemFocus = firstIconFocus) }
                    item { TvLauncherRow("广场热门", squareItems, onItemClick = { it.action() }) }
                    item { TvLauncherRow("小程序", miniAppItems, onItemClick = { it.action() }) }
                    item { TvLauncherRow("技能", skillItems, onItemClick = { it.action() }) }
                    item { TvLauncherRow("媒体", mediaItems, onItemClick = { it.action() }) }
                    item { TvLauncherRow("应用", installedApps, onItemClick = { it.action() }) }
                }
                // 极简顶栏:浮在右上(满屏 / 分屏左区均对齐)。
                TvTopChrome(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp),
                    connColor = connColor,
                    sceneOn = sceneGen,
                    onToggleScene = { sceneGen = !sceneGen },
                    onDeviceSwitch = {
                        runCatching { ctx.startActivity(android.content.Intent(ctx, DeviceListActivity::class.java)) }
                    },
                    onExit = { (ctx as? android.app.Activity)?.finish() },
                )
            }
        }

        // 未钉:桌面满屏、重心居中。钉住:桌面填满「左区」(让出 dockW),无边框、与右侧 dock 无缝对接。
        Box(Modifier.fillMaxSize().padding(end = if (docked) dockW else 0.dp)) {
            desktopContent()
        }
        if (!docked) {
            // 头像悬浮桌面 agent:常驻右下,点开即对话(分屏时隐藏,右侧已在对话)。
            FloatingAgentAvatar(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp),
                running = running,
                onClick = { openWindow(WinContent.Chat) },
            )
        }
        // 内容层:钉住 → 右侧分屏面板(与左桌面并排,不遮挡);未钉 → 居中较窄浮层(带遮罩)。
        openContent?.let { kind ->
            val body: @Composable () -> Unit = {
                when (kind) {
                    WinContent.Chat -> ChatContent(
                        convo = convo, running = running, toolNote = toolNote, narrow = docked,
                    )
                    WinContent.Character -> CharacterHud(Modifier.fillMaxSize())
                    WinContent.Discover -> DiscoverScreen(onOpenUrl = { url -> url?.let { engine.navigate(normalizeUrl(it)) } })
                    WinContent.Square -> AgentSquareScreen(onBack = { openContent = null })
                    is WinContent.Mini -> MiniAppWindow(kind.appId)
                }
            }
            val bar: (@Composable () -> Unit)? = if (kind == WinContent.Chat) {
                @Composable { DesktopReplyBar(running = running, onSend = send, onStop = stop) }
            } else {
                null
            }
            val panel = @Composable {
                TvContentPanel(
                    title = contentTitle(kind, character.name),
                    onClose = { openContent = null },
                    pinned = pinned,
                    onTogglePin = { pinned = !pinned },
                    bottomBar = bar,
                    content = body,
                )
            }
            if (docked) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(dockW).fillMaxHeight().background(TvShelfBg),
                ) { panel() }
            } else {
                Box(Modifier.fillMaxSize().background(TvOverlayScrim), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxHeight(fraction = 0.92f)
                            .fillMaxWidth(fraction = 0.58f).widthIn(min = 360.dp, max = 620.dp),
                    ) { panel() }
                }
            }
        }
        // 浏览网页:居中较宽浮层(暂不参与右侧分屏)。
        if (browsing) {
            Box(Modifier.fillMaxSize().background(TvOverlayScrim), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight(fraction = 0.94f).fillMaxWidth(fraction = 0.74f)) {
                    TvContentPanel(
                        title = pageTitle.ifBlank { currentUrl },
                        onClose = { engine.navigate("about:blank"); currentUrl = "about:blank"; pageTitle = "" },
                        pinned = false,
                        onTogglePin = {},
                        bottomBar = null,
                    ) {
                        DesktopMonitor(
                            engine = engine,
                            currentUrl = currentUrl,
                            pageTitle = pageTitle,
                            loading = loading,
                            progress = progress,
                            browsing = true,
                            onNavigate = { currentUrl = it; pageTitle = "" },
                        )
                    }
                }
            }
        }
    }
}

/** 全屏内容层的标题。 */
private fun contentTitle(kind: WinContent, name: String): String = when (kind) {
    WinContent.Chat -> "对话 · $name"
    WinContent.Character -> "角色档案 · $name"
    WinContent.Discover -> "发现"
    WinContent.Square -> "广场"
    is WinContent.Mini -> kind.name
}

// ── 布局/动画常量(top-level 具名:满足 MagicNumber 豁免) ──
private const val TV_SCENE_EVERY = 3                // 连环画:每几轮对话才生成新一格壁纸(节流,省算力/额度)
private const val THINKING_DOT_COUNT = 3            // 思考点动画数量
private const val THINKING_DOT_DURATION_MS = 460    // 思考点单次脉动时长
private const val THINKING_DOT_STAGGER_MS = 150     // 思考点依次脉动的错峰间隔
private val TvShelfBg = Color(0xF2101216)
// 全屏壁纸的柔和压暗:顶(状态栏/顶栏可读)—中段全透—底(标题/图标可读),均匀无硬边。
private val TvWallScrim = listOf(Color(0x40000000), Color(0x00000000), Color(0x00000000), Color(0x73000000))

/** 控制中心磁贴(图标 + 标签,焦点高亮);active=开启态高亮,danger=危险色。 */
@Composable
private fun CcTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        danger -> Holo.Live
        active -> Holo.Accent
        else -> Holo.TextHud
    }
    Column(
        modifier = Modifier
            .size(width = 108.dp, height = 66.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Holo.Accent.copy(alpha = 0.24f) else Holo.Surface2)
            .holoFocus(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
    }
}

/** 极简顶栏:状态点 + 时间 + 头像菜单(控制中心:设备切换 / 切换角色 / 场景 / 退出),浮在 Hero 右上。 */
@Composable
private fun TvTopChrome(
    connColor: Color,
    sceneOn: Boolean,
    onToggleScene: () -> Unit,
    onDeviceSwitch: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val timeText = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }.format(Date(nowMs))
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HoloDot(connColor)
        Text(timeText, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Box {
            // 应用内 agent 头像 = 当前角色头像(和右下角悬浮头像同一来源 CharacterAvatar);
            // 点开即控制中心。八爪鱼图标只留给应用外品牌位(桌面图标/通知/闪屏)。
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .holoFocus(CircleShape).clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                CharacterAvatar(32.dp)
            }
            // 控制中心式下拉:2×2 玻璃磁贴(参考 Apple TV 控制中心)。
            androidx.compose.material3.DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = Holo.Panel,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CcTile(Icons.Filled.Devices, "设备切换") { onDeviceSwitch(); menu = false }
                        CcTile(Icons.Filled.Person, "切换角色") { CharacterRegistry.next(); menu = false }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CcTile(
                            Icons.Filled.AutoAwesome,
                            if (sceneOn) "场景 开" else "场景 关",
                            active = sceneOn,
                        ) { onToggleScene() }
                        CcTile(Icons.Filled.Close, "退出竖屏", danger = true) { onExit(); menu = false }
                    }
                }
            }
        }
    }
}

/** 头像悬浮桌面 agent:常驻角落,运行时描边转红;点击打开对话。 */
@Composable
private fun FloatingAgentAvatar(
    modifier: Modifier,
    running: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(2.dp, if (running) Holo.Live else Holo.Accent, CircleShape)
            .holoFocus(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { CharacterAvatar(72.dp) }
}

/** 全屏内容层的半透明遮罩底色(压暗壁纸,突出内容)。 */
private val TvOverlayScrim = Color(0xE6060708)

/** 内容层顶栏小圆按钮(返回 / 钉);active=true 高亮。 */
@Composable
private fun TvIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Holo.Accent.copy(alpha = 0.22f) else Color.Transparent)
            .holoFocus(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = desc,
            tint = if (active) Holo.Accent else Holo.TextHud, modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 内容层面板(顶栏:返回 + 标题 + 钉;中间玻璃内容盒;可选底部输入)。
 * 定位交给布局层:未钉 = 居中较窄浮层;钉住 = 右侧分屏面板(与左桌面并排,不重叠)。
 */
@Composable
private fun TvContentPanel(
    title: String,
    onClose: () -> Unit,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    bottomBar: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconBtn(Icons.Filled.Close, "返回", onClose)
            Spacer(Modifier.width(8.dp))
            Text(title, color = Holo.TextHud, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TvIconBtn(
                Icons.Filled.PushPin,
                if (pinned) "取消停靠" else "停靠右侧",
                onTogglePin,
                active = pinned,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).holoGlass(16.dp).clipToBounds(),
        ) { content() }
        bottomBar?.let {
            Spacer(Modifier.height(8.dp))
            it()
        }
    }
}

/**
 * 对话窗内容(HoloWindow 提供边框/标题):左角色立绘常驻 | 右会话气泡列表(顶条:音波 + 阶段)。
 * 会话为主 Agent 同一条 [convo]。
 */
@Composable
private fun ChatContent(convo: List<DeskMsg>, running: Boolean, toolNote: String, narrow: Boolean = false) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(convo.size, convo.lastOrNull()?.text) {
        if (convo.isNotEmpty()) runCatching { listState.animateScrollToItem(convo.size - 1) }
    }
    Row(Modifier.fillMaxSize()) {
        // 左:当前角色立绘常驻(更深底,底部站立)。窄栏(右侧分屏)时隐藏,给会话让宽。
        if (!narrow) {
            Box(Modifier.width(140.dp).fillMaxHeight().background(Holo.AvatarBg)) {
                HoloFigure(
                    CharacterRegistry.current.frontRes,
                    Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.98f)
                        .aspectRatio(0.5f, matchHeightConstraintsFirst = true),
                )
                Text(
                    CharacterRegistry.current.zh, color = Holo.Accent,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                )
            }
        }
        // 右:会话气泡。去掉了「阶段 x/4」条;思考时底部内联一行青色竖点脉动,不再弹独立思考窗。
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize()) {
                if (convo.isEmpty() && !running) {
                    // 空态:小气泡(不占满、不做大灰块),像角色发来的第一句。
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopStart) {
                        Text(
                            "点下面的输入框,和我说点什么。",
                            color = Holo.TextHud, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Holo.Surface2)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    ) {
                        items(convo, key = { it.id }) { m -> DialogBubble(m) }
                        if (running) {
                            item(key = "thinking") { ThinkingRow(toolNote) }
                        }
                    }
                }
            }
        }
    }
}


/** 思考指示行:几个青色竖点脉动 + 内联状态文字(工具名 / 默认「思考中…」)。状态嵌在对话里,不弹独立悬浮窗。 */
@Composable
private fun ThinkingRow(toolNote: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThinkingDots()
        Text(toolNote.ifBlank { "思考中…" }, color = Holo.Accent, fontSize = 10.sp)
    }
}

/** 几个青色竖点,依次脉动 —— 思考中的极简动态,替代原音波/阶段条。 */
@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "think")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(THINKING_DOT_COUNT) { i ->
            val hf = t.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(
                        THINKING_DOT_DURATION_MS,
                        delayMillis = i * THINKING_DOT_STAGGER_MS,
                    ),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "d$i",
            ).value
            Box(
                Modifier.width(4.dp).height((16 * hf).dp).clip(RoundedCornerShape(2.dp))
                    .background(Holo.Accent),
            )
        }
    }
}

/** 会话气泡:用户右对齐(core 青底深字),角色左对齐(深底浅字)。 */
@Composable
private fun DialogBubble(m: DeskMsg) {
    Row(Modifier.fillMaxWidth()) {
        if (m.fromUser) Spacer(Modifier.weight(0.18f))
        Box(
            modifier = Modifier.weight(0.82f).clip(RoundedCornerShape(12.dp))
                .background(if (m.fromUser) Holo.Accent else Holo.Surface2)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                m.text,
                color = if (m.fromUser) Holo.OnAccent else Holo.TextHud,
                fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        if (!m.fromUser) Spacer(Modifier.weight(0.18f))
    }
}

/**
 * 屏幕正底部回复条(对齐 OpenRoom):上排快捷回复气泡 + 下排雪白输入胶囊(action)。
 * 运行中输入胶囊变停止键。快捷回复为预设短句,点击即发。
 */
/** 快捷回复气泡:居中 + 自动换行(窄栏 dock 放不下就往下另起一行)。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReplyQuickPrompts(prompts: List<String>, onSend: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prompts.forEach { q ->
            Text(
                q, color = Holo.TextHud, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Holo.Surface2.copy(alpha = 0.7f))
                    .holoFocus(RoundedCornerShape(9.dp))
                    .clickable { onSend(q) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun DesktopReplyBar(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var input by remember { mutableStateOf("") }
    // 语音输入:voiceMode = 收起成小圆气泡的语音模式;listening = 正在识别;partial = 实时文本。
    var voiceMode by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = remember { com.apk.claw.android.octopus_mobile.VoiceInput(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.destroy() } }
    val micPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voiceMode = true }
    val hasMic = {
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val quick = remember { listOf("你能做什么?", "帮我打开一个网页", "整理一下今天的信息") }
    val charName = CharacterRegistry.current.name
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 快捷回复气泡(运行中隐藏,避免误触)。
        if (!running) {
            ReplyQuickPrompts(quick, onSend)
        }
        if (voiceMode) {
            // 收起的小圆气泡语音模式:按住麦克风说话、松手发送;上方显示实时识别文本。
            VoiceBubbleInput(
                listening = listening,
                partial = partial,
                onStartHold = {
                    listening = true
                    partial = ""
                    voice.start(
                        onPartial = { partial = it },
                        onResult = { t -> listening = false; partial = ""; if (t.isNotBlank()) onSend(t) },
                        onError = { listening = false; partial = "" },
                    )
                },
                onRelease = { voice.stop() },
                onExitVoice = { if (!listening) voiceMode = false },
            )
        } else {
            // 输入胶囊(雪白 action)+ 麦克风(切语音)+ 发送
            Row(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Holo.Action)
                    .padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Holo.OnAction, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Holo.OnAction),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { onSend(input); input = "" } }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                "$charName 在等你回复…",
                                color = Holo.OnAction.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1,
                            )
                        }
                        inner()
                    },
                )
                // 实时语音通话:进全屏通话页(TV 桌面上即大屏语音对话,豆包式)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).holoFocus(CircleShape).clickable {
                        runCatching {
                            ctx.startActivity(android.content.Intent(ctx, VoiceCallActivity::class.java))
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Call, contentDescription = "实时语音通话",
                        tint = Holo.OnAction.copy(alpha = 0.8f), modifier = Modifier.size(20.dp),
                    )
                }
                // 麦克风:收起成小圆气泡语音模式(无权限先申请)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).holoFocus(CircleShape).clickable {
                        if (hasMic()) voiceMode = true
                        else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic, contentDescription = "语音输入",
                        tint = Holo.OnAction.copy(alpha = 0.8f), modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Holo.OnAction)
                        .holoFocus(CircleShape)
                        .clickable {
                            if (running) onStop()
                            else if (input.isNotBlank()) { onSend(input); input = "" }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                        contentDescription = if (running) "停止" else "发送",
                        tint = Holo.Accent, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 收起的小圆气泡语音输入:按住大圆麦克风说话、松手发送;上方实时识别文本;左侧键盘键退回文本输入。 */
@Composable
private fun VoiceBubbleInput(
    listening: Boolean,
    partial: String,
    onStartHold: () -> Unit,
    onRelease: () -> Unit,
    onExitVoice: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (partial.isNotBlank()) {
            Text(
                partial, color = Holo.TextHud, fontSize = 13.sp, maxLines = 2,
                modifier = Modifier.widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Holo.Surface2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            Text(
                if (listening) "正在聆听…松手发送" else "按住说话",
                color = Holo.TextSecondary, fontSize = 12.sp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 退回键盘(文本输入)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Holo.Surface2)
                    .holoFocus(CircleShape).clickable(onClick = onExitVoice),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Keyboard, contentDescription = "键盘", tint = Holo.TextHud, modifier = Modifier.size(20.dp)) }
            // 大圆麦克风气泡:按住说话,松手发送。识别中变红。
            Box(
                Modifier.size(if (listening) 64.dp else 56.dp).clip(CircleShape)
                    .background(if (listening) Holo.Live else Holo.Accent)
                    .holoFocus(CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onStartHold()
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, contentDescription = "按住说话", tint = Holo.OnAccent, modifier = Modifier.size(28.dp)) }
        }
    }
}

@Composable
private fun DesktopMonitor(
    engine: BrowserEngine,
    currentUrl: String,
    pageTitle: String,
    loading: Boolean,
    progress: Int,
    browsing: Boolean,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 浏览器 chrome(标签/地址栏/进度)仅在浏览网页时出现;空闲时中间是壁纸,不占地方。
        if (browsing) {
            DesktopTabStrip(engine, onNavigate)
            DesktopAddressBar(currentUrl, pageTitle, loading) { url ->
                engine.navigate(url)
                onNavigate(url)
            }
            if (loading) {
                Text(
                    stringResource(R.string.desktop_opening_host, hostOf(currentUrl)),
                    color = Holo.Accent, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().background(Holo.Panel)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Holo.Accent,
                    trackColor = Color.Transparent,
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            // WebView 常挂(引擎/Agent browser_* 需要),空闲置 GONE:about:blank 白屏不穿透盖住壁纸。
            AndroidView(
                factory = { ctx -> engine.createView(ctx) },
                update = { it.visibility = if (browsing) android.view.View.VISIBLE else android.view.View.GONE },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DesktopAddressBar(currentUrl: String, pageTitle: String, loading: Boolean, onGo: (String) -> Unit) {
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Holo.Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态点:加载中 core 青,空闲/完成灰
        Dot(if (loading) Holo.Accent else Holo.TextSecondary)
        Spacer(Modifier.width(8.dp))
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(pageTitle.ifBlank { stringResource(R.string.desktop_address_placeholder) }, fontSize = 13.sp, maxLines = 1, color = Holo.TextSecondary) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Holo.TextHud),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val u = normalizeUrl(text)
                if (u.isNotBlank()) onGo(u)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Holo.Surface2,
                unfocusedContainerColor = Holo.Surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        // 收藏 ★/☆(数据走共享 BookmarkManager,竖屏浏览器同一份)
        val isPage = currentUrl.isNotBlank() && currentUrl != "about:blank"
        if (isPage) {
            var marked by remember(currentUrl) { mutableStateOf(com.apk.claw.android.ui.browser.BookmarkManager.isBookmarked(currentUrl)) }
            Spacer(Modifier.width(6.dp))
            Text(
                if (marked) "★" else "☆",
                color = if (marked) Holo.Accent else Holo.TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        if (marked) com.apk.claw.android.ui.browser.BookmarkManager.remove(currentUrl)
                        else com.apk.claw.android.ui.browser.BookmarkManager.add(currentUrl, pageTitle.ifBlank { currentUrl })
                        marked = !marked
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 桌面浏览器标签条 —— 读共享 [com.apk.claw.android.ui.browser.BrowserTabsStore],与竖屏浏览器同一组标签。 */
@Composable
private fun DesktopTabStrip(engine: BrowserEngine, onNavigate: (String) -> Unit) {
    val tabsStore = com.apk.claw.android.ui.browser.BrowserTabsStore
    val tabs by tabsStore.tabs.collectAsState()
    val curId by tabsStore.currentId.collectAsState()
    val newTabLabel = stringResource(R.string.browser_new_tab)
    LaunchedEffect(Unit) { tabsStore.ensureAtLeastOne(newTabLabel) }
    if (tabs.size <= 1) return  // 单标签时不占地方

    val goto: (com.apk.claw.android.ui.browser.BrowserTabsStore.Tab?) -> Unit = { t ->
        if (t != null) { tabsStore.select(t.id); engine.navigate(t.url.ifBlank { "about:blank" }); onNavigate(t.url) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(Holo.Panel)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val active = t.id == curId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) Holo.Surface2 else Color.Transparent)
                    .border(1.dp, if (active) Holo.Accent else Holo.Border, RoundedCornerShape(6.dp))
                    .clickable { tabsStore.select(t.id); engine.navigate(t.url.ifBlank { "about:blank" }); onNavigate(t.url) }
                    .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.title.ifBlank { newTabLabel }.take(16),
                    color = if (active) Holo.TextHud else Holo.TextSecondary,
                    fontSize = 11.sp, maxLines = 1,
                )
                Text(
                    "×", color = Holo.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).clickable {
                        tabsStore.close(t.id, newTabLabel)
                        if (t.id == curId) goto(tabsStore.current())
                    }.padding(horizontal = 5.dp),
                )
            }
        }
        Text(
            "+", color = Holo.Accent, fontSize = 16.sp,
            modifier = Modifier.clip(CircleShape).clickable {
                tabsStore.newTab(newTabLabel); engine.navigate("about:blank"); onNavigate("")
            }.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** 桌面浮动窗口的内容类型。 */
private sealed interface WinContent {
    data object Chat : WinContent
    data object Discover : WinContent
    data object Square : WinContent
    data object Character : WinContent
    data class Mini(val appId: String, val name: String) : WinContent
}

/** 桌面对话一条消息。 */
private data class DeskMsg(val id: Long, val fromUser: Boolean, val text: String)

/**
 * 「连环画」第 [panel] 格的生成提示:延续同一角色的漫画故事线,每格推进一个新画面。
 * 关键:**不引用用户对话**(避免把消息烧进图里)+ 明确 no text/speech bubble/letters,出纯画面。
 */
private fun scenePrompt(c: CharacterProfile, panel: Int): String =
    "Panel $panel of an ongoing cinematic manga story starring ${c.name} (${c.codename}, ${c.role}). " +
        "Advance the narrative to the next dramatic beat in the same cyberpunk-neon world, keep the " +
        "character design consistent across panels, expressive manga illustration, dramatic rim lighting, " +
        "highly detailed, atmospheric. No text, no speech bubbles, no letters, no captions, no watermark, " +
        "no UI. 16:9."

/**
 * mini-app 浮动窗口内容 —— 用共享的 [com.apk.claw.android.plugin.MiniAppHost] 造 WebView(与全屏
 * Activity 同一沙箱),挂载时注册到 [com.apk.claw.android.plugin.MiniAppActionBus](Agent 的 app_action
 * 可派发到本窗口),关闭时注销并销毁 WebView。
 */
@Composable
private fun MiniAppWindow(appId: String) {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val manifest = remember(appId) { MiniAppRegistry.get(appId) } ?: return
    androidx.compose.runtime.DisposableEffect(appId) {
        onDispose { com.apk.claw.android.plugin.MiniAppActionBus.unregister(appId) }
    }
    AndroidView(
        factory = {
            val wv = com.apk.claw.android.plugin.MiniAppHost.createWebView(activity, manifest)
            if (wv != null) {
                com.apk.claw.android.plugin.MiniAppActionBus.registerLive(appId, activity, wv)
                wv
            } else {
                android.view.View(activity)
            }
        },
        onRelease = { v -> if (v is android.webkit.WebView) com.apk.claw.android.plugin.MiniAppHost.destroyWebView(v) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** 从 URL 取 host 作「正在打开 X」的 X;取不到就退回原串。 */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

/** 补 scheme:无协议头则默认 https://。 */
private fun normalizeUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    return when {
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
        else -> "https://$t"
    }
}
