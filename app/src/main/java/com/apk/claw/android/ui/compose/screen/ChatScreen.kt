package com.apk.claw.android.ui.compose.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.widget.MjpegImageView
import java.net.URLEncoder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.apk.claw.android.ui.compose.theme.EchoChatStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.apk.claw.android.ui.desktop.CharacterRegistry
import com.apk.claw.android.ui.desktop.personaPrompt
import com.apk.claw.android.utils.KVUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import android.content.Intent
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.agent.AgentActionRecorder
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.RemoteStreamPrefs
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.octopus_mobile.VoiceInput
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.ui.settings.LlmConfigActivity
import com.apk.claw.android.ui.voice.VoiceCallActivity
import com.apk.claw.android.ui.featurescreens.ActivityActivity
import com.apk.claw.android.ui.featurescreens.MemoryActivity
import com.apk.claw.android.ui.featurescreens.MiniAppListActivity
import com.apk.claw.android.ui.featurescreens.PersonasActivity
import com.apk.claw.android.ui.featurescreens.RoutinesActivity
import com.apk.claw.android.ui.featurescreens.SkillsActivity
import com.apk.claw.android.ui.featurescreens.TrustCenterActivity
import com.apk.claw.android.octopus_mobile.persona.PersonaStore
import com.apk.claw.android.ui.plugin.PluginActivity
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.apk.claw.android.ui.compose.screen.detail.CodeDetailPane
import com.apk.claw.android.ui.compose.screen.detail.DetailDrawer
import com.apk.claw.android.ui.compose.screen.detail.DetailPane
import com.apk.claw.android.ui.compose.screen.detail.DiffDetailPane
import com.apk.claw.android.ui.compose.screen.detail.PlanDetailPane
import com.apk.claw.android.ui.compose.screen.detail.TextDetailPane
import com.apk.claw.android.ui.compose.screen.detail.ToolsDetailPane
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Calendar

// 颜色
private val PrimaryColor get() = EchoChatStyle.accent
private val SuccessColor get() = OctopusColors.Success
private val WarningColor get() = OctopusColors.Warning
private val ErrorColor get() = OctopusColors.Error
private val AccentColor get() = OctopusColors.Accent
private val BackgroundColor get() = EchoChatStyle.background
private val SurfaceColor get() = EchoChatStyle.surface
private val SurfaceVariantColor get() = OctopusColors.SurfaceVariant
private val SurfaceDeepColor get() = EchoChatStyle.softSurface
private val TextPrimary get() = EchoChatStyle.text
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = EchoChatStyle.muted
private val BorderColor get() = EchoChatStyle.border
private val OverlayDimColor get() = OctopusColors.OverlayDim
private val OnPrimaryColor get() = OctopusColors.OnPrimary
private val VideoBackgroundColor get() = Color.Black
private val AgentBubbleColor get() = EchoChatStyle.softSurface
private val UserBubbleColor get() = EchoChatStyle.accentSurface

// 消息数据模型
sealed class ChatMessage {
    /** 稳定唯一 ID，用于 LazyColumn key 参数；不指定时由构造时自动生成 */
    abstract val id: Long

    data class UserMessage(
        val text: String,
        override val id: Long = nextId(),
        val requestId: String? = null,
        val delivery: MessageDeliveryStatus? = null,
        val deliveryFailure: MessageDeliveryFailure? = null,
    ) : ChatMessage()
    data class AgentMessage(
        val text: String,
        override val id: Long = nextId(),
        val streaming: Boolean = false,
        val remoteTaskId: String? = null,
    ) : ChatMessage()
    data class ToolCall(
        val icon: String,
        val toolName: String,
        val args: String,
        val result: String?,
        override val id: Long = nextId(),
        /**
         * 批量并行执行标识 —— 同一次 [ToolRegistry.executeToolsBatch] 调用产生的 ToolCall 共享同一 batchId。
         *
         * - `null`:串行执行的独立工具调用,UI 渲染为独立卡片(现状行为,INV-U3)
         * - 非 null:同 batchId 的 ToolCall 在 UI 层聚合为单张折叠卡片([ToolBatchCard])
         *
         * 见 `refine-chat-interaction` spec Task 2。
         */
        val batchId: String? = null,
        /** 工具执行耗时(ms),完成后由 [DefaultAgentService] 填入,UI 用于折叠卡片展开态显示。 */
        val durationMs: Long? = null,
        /** 工具执行状态:running / success / failure。null 表示未启动(默认)。 */
        val status: ToolCallStatus = ToolCallStatus.SUCCESS,
        /** Unique execution identity; preserved while snapshots update this row. */
        val callId: String? = null,
    ) : ChatMessage()

    /** 工具执行状态(用于 [ToolBatchCard] 展开态图标渲染)。 */
    enum class ToolCallStatus { RUNNING, SUCCESS, FAILURE, CANCELLED }

    data class Thinking(val text: String, override val id: Long = nextId()) : ChatMessage()

    /**
     * 产物消息 —— 类似 ChatGPT/Claude 的 Artifacts 面板,展示工具产生的结构化产物。
     *
     * Agent 工具(preview_html / generate_app / take_screenshot / run_code writeFile 等)
     * 产生的 HTML/图片/文件/diff 不再只藏在 48 字符摘要里,而是作为独立消息卡片展示,
     * 提供「预览」「打开」等入口。
     *
     * 持久化策略:大 payload(HTML/图片 base64)存到独立的 MMKV 键([artifactPayloadKey]),
     * 主消息列表只存引用键 + 元信息(类型/标题/路径),避免主列表臃肿。
     *
     * ## refine-chat-interaction 扩展
     *
     * 新增 3 个 ArtifactKind:
     * - [ArtifactKind.PLAN]:Plan 模式产物,标题「计划(N 步)」,详情走右侧栏 PlanDetailPane
     * - [ArtifactKind.CODE_SNIPPET]:search_code 产物,标题「file.kt:42-58」,详情走右侧栏 CodeDetailPane
     * - [ArtifactKind.TEXT]:Git 工具结果 + run_code stdout,标题如「commit abc123」,详情走右侧栏 TextDetailPane
     *
     * 新增字段 [collapsed]:默认 true,符合极简偏好(INV-U2)。消息流只显示标题 + preview,
     * 详情一律走右侧栏 DetailDrawer,不在消息流展开。
     */
    data class Artifact(
        val kind: ArtifactKind,
        val title: String,
        /** 文件路径(文件类产物用)或 payload 引用键(HTML/图片用)。 */
        val payloadRef: String,
        override val id: Long = nextId(),
        /**
         * 是否折叠展示。默认 true(INV-U2) —— 消息流只显示标题 + preview,
         * 点击卡片打开右侧栏 DetailDrawer 展开详情,不在消息流内展开。
         *
         * 仅 HTML/IMAGE 类(需要全屏预览)和既有 DIFF(向后兼容)会用到 false,
         * 新增 PLAN/CODE_SNIPPET/TEXT 一律 true。
         */
        val collapsed: Boolean = true,
    ) : ChatMessage()

    enum class ArtifactKind { HTML, IMAGE, FILE, DIFF, PLAN, CODE_SNIPPET, TEXT }
}

/**
 * 待用户选择的澄清问卷（选择题卡片）。
 * 由 generate_app 等工具在需求不明确时通过 onForm 回调下发，UI 在输入框上方渲染可点选选项卡片。
 * 用户选完点"开始生成"后，答案被构造成自然语言消息自动发送回 Agent。
 */
data class ClarifyQuestion(
    val q: String,
    val options: List<String>,
)

data class PendingClarifyForm(
    val toolName: String,
    val questions: List<ClarifyQuestion>,
    /** 用户当前已选中的选项索引（与questions同序），-1表示未选 */
    val selections: MutableList<Int> = mutableListOf(),
)

/** 自增 ID 计数器（线程安全）。仅用于 UI 层稳定 key，不参与业务逻辑。 */
private val chatIdCounter = java.util.concurrent.atomic.AtomicLong(0L)
private fun nextId(): Long = chatIdCounter.incrementAndGet()

private const val CHAT_MESSAGES_LIMIT = 240
private const val CHAT_SCROLL_THROTTLE_MS = 180L
private const val CHAT_STREAM_UI_THROTTLE_MS = 80L

/** 对话页当前角色的持久化键(与 TV 模式的选择互不影响)。 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen() {
    // 多会话:会话索引 + 当前会话 + 当前会话的消息
    val sessions = remember { mutableStateListOf<SessionStore.SessionMeta>() }
    var currentId by remember { mutableStateOf("") }
    // 角色空间:点顶栏「Octopus」可切角色(与 TV 模式同一批角色),会话与历史按角色完全隔离。
    var currentCharacter by remember {
        mutableStateOf(DotRoster.activeCharacter)
    }
    var charMenuOpen by remember { mutableStateOf(false) }
    var showDotPicker by remember { mutableStateOf(false) }
    var showDotScreen by remember { mutableStateOf(false) }
    var showBackgroundTasks by remember { mutableStateOf(false) }
    val dotEnvironment = DotEnvironmentStore.selected
    val taskState = DeviceExecutionStore.state(currentId, currentCharacter, dotEnvironment)
    val messages = taskState.messages
    val visibleTask by rememberUpdatedState(taskState)
    // A one-turn execution override never changes the displayed conversation/device.
    var delegatedEnvironment by remember(taskState.storageId) { mutableStateOf<DotEnvironment?>(null) }
    var connectionEnvironment by remember { mutableStateOf<DotEnvironment?>(null) }
    val executionEnvironment = delegatedEnvironment ?: dotEnvironment
    var showEnvironmentConnection by remember { mutableStateOf(false) }
    // 互聊花名册管理对话框
    var multiRosterDialogOpen by remember { mutableStateOf(false) }

    var inputText by taskState.draft
    var isRunning by taskState.running
    var moreMenuOpen by remember { mutableStateOf(false) }
    var previewDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    // 待填写的问卷表单（选择题）：工具返回时在输入框上方渲染可点选的选项卡片
    var pendingForm by taskState.form
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // 新建对话工作空间选择对话框(类似 Codex 启动时选项目目录)
    var showNewChatWorkspaceDialog by remember { mutableStateOf(false) }
    // 当前会话工作空间更改对话框(已有会话改工作空间)
    var showChangeWorkspaceDialog by remember { mutableStateOf(false) }
    // refine-chat-interaction Task 3:右侧详情抽屉当前面板。
    // null = 关闭,消息流可独立浏览(INV-U4)。具体 DetailPane 实例化由 Task 4 完成。
    var currentDetail by remember { mutableStateOf<DetailPane?>(null) }
    val openToolDetail: (List<ChatMessage.ToolCall>) -> Unit = { calls ->
        if (KVUtils.isDetailDrawerEnabled()) {
            val selection = ToolDetailSelection(messages, calls)
            currentDetail = ToolsDetailPane(callsProvider = { selection.resolve(messages) })
        }
    }
    val context = LocalContext.current
    val devices by ClawApplication.instance.deviceRegistry.deviceList.collectAsState()
    // 设备已不再独立成页：在主对话界面启动局域网发现 + 配置服务，
    // 使输入框的「目标选择器」能发现设备、本机也可被发现/被控。
    LaunchedEffect(Unit) {
        runCatching { ClawApplication.instance.deviceDiscoveryManager.start() }
        runCatching { ConfigServerManager.start(context) }
    }
    // 设置完成度:每次回到前台重新检测(配置/授权可能在外部页面变更)
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val llmOk = remember(refreshTick) { ChatAgentBridge.isConfigured() }
    val a11yOk = remember(refreshTick) { ClawAccessibilityService.isRunning() }
    // 工具组展开状态(key=组首工具 id),默认折叠
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var ghostChatJob by taskState.job
    var dotRequestGeneration by taskState.generation
    val ackText = stringResource(R.string.chat_ack)
    val thinkingText = stringResource(R.string.chat_thinking)
    val newChatTitle = stringResource(R.string.chat_new)
    val dotSendFailed = stringResource(R.string.dot_send_failed)
    var lastScrollTs by remember { mutableLongStateOf(0L) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var followLatest by rememberChatFollowLatest(listState, taskState.storageId) { scrollJob?.cancel() }
    val draggingMessages by listState.interactionSource.collectIsDraggedAsState()
    val showScrollToBottom by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }
    val pauseChatFollowing = { followLatest = false; scrollJob?.cancel(); Unit }
    LaunchedEffect(taskState.storageId) { followLatest = true; lastScrollTs = 0L }
    val scrollEnd = {
        val now = System.currentTimeMillis()
        if (followLatest && !draggingMessages && now - lastScrollTs > CHAT_SCROLL_THROTTLE_MS) {
            lastScrollTs = now
            scrollJob?.cancel()
            scrollJob = scope.launch {
                val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.animateScrollToItem(target)
            }
        }
        Unit
    }

    // 运行时消息上限：超过则丢弃最早的部分，防止长会话 OOM/卡顿
    LaunchedEffect(messages.size) {
        if (messages.size > CHAT_MESSAGES_LIMIT) {
            val drop = messages.size - CHAT_MESSAGES_LIMIT
            messages.subList(0, drop).clear()
        }
    }

    // 保存当前会话消息 + 用首条用户消息更新会话标题/时间并置顶
    val persist = {
        if (taskState.sessionId.isNotEmpty()) {
            ChatStore.save(taskState.storageId, messages)
            val firstUser = messages.firstOrNull { it is ChatMessage.UserMessage } as? ChatMessage.UserMessage
            val ghostPersona = GhostChatSessionStore.load(taskState.sessionId)
            if (firstUser != null || ghostPersona != null) {
                val title = ghostPersona?.let { GhostChatSessionStore.titleFor(it) }
                    ?: firstUser?.text?.take(18)
                    ?: newChatTitle
                val now = System.currentTimeMillis()
                SessionStore.updateMeta(taskState.sessionId, title, now)
                val i = sessions.indexOfFirst { it.id == taskState.sessionId }
                if (i >= 0) sessions[i] = sessions[i].copy(title = if (sessions[i].manuallyNamed) sessions[i].title else title, updatedAt = now)
            }
        }
    }

    // 加载某个角色的会话空间:该角色的会话列表 + 其当前会话与消息(与其他角色完全隔离)。
    val loadCharacterSpace = { char: String ->
        val idx = SessionStore.ensureAtLeastOne(System.currentTimeMillis(), emptyList(), char)
        sessions.clear(); sessions.addAll(idx)
        val cid = SessionStore.currentId(char)?.takeIf { c -> idx.any { it.id == c } } ?: idx.first().id
        SessionStore.setCurrent(cid, char)
        currentId = cid
        currentDetail = null

    }
    val stop = {
        if (taskState.pending != null) {
            DeviceExecutionStore.cancel(taskState)
        } else {
            dotRequestGeneration++
            ghostChatJob?.cancel(); ghostChatJob = null
            if (taskState.localAgent) ChatAgentBridge.cancel()
            taskState.localAgent = false
            isRunning = false
            interruptToolCalls(messages, "已停止")
            finishStreamingMessages(messages)
            messages.removeAll { it is ChatMessage.Thinking }
            persist()
        }
    }
    // Switching a view never cancels its execution or moves its callbacks to another lane.
    val closeDetailForSwitch = { currentDetail = null }
    val switchEnvironment = { environment: DotEnvironment ->
        if (environment != dotEnvironment) {
            val message = context.getString(R.string.dot_env_switched, context.getString(environment.tabLabel))
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        DotEnvironmentStore.select(environment)
        if (environment == DotEnvironment.PHONE) {
            ControlTarget.setLocal(); previewDevice = null
        }
    }
    LaunchedEffect(dotEnvironment) {
        if (dotEnvironment == DotEnvironment.PHONE) ControlTarget.setLocal()
    }
    if (showEnvironmentConnection) {
        DotConnectionDialog(connectionEnvironment ?: dotEnvironment,
            onDismiss = { showEnvironmentConnection = false; connectionEnvironment = null },
            onSaved = { showEnvironmentConnection = false; connectionEnvironment = null; refreshTick++ },
        )
    }
    // 初始化:确保至少一个会话,加载当前角色的当前会话
    LaunchedEffect(Unit) {
        DeviceExecutionStore.restorePending()
        if (currentId.isEmpty()) {
            loadCharacterSpace(currentCharacter)
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size)
        }
    }
    // 切角色:存好当前角色的会话,整体切换到目标角色的会话空间(选择持久化)。
    val switchCharacter = { charId: String ->
        if (charId != currentCharacter) {
            closeDetailForSwitch()
            if (currentId.isNotEmpty()) ChatStore.save(taskState.storageId, messages)
            currentCharacter = charId
            DotRoster.select(charId)
            loadCharacterSpace(charId)
            scrollEnd()
        }
    }
    LaunchedEffect(DotRoster.activeCharacter) {
        switchCharacter(DotRoster.activeCharacter)
    }
    if (showDotScreen) {
        DotRoster.find(currentCharacter)?.let { member ->
            val target = DeviceExecutionStore.delegatedTo(taskState).lastOrNull()?.environment
                ?: dotEnvironment.takeUnless { it == DotEnvironment.PHONE } ?: DotEnvironment.COMPUTER
            DotDeviceSheet(member, target, onDismiss = { showDotScreen = false },
                onChangeRole = { showDotScreen = false; showDotPicker = true },
                onOpenTask = { execution ->
                    showDotScreen = false
                    closeDetailForSwitch()
                    ChatStore.save(taskState.storageId, messages)
                    val destination = DeviceExecutionStore.deliveryState(execution)
                    DotEnvironmentStore.select(destination.environment)
                    switchCharacter(destination.character)
                    SessionStore.setCurrent(destination.sessionId, destination.character)
                    currentId = destination.sessionId
                })
        }
    }
    if (showDotPicker) {
        DotPickerSheet(
            onDismiss = { showDotPicker = false },
            onOpenChat = { switchCharacter(it); showDotPicker = false },
        )
    }
    val switchTo = { id: String ->
        if (id != currentId && id.isNotEmpty()) {
            closeDetailForSwitch()
            ChatStore.save(taskState.storageId, messages)
            SessionStore.setCurrent(id, currentCharacter); currentId = id

            scrollEnd()
        }
    }
    val newChat = {
        // 不直接建会话 —— 先弹工作空间选择对话框(可跳过用全局默认),类似 Codex 启动时选项目目录。
        closeDetailForSwitch()
        showNewChatWorkspaceDialog = true
    }
    // 真正新建会话:workspace 为 null/空时退化为全局默认(行为与改造前一致)
    val createNewChatWithWorkspace = { workspace: String? ->
        closeDetailForSwitch()
        if (currentId.isNotEmpty()) ChatStore.save(taskState.storageId, messages)
        val meta = SessionStore.create(System.currentTimeMillis(), currentCharacter, workspace)
            .copy(title = newChatTitle)
        SessionStore.updateMeta(meta.id, newChatTitle, meta.updatedAt)
        sessions.add(0, meta); currentId = meta.id
    }
    val deleteSession = { id: String ->
        closeDetailForSwitch()
        DeviceExecutionStore.forget(id)
        SessionStore.delete(id)
        GhostChatSessionStore.clear(id)
        sessions.removeAll { it.id == id }
        if (id == currentId) {
            val next = sessions.firstOrNull()?.id
                ?: SessionStore.create(System.currentTimeMillis(), currentCharacter)
                    .also { sessions.add(0, it) }.id
            SessionStore.setCurrent(next, currentCharacter); currentId = next

        }
    }

    // 发送指令：配置了 LLM 则真正驱动 Agent;运行期间显示「思考中」、发送键变停止键
    val send = send@{
        val t = inputText.trim()
        if (visibleTask === taskState && t.isNotEmpty() && !isRunning) {
            if (DotRoster.find(taskState.character) != null &&
                executionEnvironment != DotEnvironment.PHONE &&
                !DotRepository.validBaseUrl(DotEnvironmentStore.address(executionEnvironment))) {
                connectionEnvironment = executionEnvironment
                showEnvironmentConnection = true
                return@send
            }
            pendingForm = null // 发消息时清除待填表单
            followLatest = true; lastScrollTs = 0L
            messages.add(ChatMessage.UserMessage(t))
            inputText = ""
            scrollEnd(); persist()
            val ghostPersona = GhostChatSessionStore.load(taskState.sessionId)
            val dot = DotRoster.find(taskState.character)
            if (dot != null && executionEnvironment != DotEnvironment.PHONE) {
                val history = messages.mapNotNull {
                    when (it) {
                        is ChatMessage.UserMessage -> if (it.includeInHistory()) GhostChatMessage("user", it.text) else null
                        is ChatMessage.AgentMessage -> GhostChatMessage("assistant", it.text)
                        else -> null
                    }
                }
                if (delegatedEnvironment != null) {
                    // Separate remote workspace for each child job; context comes from this conversation.
                    val delegatedSession = "${taskState.sessionId}_delegation_${java.util.UUID.randomUUID()}"
                    DeviceExecutionStore.delegate(taskState, executionEnvironment,
                        DotRepository.taskBody(dot, delegatedSession, history, executionEnvironment))
                    delegatedEnvironment = null
                } else {
                    messages.add(ChatMessage.Thinking(thinkingText))
                    DeviceExecutionStore.start(taskState, executionEnvironment,
                        DotRepository.taskBody(dot, taskState.sessionId, history, executionEnvironment))
                }
            } else if (ghostPersona != null) {
                val generation = ++dotRequestGeneration
                val history = messages.mapNotNull {
                    when (it) {
                        is ChatMessage.UserMessage -> if (it.includeInHistory()) GhostChatMessage("user", it.text) else null
                        is ChatMessage.AgentMessage -> GhostChatMessage("assistant", it.text)
                        else -> null
                    }
                }
                isRunning = true
                val thinking = ChatMessage.Thinking(thinkingText)
                messages.add(thinking)
                ghostChatJob = DeviceExecutionStore.scope.launch {
                    try {
                        val reply = UniverseRepository.chatWithGhost(ghostPersona, t, history.dropLast(1))
                        if (generation == dotRequestGeneration) messages.add(ChatMessage.AgentMessage(reply))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (generation == dotRequestGeneration) messages.add(ChatMessage.AgentMessage(dotSendFailed))
                    } finally {
                        if (generation == dotRequestGeneration) {
                            messages.remove(thinking); isRunning = false; ghostChatJob = null; persist()
                        }
                    }
                }
            } else if (ChatAgentBridge.isConfigured()) {
                taskState.localAgent = true
                isRunning = true
                val sendingSession = taskState.sessionId
                val requestGeneration = ++dotRequestGeneration
                val acceptsCallback = {
                    isRunning && requestGeneration == dotRequestGeneration
                }
                val thinking = ChatMessage.Thinking(thinkingText)
                val showThinking = { if (!messages.contains(thinking)) { messages.add(thinking); scrollEnd() } }
                val hideThinking = { messages.remove(thinking) }
                // 流式:把 token 累积进同一个气泡(live bubble),实时更新
                var streamId: Long? = null
                var buf = StringBuilder()
                var lastStreamUiTs = 0L
                val flushStream = {
                    val id = streamId
                    if (id != null) {
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(buf.toString(), id, streaming = true)
                    }
                    Unit
                }
                val appendStream = { tok: String ->
                    hideThinking()
                    val id = streamId
                    if (id == null) {
                        buf = StringBuilder(tok)
                        val m = ChatMessage.AgentMessage(buf.toString(), streaming = true)
                        streamId = m.id
                        messages.add(m)
                        lastStreamUiTs = System.currentTimeMillis()
                        scrollEnd()
                    } else {
                        buf.append(tok)
                        val now = System.currentTimeMillis()
                        if (now - lastStreamUiTs >= CHAT_STREAM_UI_THROTTLE_MS) {
                            flushStream()
                            lastStreamUiTs = now
                            scrollEnd()
                        }
                    }
                }
                // 结束当前流式气泡(final!=null 时用最终文本覆盖;否则定格已流式内容)
                val finalizeStream = { final: String? ->
                    val id = streamId
                    if (id != null && final != null) {
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(final, id)
                    } else if (id != null) {
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(buf.toString(), id)
                    } else if (final != null) {
                        messages.add(ChatMessage.AgentMessage(final))
                    }
                    streamId = null; buf = StringBuilder()
                }
                val updateTool: (com.apk.claw.android.agent.ToolExecutionProgress) -> Unit = { progress ->
                    if (acceptsCallback()) {
                        if (progress.phase == com.apk.claw.android.agent.ToolExecutionProgress.Phase.STARTED) {
                            finalizeStream(null); hideThinking()
                        }
                        updateToolCall(messages, progress)
                        if (progress.phase == com.apk.claw.android.agent.ToolExecutionProgress.Phase.FINISHED) {
                            if (messages.filterIsInstance<ChatMessage.ToolCall>().none {
                                it.status == ChatMessage.ToolCallStatus.RUNNING
                            }) showThinking()
                            persist()
                        }
                        scrollEnd()
                    }
                }
                // ── 互聊模式分支：多 Persona 轮流发言 ──
                // 花名册非空时走 MultiPersonaOrchestrator；为空则走下面的单 Agent 路径（保持原行为）。
                val multiRosterIds = if (dot != null) emptyList() else PersonaStore.MultiMode.getRoster()
                if (multiRosterIds.isNotEmpty()) {
                    showThinking()
                    val priorTurns = messages.dropLast(1).mapNotNull {
                        when (it) {
                            is ChatMessage.UserMessage -> if (it.includeInHistory()) true to it.text else null
                            is ChatMessage.AgentMessage -> false to it.text
                            else -> null
                        }
                    }
                    com.apk.claw.android.octopus_mobile.persona.MultiPersonaOrchestrator.run(
                        topic = t,
                        onToolProgress = updateTool,
                        isActive = acceptsCallback,
                        priorTurns = priorTurns,
                        onTurnStart = { persona ->
                            if (!acceptsCallback()) return@run
                            // 切到下一个 Persona：重置流式缓冲，重新显示思考气泡
                            finalizeStream(null)
                            hideThinking()
                            val turnThinking = ChatMessage.Thinking("${persona.avatar} ${persona.name} 正在思考…")
                            if (!messages.contains(turnThinking)) { messages.add(turnThinking); scrollEnd() }
                        },
                        onTurnDone = { persona, reply ->
                            if (!acceptsCallback()) return@run
                            // 每个 Persona 的回复作为独立 AgentMessage 落盘，前缀带 avatar+name 以便区分
                            val tagged = "**${persona.avatar} ${persona.name}**\n\n$reply"
                            finalizeStream(tagged)
                            scrollEnd(); persist()
                        },
                        onAllDone = {
                            if (!acceptsCallback()) return@run
                            hideThinking()
                            interruptToolCalls(messages, "执行已中断")
                            isRunning = false
                            scrollEnd(); persist()
                        },
                        onTool = { icon, name, args, res ->
                            if (!acceptsCallback()) return@run
                            com.apk.claw.android.agent.AgentProgressBus.set(null)
                            finalizeStream(null); hideThinking()
                            messages.add(ChatMessage.ToolCall(icon, name, args, res))
                            showThinking(); persist()
                        },
                        onText = { txt -> if (acceptsCallback()) appendStream(txt) },
                        onError = { e ->
                            if (!acceptsCallback()) return@run
                            com.apk.claw.android.agent.AgentProgressBus.set(null)
                            hideThinking(); finalizeStream(null)
                            messages.add(ChatMessage.AgentMessage("⚠️ $e"))
                            interruptToolCalls(messages, "执行已中断")
                        isRunning = false; scrollEnd(); persist()
                        },
                    )
                } else {
                showThinking()
                // 多轮上下文:带上最近几轮 user/agent 消息摘要(dropLast 排除刚 add 的本条),
                // 让「换成蓝牙的」这类指代能接上文。工具卡片/思考气泡不算轮次。
                val convContext = com.apk.claw.android.agent.ConversationContext.build(
                    messages.dropLast(1).mapNotNull {
                        when (it) {
                            is ChatMessage.UserMessage -> if (it.includeInHistory()) true to it.text else null
                            is ChatMessage.AgentMessage -> false to it.text
                            else -> null
                        }
                    },
                )
                ChatAgentBridge.run(
                    prompt = t,
                    onToolProgress = updateTool,
                    conversationContext = convContext,
                    // 非默认角色注入人设:该角色第一人称应答;Octopus 本体不扮演(persona=null)。
                    persona = dot?.let { DotRoster.phonePersona(it) } ?: CharacterRegistry.all
                        .firstOrNull { it.id == taskState.character }?.personaPrompt(),
                    // 注入当前会话的工作空间(类似 Codex --cd 选定项目目录)。
                    // SessionMeta.workspace 非空时覆盖全局默认;为 null 时 effectiveWorkspace() 回退全局。
                    workspace = SessionStore.metaOf(taskState.sessionId)?.effectiveWorkspace(),
                    onTool = { icon, name, args, res ->
                        if (!acceptsCallback()) return@run
                        com.apk.claw.android.agent.AgentProgressBus.set(null)
                        finalizeStream(null); hideThinking()
                        messages.add(ChatMessage.ToolCall(icon, name, args, res))
                        showThinking(); persist()
                    },
                    onText = { txt -> if (acceptsCallback()) appendStream(txt) },
                    onDone = { ans ->
                        if (!acceptsCallback()) return@run
                        com.apk.claw.android.agent.AgentProgressBus.set(null)
                        hideThinking(); finalizeStream(ans)
                        interruptToolCalls(messages, "执行已中断")
                        isRunning = false; scrollEnd(); persist()
                    },
                    onError = { e ->
                        if (!acceptsCallback()) return@run
                        com.apk.claw.android.agent.AgentProgressBus.set(null)
                        hideThinking(); finalizeStream(null)
                        messages.add(ChatMessage.AgentMessage("⚠️ $e"))
                        interruptToolCalls(messages, "执行已中断")
                        isRunning = false; scrollEnd(); persist()
                    },
                    // ── 产物回调(类 Claude Artifacts)──
                    // HTML 产物:preview_html / generate_app 产生的 HTML 字符串。
                    // payload 格式 "$height\n$html"(PreviewHtmlTool 注入),解析时按首个 \n 分割。
                    onHtml = { toolName, htmlPayload ->
                        if (!acceptsCallback()) return@run
                        val refId = "${sendingSession}_${System.currentTimeMillis()}_html"
                        // payload 格式 "$height\n$html",剥掉首行高度信息取实际 HTML
                        val actualHtml = htmlPayload.substringAfter('\n', htmlPayload)
                        ChatStore.savePayload(refId, actualHtml)
                        val title = if (toolName == "generate_app") "生成的小应用"
                            else "HTML 预览"
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.HTML, title, refId,
                        ))
                        scrollEnd(); persist()
                    },
                    // 图片产物:take_screenshot 等产生的 JPEG base64
                    onImage = { toolName, imageBase64 ->
                        if (!acceptsCallback()) return@run
                        val refId = "${sendingSession}_${System.currentTimeMillis()}_img"
                        ChatStore.savePayload(refId, imageBase64)
                        val title = if (toolName == "take_screenshot") "截图" else "图片"
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.IMAGE, title, refId,
                        ))
                        scrollEnd(); persist()
                    },
                    // 文件产物:run_code writeFile / file_ops write / generate_app 等产生的文件路径
                    onFile = { toolName, filePath ->
                        if (!acceptsCallback()) return@run
                        val title = when (toolName) {
                            "generate_app" -> "生成的小应用"
                            "file_ops" -> "写入的文件"
                            "run_code", "run_python" -> "脚本产出文件"
                            else -> "产出的文件"
                        }
                        // payloadRef 直接存文件路径(无需独立 payload 键,路径本身很短)
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.FILE, title, filePath,
                        ))
                        scrollEnd(); persist()
                    },
                    // diff 产物:edit_file 等产生的 unified diff
                    onDiff = { toolName, diff ->
                        if (!acceptsCallback()) return@run
                        val refId = "${sendingSession}_${System.currentTimeMillis()}_diff"
                        ChatStore.savePayload(refId, diff)
                        val title = if (toolName == "edit_file") "代码改动" else "diff"
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.DIFF, title, refId,
                        ))
                        scrollEnd(); persist()
                    },
                    // 结构化问卷表单：工具需要用户点选选项时下发，显示在输入框上方
                    onForm = { toolName, formJson ->
                        if (!acceptsCallback()) return@run
                        val questions = runCatching {
                            val arr = org.json.JSONArray(formJson)
                            (0 until arr.length()).map { i ->
                                val obj = arr.getJSONObject(i)
                                val optsArr = obj.getJSONArray("options")
                                val opts = (0 until optsArr.length()).map { j -> optsArr.getString(j) }
                                ClarifyQuestion(obj.getString("q"), opts)
                            }
                        }.getOrDefault(emptyList())
                        if (questions.isNotEmpty()) {
                            pendingForm = PendingClarifyForm(
                                toolName = toolName,
                                questions = questions,
                                selections = MutableList(questions.size) { -1 },
                            )
                            scrollEnd()
                        }
                    },
                    // ── search_code 代码片段产物(spec refine-chat-interaction Task 5)──
                    // search_code 结果 data 已改为结构化 JSON(含 file/startLine/endLine/snippet),
                    // 调用 handleSearchCodeResult 解析 JSON → 存 snippet 到 ChatStore → 生成 CODE_SNIPPET Artifact。
                    // 卡片在消息流中折叠展示(CodeSnippetPreview 占位已在 Task 1 实现),
                    // 标题「file.kt:42-58」,详情走右侧栏 CodeDetailPane。
                    onCodeSnippet = { toolName, result ->
                        if (!acceptsCallback()) return@run
                        val artifact = com.apk.claw.android.tool.impl.handleSearchCodeResult(result, sendingSession)
                        if (artifact != null) {
                            messages.add(artifact)
                            scrollEnd(); persist()
                        }
                    },
                    // ── run_code 输出产物(spec refine-chat-interaction Task 8)──
                    // run_code 的 stdout 经 RunCodeTool 包装为 ToolResult.textBody,
                    // 这里生成 TEXT 类 Artifact:body 存到 ChatStore,卡片在消息流中折叠展示,
                    // 右侧栏 TextDetailPane 显示全文。stdout 超 500 字符时保持折叠(默认 true),
                    // 否则展开(body 短,直接看预览更顺手)。
                    onTextBody = { toolName, body ->
                        if (!acceptsCallback()) return@run
                        val refId = "${sendingSession}_${System.currentTimeMillis()}_text"
                        ChatStore.savePayload(refId, body)
                        val title = if (toolName == "run_code") "运行输出" else "文本输出"
                        // 超 500 字符保持折叠(默认 true);短输出直接展开,预览更直观
                        val collapsed = body.length > 500
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.TEXT, title, refId, collapsed = collapsed,
                        ))
                        scrollEnd(); persist()
                    },
                    // ── Plan 模式产物(spec refine-chat-interaction Task 7)──
                    // LLM 在 PLAN 模式下输出步骤数组后调 exit_plan_mode,经用户确认切换回 DEFAULT,
                    // DefaultAgentService 从 LLM 文本提取 plan JSON 经 ToolResult.planJson 回传,
                    // 这里生成 PLAN 类 Artifact:plan JSON 存到 ChatStore,卡片在消息流中折叠展示,
                    // 标题「计划(N 步)」,详情走右侧栏 PlanDetailPane(PlanPreview 占位已在 Task 1 实现)。
                    onPlan = { toolName, planJson ->
                        if (!acceptsCallback()) return@run
                        val refId = "${sendingSession}_${System.currentTimeMillis()}_plan"
                        ChatStore.savePayload(refId, planJson)
                        val stepCount = runCatching { org.json.JSONArray(planJson).length() }.getOrDefault(0)
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.PLAN, "计划($stepCount 步)", refId,
                        ))
                        scrollEnd(); persist()
                    },
                    // ── Git 工具结果产物(spec refine-chat-interaction Task 6)──
                    // git_commit / git_push / github_create_pr 成功后,DefaultAgentService
                    // 已把结构化 JSON 解析为 title + body(URL 拼在 body 头部),存到 ChatStore,
                    // 这里生成 TEXT 类 Artifact 卡片。body 以 "URL: " 开头时,TextPreview
                    // 会显示「打开」按钮(Intent.ACTION_VIEW),例如 PR 卡片可一键跳转浏览器。
                    onTextArtifact = { toolName, title, refId ->
                        if (!acceptsCallback()) return@run
                        messages.add(ChatMessage.Artifact(
                            ChatMessage.ArtifactKind.TEXT, title, refId,
                        ))
                        scrollEnd(); persist()
                    },
                )
                }  // 关闭单 Agent 分支的 else
            } else {
                messages.add(ChatMessage.AgentMessage(ackText))
                scrollEnd(); persist()
            }
        }
    }
    val closeDrawer = { scope.launch { drawerState.close() }; Unit }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    // 文本默认展开；语音模式仍支持按住说话、松手发送。
    var voiceMode by remember { mutableStateOf(false) }
    var inputActionsOpen by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }       // 实时部分识别结果
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }

    val startListening = {
        partial = ""
        listening = true
        voice.start(
            onPartial = { partial = it },
            onResult = { txt ->
                listening = false; partial = ""
                if (visibleTask === taskState && txt.isNotBlank()) { inputText = txt; send() }
            },
            onError = { err ->
                listening = false; partial = ""
                if (err.isNotEmpty()) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            },
        )
    }
    // 录音授权：未授权时先请求，授权后再「按住说话」
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) context.getString(R.string.chat_mic_permission_granted) else context.getString(R.string.chat_mic_permission_denied),
            Toast.LENGTH_SHORT,
        ).show()
    }
    val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    var renameChatOpen by remember { mutableStateOf(false) }
    var clearChatOpen by remember { mutableStateOf(false) }
    var deleteChatOpen by remember { mutableStateOf(false) }
    var conversationOptionsExpanded by remember { mutableStateOf(false) }
    var showDesktopConversations by remember { mutableStateOf(false) }
    if (showDesktopConversations) DesktopConversationsSheet(dotEnvironment) { showDesktopConversations = false }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = OverlayDimColor,
        drawerContent = {
            EchoNavigationDrawer(
                sessions = sessions, currentId = currentId, isRunning = false,
                onNewChat = { createNewChatWithWorkspace(null); closeDrawer() },
                onSwitch = { switchTo(it); closeDrawer() },
                onClose = closeDrawer,
                onOpenPersonas = { closeDrawer(); charMenuOpen = true },
                onOpenDesktopConversations = { closeDrawer(); showDesktopConversations = true },
            )
        },
    ) {
    Box(modifier = Modifier.fillMaxSize().background(BackgroundColor).tvOverscan()) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 设备实时预览(选了远程设备后出现在上半屏);「进入控制」开横屏全控页
        previewDevice?.let { dev ->
            key(dev.deviceId) {
                DevicePreviewPanel(
                    device = dev,
                    onEnter = { runCatching { com.apk.claw.android.ui.device.RemoteControlActivity.start(context, dev.deviceId) } },
                    onClose = { previewDevice = null },
                )
            }
        }

        // 设置指引:未配置完成时显示,配齐后自动隐藏
        if ((DotRoster.find(currentCharacter) == null || dotEnvironment == DotEnvironment.PHONE) && (!llmOk || !a11yOk)) {
            SetupGuideCard(
                llmOk = llmOk,
                a11yOk = a11yOk,
                onConfigLlm = { runCatching { context.startActivity(android.content.Intent(context, LlmConfigActivity::class.java)) } },
                onEnableA11y = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } },
            )
        }

        // 消息列表 / 空状态
        if (messages.none { it !is ChatMessage.Thinking }) {
            ChatHomeWorkbench(
                dot = DotRoster.find(currentCharacter),
                llmOk = llmOk,
                target = ControlTarget.label(),
                onUsePrompt = { suggestion ->
                    inputText = suggestion
                    voiceMode = false
                },
            )
        } else {
            Box(modifier = Modifier.weight(1f)) {
                val rows by remember { derivedStateOf { buildChatRows(messages) } }
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                    ) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is ChatRow.Single -> when (val msg = row.msg) {
                                    is ChatMessage.UserMessage -> UserBubble(msg,
                                        retryEnabled = !isRunning &&
                                            DeviceExecutionStore.delegatedTo(taskState).isEmpty(),
                                        onRetry = {
                                            if (DeviceExecutionStore.retry(taskState, msg)) {
                                                followLatest = true; lastScrollTs = 0L; scrollEnd()
                                            }
                                        })
                                    is ChatMessage.AgentMessage -> AgentBubble(msg.text, msg.streaming, pauseChatFollowing)
                                    is ChatMessage.ToolCall -> ToolCallItem(msg) { openToolDetail(listOf(msg)) }
                                    is ChatMessage.Thinking -> ThinkingItem(msg.text)
                                    is ChatMessage.Artifact -> ArtifactCard(
                                        artifact = msg,
                                        onClick = {
                                            // refine-chat-interaction Task 4:根据 artifact.kind 创建对应 DetailPane。
                                            // 关闭右侧栏详情开关时点击无操作(回退到现状,INV-U3)。
                                            if (KVUtils.isDetailDrawerEnabled()) {
                                                currentDetail = artifactDetailPane(msg) { currentDetail = null }
                                            }
                                        },
                                    )
                                }
                                is ChatRow.ToolGroup -> ToolGroupItem(
                                    tools = row.tools,
                                    expanded = expandedGroups[row.tools.first().id] == true,
                                    onToggle = { id -> expandedGroups[id] = expandedGroups[id] != true },
                                    onShowDetail = openToolDetail,
                                )
                                is ChatRow.ToolBatch -> {
                                    // refine-chat-interaction:折叠并行工具开关。
                                    // 开启时聚合为单张 ToolBatchCard;关闭时每个 ToolCall 独立渲染(回退到现状,INV-U3)。
                                    if (KVUtils.isCollapseParallelTools()) {
                                        ToolBatchCard(
                                            calls = row.calls,
                                            onShowDetail = { openToolDetail(row.calls) },
                                        )
                                    } else {
                                        row.calls.forEach { call ->
                                            ToolCallItem(call) { openToolDetail(listOf(call)) }
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(OctopusSpacing.sm)) }
                    }
                }
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = OctopusSpacing.sm)) {
                    ScrollToBottomButton(visible = showScrollToBottom && !followLatest, onClick = {
                        followLatest = true; lastScrollTs = 0L; scrollEnd()
                    })
                }
            }
        }

        // ── 澄清问卷（选择题卡片）：待填写时显示在输入框上方，用户点选选项后填入输入框 ──
        pendingForm?.let { form ->
            ClarifyFormCard(
                form = form,
                onSelect = { qIdx, optIdx ->
                    pendingForm = form.copy(
                        selections = form.selections.toMutableList().apply { this[qIdx] = optIdx }
                    )
                },
                onConfirm = {
                    val answers = form.questions.mapIndexed { i, q ->
                        val sel = form.selections[i]
                        if (sel >= 0) "${q.q}${q.options[sel]}" else null
                    }.filterNotNull()
                    val filled = if (answers.isNotEmpty()) answers.joinToString("\n") + "\n" else ""
                    inputText = filled
                    pendingForm = null
                },
                onCancel = { pendingForm = null },
            )
        }

        taskState.connectionIssue.value?.let { issue ->
            Text(issue, color = TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        // Suggestions stay advisory; only an explicit tap changes the execution lane.
        val deviceSuggestion = remember(inputText, currentCharacter) {
            if (DotRoster.find(currentCharacter) != null) DeviceTaskSuggestion.suggest(inputText) else null
        }
        var dismissedSuggestion by remember(taskState.storageId, inputText) { mutableStateOf(false) }
        if (!isRunning && !dismissedSuggestion && deviceSuggestion != null &&
            deviceSuggestion.environment != executionEnvironment) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable {
                    if (deviceSuggestion.environment != DotEnvironment.PHONE) {
                        delegatedEnvironment = deviceSuggestion.environment
                    } else {
                        android.widget.Toast.makeText(context, context.getString(R.string.dot_delegate_phone_hint),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(deviceSuggestion.environment.icon, null, tint = PrimaryColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dot_env_suggestion,
                        stringResource(deviceSuggestion.environment.tabLabel), stringResource(deviceSuggestion.reason)),
                        color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { dismissedSuggestion = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.common_close),
                        tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        delegatedEnvironment?.let { target ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(target.icon, null, tint = PrimaryColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dot_delegate_next, stringResource(target.tabLabel)),
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { delegatedEnvironment = null }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        val delegatedTasks = DeviceExecutionStore.delegatedTo(taskState)
        if (delegatedTasks.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)) {
                delegatedTasks.forEach { execution ->
                    Surface(shape = RoundedCornerShape(16.dp), color = EchoChatStyle.surface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(execution.environment.icon, null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                val status = when (execution.taskStatus.value) {
                                    "running" -> R.string.dot_delegate_running
                                    "queued" -> R.string.dot_delegate_queued
                                    "cancelling" -> R.string.dot_delegate_stopping
                                    else -> R.string.dot_delegate_connecting
                                }
                                Text(stringResource(R.string.dot_delegate_status, stringResource(execution.environment.tabLabel),
                                    stringResource(status)), fontSize = 13.sp, color = TextPrimary)
                                val taskPrompt = execution.pending?.getAsJsonObject("request")?.getAsJsonArray("messages")
                                    ?.lastOrNull()?.asJsonObject?.get("content")?.asString.orEmpty()
                                RemoteTaskProgress(execution.progress.value)
                                Text(execution.connectionIssue.value ?: taskPrompt,
                                    fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { DeviceExecutionStore.cancel(execution) },
                                enabled = execution.taskStatus.value != "cancelling") {
                                Icon(Icons.Filled.Stop, stringResource(R.string.dot_delegate_stop),
                                    tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        // One clean composer above the Dock. A multiline field keeps the writing area wide.
        Surface(
            modifier = Modifier.fillMaxWidth().imePadding()
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 2.dp),
            shape = RoundedCornerShape(28.dp),
            color = EchoChatStyle.surface,
            border = BorderStroke(1.dp, EchoChatStyle.border.copy(alpha = .65f)),
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (voiceMode) {
                    IconButton(onClick = { voiceMode = false }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.chat_text_mode), tint = TextMuted)
                    }
                    val listeningText = stringResource(R.string.chat_voice_listening_release)
                    val pillText = when {
                        isRunning -> stringResource(R.string.chat_voice_stop_agent)
                        listening -> partial.ifBlank { listeningText }
                        else -> stringResource(R.string.chat_voice_hold_to_speak)
                    }
                    val pillGesture = if (isRunning) Modifier.clickable(onClick = stop) else {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (hasMic()) {
                                    startListening()
                                    tryAwaitRelease()
                                    voice.stop()
                                } else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            })
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (isRunning || listening) ErrorColor else EchoChatStyle.accentSurface)
                            .semantics { contentDescription = pillText }
                            .then(pillGesture),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isRunning) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = null,
                                tint = if (isRunning || listening) Color.White else PrimaryColor,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(pillText, color = if (isRunning || listening) Color.White else PrimaryColor,
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Box {
                        IconButton(onClick = { inputActionsOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_input_actions), tint = TextMuted)
                        }
                        DropdownMenu(expanded = inputActionsOpen, onDismissRequest = { inputActionsOpen = false }) {
                            if (DotRoster.find(currentCharacter) != null) {
                                listOf(DotEnvironment.COMPUTER, DotEnvironment.HOME).forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dot_delegate_menu, stringResource(target.tabLabel))) },
                                        leadingIcon = { Icon(target.icon, null) },
                                        enabled = !isRunning,
                                        onClick = { inputActionsOpen = false; delegatedEnvironment = target },
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_call_entry)) },
                                leadingIcon = { Icon(Icons.Filled.Call, contentDescription = null) },
                                onClick = {
                                    inputActionsOpen = false
                                    runCatching { context.startActivity(Intent(context, VoiceCallActivity::class.java)) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_voice_mode)) },
                                leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                                onClick = { inputActionsOpen = false; voiceMode = true },
                            )
                        }
                    }
                    val composerDot = DotRoster.find(currentCharacter)
                    val inputHint = if (composerDot != null) stringResource(R.string.dot_env_input_hint, stringResource(executionEnvironment.tabLabel)) else stringResource(R.string.chat_input_hint)
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = inputHint },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp, lineHeight = 23.sp),
                        cursorBrush = SolidColor(PrimaryColor),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        decorationBox = { innerTextField ->
                            Box(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                                if (inputText.isEmpty()) Text(inputHint, color = TextMuted, fontSize = 16.sp)
                                innerTextField()
                            }
                        },
                    )
                    val showSend = inputText.isNotBlank() || isRunning
                    IconButton(
                        onClick = { if (isRunning) stop() else if (showSend) send() else voiceMode = true },
                        modifier = Modifier.size(48.dp).padding(3.dp)
                            .background(if (isRunning) ErrorColor else if (showSend) PrimaryColor else Color.Transparent, CircleShape),
                    ) {
                        Icon(
                            if (isRunning) Icons.Filled.Stop else if (showSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                            contentDescription = if (composerDot != null && showSend && !isRunning)
                                stringResource(R.string.dot_env_send_to, stringResource(executionEnvironment.title))
                            else stringResource(if (isRunning) R.string.chat_voice_stop_agent else if (showSend) R.string.chat_send_message else R.string.chat_voice_mode),
                            tint = if (isRunning) Color.White else if (showSend) {
                                if (OctopusColors.isLight) Color.White else EchoChatStyle.background
                            } else TextMuted,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
        }
    }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.chat_sessions), tint = TextSecondary)
            }
            Row(
                modifier = Modifier.align(Alignment.TopCenter)
                    .widthIn(max = (LocalConfiguration.current.screenWidthDp - 128).coerceAtLeast(128).dp)
                    .background(EchoChatStyle.surface, RoundedCornerShape(40.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val curProfile = CharacterRegistry.all.firstOrNull { it.id == currentCharacter }
                val currentDot = DotRoster.find(currentCharacter)
                val projectPath = sessions.firstOrNull { it.id == currentId }?.workspace
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val projectName = projectPath?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() } ?: projectPath
                Box {
                    Image(
                        painterResource(currentDot?.avatar ?: curProfile?.avatarRes ?: R.drawable.ic_echo_foreground),
                        contentDescription = currentDot?.name ?: curProfile?.zh ?: stringResource(R.string.app_name),
                        modifier = Modifier.size(56.dp).clip(CircleShape)
                            .combinedClickable(
                                onClick = { if (currentDot != null) showDotScreen = true else showDotPicker = true },
                                onLongClick = { showDotPicker = true },
                                onClickLabel = stringResource(if (currentDot != null) R.string.dot_devices else R.string.dot_screen_switch_role),
                                onLongClickLabel = stringResource(R.string.dot_screen_switch_role),
                            ).tvFocusable(),
                    )
                    DropdownMenu(expanded = charMenuOpen, onDismissRequest = { charMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("${stringResource(R.string.app_name)} · 默认助手") },
                            onClick = { charMenuOpen = false; switchCharacter(SessionStore.CHARACTER_DEFAULT) },
                        )
                        CharacterRegistry.all.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.zh} · ${c.name}") },
                                leadingIcon = {
                                    Image(
                                        painterResource(c.avatarRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(OctopusIconSize.medium).clip(CircleShape),
                                    )
                                },
                                onClick = { charMenuOpen = false; switchCharacter(c.id) },
                            )
                        }
                        // ── 分隔：上面是 character 空间（会话隔离），下面是 Persona 人设（systemPrompt 注入） ──
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        val activePersona = remember(refreshTick) { PersonaStore.getActivePersona(context) }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (activePersona != null) "人设：${activePersona.name}" else "管理 Persona 人设",
                                        fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (activePersona != null) "${activePersona.avatar} ${activePersona.styleHint.take(20)}"
                                        else "切换/自定义 AI 性格与语气",
                                        fontSize = OctopusType.tag, color = TextMuted,
                                    )
                                }
                            },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted) },
                            onClick = {
                                charMenuOpen = false
                                context.startActivity(Intent(context, PersonasActivity::class.java))
                            },
                        )
                        // 互聊模式：多个 Persona 轮流发言。状态由 PersonaStore.MultiMode 持久化。
                        val multiRoster = remember(refreshTick) { PersonaStore.MultiMode.getRoster() }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        if (multiRoster.isEmpty()) "互聊模式：关闭"
                                        else "互聊模式：${multiRoster.size} 人",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        if (multiRoster.isEmpty()) "选 2-4 个 Persona 轮流发言"
                                        else "点击管理花名册 / 关闭",
                                        fontSize = OctopusType.tag, color = TextMuted,
                                    )
                                }
                            },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted) },
                            onClick = {
                                charMenuOpen = false
                                multiRosterDialogOpen = true
                            },
                        )
                    }
                }
                if (currentDot != null) {
                    DotEnvironmentSelector(dotEnvironment, true, switchEnvironment,
                        runningEnvironments = DeviceExecutionStore.runningEnvironments(),
                        onConfigure = { showEnvironmentConnection = true })
                }
                if (projectName != null) {
                    Row(
                        modifier = Modifier.widthIn(max = 100.dp).heightIn(min = 48.dp)
                            .clickable(enabled = !isRunning) { showChangeWorkspaceDialog = true }
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(projectName, color = TextPrimary, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Filled.ExpandMore, stringResource(R.string.chat_workspace_change),
                            tint = TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
                if (com.apk.claw.android.octopus_mobile.DemoRecorder.isRecording()) {
                    Box(Modifier.padding(end = 4.dp).size(6.dp).background(ErrorColor, CircleShape))
                }
            }

            Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
            // 目标选择器:决定 Agent 在「本机」还是某台局域网设备上执行(移到右上,与三点并排)
            if (DotRoster.find(currentCharacter) == null) TargetSelector(onPreview = { previewDevice = it })
            Spacer(modifier = Modifier.width(OctopusSpacing.xs))
            Box {
                IconButton(onClick = { conversationOptionsExpanded = false; moreMenuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more), tint = TextPrimary)
                }

            }
        }

        }

    }
    // 新建对话工作空间选择对话框(类似 Codex 启动时选项目目录)
    if (moreMenuOpen) {
        ModalBottomSheet(onDismissRequest = { moreMenuOpen = false; conversationOptionsExpanded = false },
            containerColor = BackgroundColor) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(sessions.firstOrNull { it.id == currentId }?.title ?: newChatTitle,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                val activeTasks = DeviceExecutionStore.active()
                if (activeTasks.isNotEmpty()) EchoDrawerLink(Icons.Filled.Schedule,
                    stringResource(R.string.echo_background_tasks, activeTasks.size)) {
                    moreMenuOpen = false; showBackgroundTasks = true
                }
                EchoDrawerLink(Icons.Filled.Edit, stringResource(R.string.echo_rename_chat)) { moreMenuOpen = false; renameChatOpen = true }
                val pinned = sessions.firstOrNull { it.id == currentId }?.pinned == true
                EchoDrawerLink(Icons.Filled.PushPin, stringResource(if (pinned) R.string.echo_unpin else R.string.echo_pin)) {
                    SessionStore.setPinned(currentId, !pinned)
                    val at = sessions.indexOfFirst { it.id == currentId }
                    if (at >= 0) sessions[at] = sessions[at].copy(pinned = !pinned)
                    moreMenuOpen = false
                }
                EchoDrawerLink(Icons.Filled.Storage, stringResource(R.string.chat_workspace_change), enabled = !isRunning) {
                    moreMenuOpen = false; showChangeWorkspaceDialog = true
                }
                if (DotRoster.find(currentCharacter) != null) {
                    EchoDrawerLink(Icons.Filled.SettingsRemote, stringResource(R.string.dot_env_configure), enabled = !isRunning) {
                        if (!isRunning) { moreMenuOpen = false; showEnvironmentConnection = true }
                    }
                }
                EchoDrawerLink(if (conversationOptionsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    stringResource(R.string.echo_conversation_tools)) { conversationOptionsExpanded = !conversationOptionsExpanded }
                if (conversationOptionsExpanded) {
                    val agentRecording = AgentActionRecorder.isRecording
                    EchoDrawerLink(Icons.Filled.FiberManualRecord,
                        stringResource(if (agentRecording) R.string.browser_menu_stop_recording else R.string.browser_menu_record_agent)) {
                            moreMenuOpen = false
                            if (agentRecording) {
                                val routine = AgentActionRecorder.stopRecording()
                                val msg = if (routine != null) {
                                    context.getString(R.string.agent_record_saved, routine.name)
                                } else {
                                    context.getString(R.string.agent_record_empty)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                AgentActionRecorder.startRecording("Agent 对话操作")
                                Toast.makeText(context, R.string.agent_record_started, Toast.LENGTH_SHORT).show()
                            }
                    }

                    EchoDrawerLink(Icons.Filled.FiberManualRecord, stringResource(R.string.chat_demo_record)) {
                        moreMenuOpen = false; com.apk.claw.android.octopus_mobile.DemoRecorder.toggle()
                    }
                    EchoDrawerLink(Icons.Filled.DeleteSweep, stringResource(R.string.chat_clear_current), enabled = !isRunning) {
                        if (!isRunning) { moreMenuOpen = false; clearChatOpen = true }
                    }
                    EchoDrawerLink(Icons.Filled.DeleteSweep, stringResource(R.string.echo_delete_chat), enabled = !DeviceExecutionStore.hasRunning(currentId)) {
                        if (!isRunning) { moreMenuOpen = false; deleteChatOpen = true }
                    }
                }
            }
        }
    }
    if (showBackgroundTasks) {
        ModalBottomSheet(onDismissRequest = { showBackgroundTasks = false }, containerColor = BackgroundColor) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.echo_background_tasks, DeviceExecutionStore.active().size),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                DeviceExecutionStore.active().forEach { execution ->
                    val title = SessionStore.metaOf(execution.sessionId)?.title ?: newChatTitle
                    EchoDrawerLink(execution.environment.icon, "${stringResource(execution.environment.title)} · $title") {
                        showBackgroundTasks = false
                        val destination = DeviceExecutionStore.deliveryState(execution)
                        DotEnvironmentStore.select(destination.environment)
                        switchCharacter(destination.character)
                        SessionStore.setCurrent(destination.sessionId, destination.character)
                        currentId = destination.sessionId
                    }
                }
            }
        }
    }
    if (renameChatOpen) {
        var title by remember(currentId) { mutableStateOf(sessions.firstOrNull { it.id == currentId }?.title.orEmpty()) }
        AlertDialog(onDismissRequest = { renameChatOpen = false }, title = { Text(stringResource(R.string.echo_rename_chat)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                SessionStore.rename(currentId, title)
                val at = sessions.indexOfFirst { it.id == currentId }
                if (at >= 0) sessions[at] = sessions[at].copy(title = title.trim(), manuallyNamed = true)
                renameChatOpen = false
            }) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = { renameChatOpen = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (clearChatOpen || deleteChatOpen) {
        AlertDialog(onDismissRequest = { clearChatOpen = false; deleteChatOpen = false },
            title = { Text(stringResource(if (deleteChatOpen) R.string.echo_delete_chat else R.string.chat_clear_current)) },
            text = { Text(stringResource(R.string.echo_delete_confirm)) },
            confirmButton = { TextButton(enabled = !isRunning && (!deleteChatOpen || !DeviceExecutionStore.hasRunning(currentId)), onClick = {
                if (deleteChatOpen) deleteSession(currentId) else { messages.clear(); ChatStore.clear(taskState.storageId) }
                clearChatOpen = false; deleteChatOpen = false
            }) { Text(stringResource(R.string.common_delete), color = ErrorColor) } },
            dismissButton = { TextButton(onClick = { clearChatOpen = false; deleteChatOpen = false }) { Text(stringResource(R.string.common_cancel)) } })
    }

    if (showNewChatWorkspaceDialog) {
        WorkspacePickerDialog(
            title = stringResource(R.string.chat_workspace_pick_title),
            desc = stringResource(R.string.chat_workspace_pick_desc),
            initial = "",
            hint = stringResource(R.string.settings_workspace_hint),
            onDismiss = { showNewChatWorkspaceDialog = false },
            onConfirm = { path ->
                showNewChatWorkspaceDialog = false
                createNewChatWithWorkspace(path)
            },
        )
    }
    // 当前会话工作空间更改对话框
    if (showChangeWorkspaceDialog) {
        WorkspacePickerDialog(
            title = stringResource(R.string.chat_workspace_change),
            desc = stringResource(R.string.chat_workspace_pick_desc),
            initial = SessionStore.metaOf(currentId)?.workspace ?: "",
            hint = stringResource(R.string.settings_workspace_hint),
            onDismiss = { showChangeWorkspaceDialog = false },
            onConfirm = { path ->
                showChangeWorkspaceDialog = false
                SessionStore.setWorkspace(currentId, path)
                // 同步刷新内存中的 sessions 列表(让抽屉显示立即更新)
                val idx = sessions.indexOfFirst { it.id == currentId }
                if (idx >= 0) {
                    val updated = sessions[idx].copy(workspace = path?.takeIf { p -> p.isNotBlank() })
                    sessions[idx] = updated
                }
            },
        )
    }
    // 互聊花名册管理对话框：勾选 2-4 个 Persona，按勾选顺序发言
    if (multiRosterDialogOpen) {
        MultiRosterPickerDialog(
            onDismiss = {
                multiRosterDialogOpen = false
                refreshTick++  // 触发顶部菜单的 roster 计数刷新
            },
        )
    }
    // refine-chat-interaction Task 3:右侧详情抽屉。
    // currentDetail = null 时 DetailDrawer 直接 return,消息流可独立浏览(INV-U4)。
    // 占位接入:具体 DetailPane 实例化由 ArtifactCard.onClick 触发,Task 4 子代理实现。
    // 关闭右侧栏详情开关时不渲染 DetailDrawer(回退到现状,INV-U3)。
    if (KVUtils.isDetailDrawerEnabled()) {
        DetailDrawer(
            currentPane = currentDetail,
            onClose = { currentDetail = null },
        )
    }
    }
}

/**
 * 互聊花名册选择对话框。直接读写 [PersonaStore.MultiMode] 持久化状态，
 * 关闭时通过 onDismiss 通知调用方刷新依赖 roster 状态的 UI。
 */
@Composable
private fun MultiRosterPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val allPersonas = remember { PersonaStore.list(context) }
    var roster by remember { mutableStateOf(PersonaStore.MultiMode.getRoster()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("互聊花名册") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.xs)) {
                Text(
                    "选 ${PersonaStore.MultiMode.MAX_ROSTER} 以内的 Persona 加入花名册，" +
                        "勾选顺序即发言顺序。空花名册 = 关闭互聊模式。",
                    color = TextMuted,
                    fontSize = OctopusType.caption,
                    lineHeight = 16.sp,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                allPersonas.forEach { p ->
                    val order = roster.indexOf(p.id)  // -1 = 未选
                    val selected = order >= 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newRoster = if (selected) {
                                    roster.filterNot { it == p.id }
                                } else {
                                    if (roster.size >= PersonaStore.MultiMode.MAX_ROSTER) return@clickable  // 满员
                                    roster + p.id
                                }
                                PersonaStore.MultiMode.setRoster(newRoster)
                                roster = newRoster
                            }
                            .padding(vertical = OctopusSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.avatar, fontSize = OctopusType.body)
                        Spacer(Modifier.width(OctopusSpacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = OctopusType.body)
                            if (p.styleHint.isNotEmpty()) {
                                Text(p.styleHint, fontSize = OctopusType.tag, color = TextMuted, maxLines = 1)
                            }
                        }
                        if (selected) {
                            Text(
                                "#${order + 1}",
                                color = TextMuted,
                                fontSize = OctopusType.tag,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(OctopusSpacing.xs))
                            Icon(Icons.Filled.Check, contentDescription = null, tint = SuccessColor,
                                modifier = Modifier.size(OctopusIconSize.small))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = {
                PersonaStore.MultiMode.clear()
                roster = emptyList()
            }) { Text("清空") }
        },
    )
}

/**
 * 工作空间选择对话框 —— 新建对话 / 更改当前对话工作空间共用。
 * 留空点确认 = 使用全局默认(行为与改造前一致);填路径 = per-session 覆盖。
 */
@Composable
private fun WorkspacePickerDialog(
    title: String,
    desc: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var showBrowser by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                Text(desc, color = TextMuted, fontSize = OctopusType.caption, lineHeight = 16.sp)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(hint) },
                    trailingIcon = {
                        TextButton(onClick = { showBrowser = true }) { Text("浏览") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.ifBlank { null }) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (showBrowser) {
        FolderBrowserDialog(
            start = draft,
            onDismiss = { showBrowser = false },
            onPick = { picked -> draft = picked.trimEnd('/') + "/"; showBrowser = false },
        )
    }
}

@Composable
private fun ChatSessionDrawer(
    sessions: List<SessionStore.SessionMeta>,
    currentId: String,
    isRunning: Boolean,
    llmOk: Boolean,
    a11yOk: Boolean,
    deviceCount: Int,
    onNewChat: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    // 不能用 remember(sessions){…}:sessions 是 mutableStateListOf,in-place 增删时引用不变,
    // remember 永不失效 → 抽屉首帧(sessions 尚空)把 grouped 永久缓存成空,历史行永不渲染。
    // 直接在组合期分组:读可观察的 sessions 会订阅其变化,增删即重组重算。
    val grouped = sessions.groupBy { sessionBucket(it.updatedAt, now) }
    ModalDrawerSheet(
        drawerContainerColor = BackgroundColor,
        drawerContentColor = TextPrimary,
        modifier = Modifier.fillMaxHeight().width(304.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.lg)) {
            Text(stringResource(R.string.app_name), color = TextPrimary, fontSize = OctopusType.headlineSm, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(stringResource(R.string.chat_drawer_subtitle), color = TextMuted, fontSize = OctopusType.label)
            Spacer(Modifier.height(OctopusSpacing.lg))
            DrawerStatusPanel(llmOk = llmOk, a11yOk = a11yOk, deviceCount = deviceCount)
            Spacer(Modifier.height(OctopusSpacing.lg))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isRunning, onClick = onNewChat).tvFocusable(),
                shape = OctopusShape.large,
                color = PrimaryColor.copy(alpha = if (isRunning) 0.12f else 0.18f),
                border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.28f)),
            ) {
                Row(modifier = Modifier.padding(OctopusSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.medium))
                    Spacer(Modifier.width(OctopusSpacing.md))
                    Text(stringResource(R.string.chat_new), color = TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(OctopusSpacing.lg))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                listOf(SessionBucket.Today, SessionBucket.Recent, SessionBucket.Earlier).forEach { bucket ->
                    val bucketSessions = grouped[bucket].orEmpty()
                    if (bucketSessions.isNotEmpty()) {
                        item(key = "h_$bucket") { DrawerSectionTitle(bucket.label()) }
                        items(bucketSessions, key = { it.id }) { session ->
                            SessionDrawerRow(
                                session = session,
                                selected = session.id == currentId,
                                onSwitch = onSwitch,
                                onDelete = onDelete,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentHomeStatusCard(
    llmOk: Boolean,
    a11yOk: Boolean,
    isRunning: Boolean,
    target: String,
    deviceCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OctopusShape.xl,
        color = OctopusBackground.cardSurface,
        border = BorderStroke(1.dp, OctopusBackground.cardBorder),
        shadowElevation = OctopusThemeStyle.cardShadow(8.dp),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg), verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.medium))
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(stringResource(R.string.chat_home_status_title), color = TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                StatusDot(if (isRunning) WarningColor else if (llmOk && a11yOk) SuccessColor else WarningColor)
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text(
                    stringResource(
                        when {
                            isRunning -> R.string.chat_status_running
                            llmOk && a11yOk -> R.string.chat_agent_ready
                            else -> R.string.chat_agent_setup_needed
                        }
                    ),
                    color = TextSecondary,
                    fontSize = OctopusType.caption,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                MiniMetric(
                    label = stringResource(R.string.setup_a11y),
                    value = if (a11yOk) stringResource(R.string.status_online) else stringResource(R.string.chat_agent_setup_needed),
                    ok = a11yOk,
                    modifier = Modifier.weight(1f),
                )
                MiniMetric(
                    label = stringResource(R.string.chat_metric_target),
                    value = target,
                    ok = true,
                    modifier = Modifier.weight(1f),
                )
                MiniMetric(
                    label = stringResource(R.string.chat_metric_devices),
                    value = deviceCount.toString(),
                    ok = deviceCount > 0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ChatHomeWorkbench(
    dot: DotMember? = null,
    llmOk: Boolean,
    target: String,
    onUsePrompt: (String) -> Unit,
) {
    val prompts = if (dot != null) listOf(
        HomePrompt(stringResource(R.string.dot_task), stringResource(R.string.dot_prompt_task, dotSpecialty(dot)), Icons.Filled.AutoAwesome, AccentColor),
        HomePrompt(stringResource(R.string.dot_plan), stringResource(R.string.dot_prompt_plan), Icons.Filled.Search, PrimaryColor),
        HomePrompt(stringResource(R.string.dot_intro), stringResource(R.string.dot_prompt_intro), Icons.Filled.Chat, SuccessColor),
    ) else listOf(
        HomePrompt(
            label = stringResource(R.string.chat_prompt_monitor),
            text = stringResource(R.string.chat_suggestion_notifications),
            icon = Icons.Filled.Visibility,
            color = AccentColor,
        ),
        HomePrompt(
            label = stringResource(R.string.chat_prompt_organize),
            text = stringResource(R.string.chat_suggestion_files),
            icon = Icons.Filled.Search,
            color = PrimaryColor,
        ),
        HomePrompt(
            label = stringResource(R.string.chat_prompt_control),
            text = stringResource(R.string.chat_suggestion_app),
            icon = Icons.Filled.TouchApp,
            color = SuccessColor,
        ),
    )
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
    // Position the empty welcome content within the full conversation canvas.
    // This spacing is independent of the floating header's measured height.
    val welcomeInset = (maxHeight * .14f).coerceIn(96.dp, 120.dp)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        contentPadding = PaddingValues(top = welcomeInset, bottom = OctopusSpacing.lg),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (dot != null) stringResource(R.string.dot_home_title, dot.name) else stringResource(R.string.chat_empty_title),
                    color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 28.sp, letterSpacing = (-0.6).sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (dot != null) dotSpecialty(dot) else stringResource(R.string.chat_empty_hint),
                    color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp),
                )
                if (dot == null) Spacer(Modifier.height(16.dp))
                if (dot == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    var tierMenu by remember { mutableStateOf(false) }
                    var tier by remember { mutableStateOf(AccountConfig.modelTier) }
                    val tierLabel = when (tier) {
                        AccountConfig.TIER_FLASH -> stringResource(R.string.account_tier_flash)
                        AccountConfig.TIER_PREMIUM -> stringResource(R.string.account_tier_premium)
                        else -> stringResource(R.string.account_tier_fast)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        HomeTinyStat(
                            label = stringResource(R.string.chat_metric_model), value = tierLabel,
                            ok = llmOk, modifier = Modifier.fillMaxWidth(), onClick = { tierMenu = true },
                        )
                        DropdownMenu(expanded = tierMenu, onDismissRequest = { tierMenu = false }) {
                            listOf(
                                AccountConfig.TIER_FAST to R.string.account_tier_fast,
                                AccountConfig.TIER_FLASH to R.string.account_tier_flash,
                                AccountConfig.TIER_PREMIUM to R.string.account_tier_premium,
                            ).forEach { (t, nameRes) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(nameRes)) },
                                    trailingIcon = { if (t == tier) Icon(Icons.Filled.Check, contentDescription = null, tint = PrimaryColor) },
                                    onClick = { AccountConfig.modelTier = t; tier = t; tierMenu = false },
                                )
                            }
                        }
                    }
                    HomeTinyStat(label = stringResource(R.string.chat_metric_target), value = target,
                        ok = true, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = EchoChatStyle.surface,
                border = BorderStroke(1.dp, EchoChatStyle.border.copy(alpha = .6f)),
            ) {
                Column {
                    prompts.forEachIndexed { index, prompt ->
                        PromptSuggestion(prompt) { onUsePrompt(prompt.text) }
                        if (index < prompts.lastIndex) HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                            color = EchoChatStyle.border.copy(alpha = .6f),
                        )
                    }
                }
            }
        }
    }
}

}

private data class HomePrompt(
    val label: String,
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
)

@Composable
private fun HomeTinyStat(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(18.dp), color = EchoChatStyle.softSurface,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(value, color = if (ok) TextPrimary else TextMuted, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (onClick != null) Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PromptSuggestion(prompt: HomePrompt, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(prompt.icon, contentDescription = null, tint = prompt.color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(prompt.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DrawerStatusPanel(llmOk: Boolean, a11yOk: Boolean, deviceCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OctopusShape.large,
        color = OctopusBackground.cardSurface,
        border = BorderStroke(1.dp, OctopusBackground.cardBorder),
        shadowElevation = OctopusThemeStyle.cardShadow(1.dp),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.md), verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
            DrawerStatusRow(Icons.Filled.PhoneAndroid, stringResource(R.string.setup_a11y), a11yOk)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.small))
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(stringResource(R.string.chat_metric_devices), modifier = Modifier.weight(1f), color = TextSecondary, fontSize = OctopusType.label)
                Text(deviceCount.toString(), color = if (deviceCount > 0) SuccessColor else TextMuted, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DrawerStatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (ok) SuccessColor else TextMuted, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(label, modifier = Modifier.weight(1f), color = TextSecondary, fontSize = OctopusType.label)
        Text(
            stringResource(if (ok) R.string.status_online else R.string.chat_agent_setup_needed),
            color = if (ok) SuccessColor else WarningColor,
            fontSize = OctopusType.caption,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniMetric(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = OctopusShape.large,
        color = SurfaceDeepColor,
        border = BorderStroke(1.dp, if (ok) PrimaryColor.copy(alpha = 0.16f) else BorderColor),
        shadowElevation = OctopusThemeStyle.cardShadow(1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm)) {
            Text(label, color = TextMuted, fontSize = OctopusType.tag, maxLines = 1)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(value, color = if (ok) TextPrimary else WarningColor, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = OctopusType.caption,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = OctopusSpacing.xs, top = OctopusSpacing.sm, bottom = OctopusSpacing.xs),
    )
}

@Composable
private fun SessionDrawerRow(
    session: SessionStore.SessionMeta,
    selected: Boolean,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSwitch(session.id) },
        shape = OctopusShape.large,
        color = if (selected) SurfaceVariantColor else SurfaceColor,
        border = BorderStroke(1.dp, if (selected) PrimaryColor.copy(alpha = 0.26f) else BorderColor),
        shadowElevation = OctopusThemeStyle.cardShadow(1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                tint = if (selected) PrimaryColor else TextMuted,
                modifier = Modifier.size(OctopusIconSize.small),
            )
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(
                session.title,
                modifier = Modifier.weight(1f),
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = OctopusType.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                "×",
                color = TextMuted,
                fontSize = OctopusType.title,
                modifier = Modifier.clickable { onDelete(session.id) }.padding(start = OctopusSpacing.sm),
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
}

private enum class SessionBucket { Today, Recent, Earlier }

private fun sessionBucket(updatedAt: Long, now: Long): SessionBucket {
    val todayStart = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        updatedAt >= todayStart -> SessionBucket.Today
        updatedAt >= todayStart - 6L * 24L * 60L * 60L * 1000L -> SessionBucket.Recent
        else -> SessionBucket.Earlier
    }
}

@Composable
private fun SessionBucket.label(): String = stringResource(
    when (this) {
        SessionBucket.Today -> R.string.chat_sessions_today
        SessionBucket.Recent -> R.string.chat_sessions_recent
        SessionBucket.Earlier -> R.string.chat_sessions_earlier
    }
)

@Composable
private fun TargetSelector(onPreview: (DeviceInfo?) -> Unit = {}) {
    val context = LocalContext.current
    val devices by ClawApplication.instance.deviceRegistry.deviceList.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(ControlTarget.label()) }
    val remote = remember(label) { ControlTarget.isRemote() }
    val loopbackName = stringResource(R.string.chat_loopback_device)  // 提前取,onClick 内不能调 @Composable
    Box {
        // 只一个「手机/电脑」复合图标;点击才弹出设备列表。远程时高亮 + 右上角小圆点提示。
        IconButton(onClick = { menu = true }) {
            BadgedBox(
                badge = { if (remote) Badge(containerColor = PrimaryColor, modifier = Modifier.size(7.dp)) }
            ) {
                Icon(
                    Icons.Filled.Devices,
                    contentDescription = label,
                    tint = if (remote) PrimaryColor else TextSecondary,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_target_local_device)) },
                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(OctopusIconSize.small)) },
                onClick = { ControlTarget.setLocal(); label = ControlTarget.label(); menu = false; onPreview(null) },
            )
            devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.deviceName) },
                    leadingIcon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(OctopusIconSize.small)) },
                    onClick = { ControlTarget.setRemote(d); label = ControlTarget.label(); menu = false; onPreview(d) },
                )
            }
            // 电脑：远端电脑(WebRTC 远程桌面) + 本地虚拟电脑(桌面模式:本机浏览器桌面 + Agent)
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_remote_pc_title)) },
                leadingIcon = { Icon(Icons.Filled.Monitor, contentDescription = null, modifier = Modifier.size(OctopusIconSize.small)) },
                onClick = {
                    menu = false
                    runCatching { context.startActivity(android.content.Intent(context, com.apk.claw.android.ui.featurescreens.PcRemoteWebrtcActivity::class.java)) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_target_virtual_pc)) },
                leadingIcon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null, modifier = Modifier.size(OctopusIconSize.small)) },
                onClick = {
                    menu = false
                    runCatching { context.startActivity(android.content.Intent(context, com.apk.claw.android.ui.desktop.DesktopActivity::class.java)) }
                },
            )
            // 调试：回环目标（远程控制自己，用于单机验证远程路由）
            if (BuildConfig.DEBUG) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_loopback)) },
                    onClick = {
                        val token = runCatching { ConfigServerManager.getAuthToken() }.getOrNull() ?: ""
                        // ConfigServer 绑定在本机 WiFi IP（非 127.0.0.1），用其真实地址回环
                        val addr = runCatching { ConfigServerManager.getAddress() }.getOrNull() ?: "127.0.0.1:9527"
                        val ip = addr.substringBefore(":")
                        val port = addr.substringAfter(":").toIntOrNull() ?: 9527
                        ControlTarget.setRemote(
                            DeviceInfo(
                                deviceId = "loopback",
                                deviceName = loopbackName,
                                ip = ip,
                                configServerPort = port,
                                authToken = token,
                            )
                        )
                        label = ControlTarget.label()
                        menu = false
                    },
                )
            }
        }
    }
}

/**
 * 设备实时预览面板(上半屏)。点设备后出现:MJPEG 直播画面 + 「进入控制」开横屏全控页。
 * 用 key(deviceId) 保证切设备时整块重建,串流随之重启;离开组合时 onRelease 停流。
 */
@Composable
private fun DevicePreviewPanel(device: DeviceInfo, onEnter: () -> Unit, onClose: () -> Unit) {
    // Marvis 风格:紧贴顶栏的一块干净圆角实时画面,无内部标题栏;点画面=进入控制,下方一根拖拽柄。
    val previewH = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.md).padding(top = OctopusSpacing.sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewH)
                .clip(OctopusShape.large)
                .background(VideoBackgroundColor)
                .clickable { onEnter() },
        ) {
            AndroidView(
                factory = { ctx ->
                    MjpegImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.BLACK)
                        val token = URLEncoder.encode(device.authToken, "UTF-8")
                        val q = RemoteStreamPrefs.streamParams()
                        start("${device.getBaseUrl()}/api/screen/stream?$q&token=$token")
                    }
                },
                onRelease = { it.stop() },
                modifier = Modifier.fillMaxSize(),
            )
            // 左上角:设备名小药丸(画面内做上下文,不占独立标题栏)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(OctopusSpacing.sm)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OverlayDimColor)
                    .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SuccessColor))
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text(device.deviceName, color = OnPrimaryColor, fontSize = OctopusType.caption, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            // 右上角:关闭
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(OctopusSpacing.sm)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(OverlayDimColor)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_close), tint = OnPrimaryColor, modifier = Modifier.size(OctopusIconSize.small))
            }
        }
        // 拖拽柄(视觉提示:可点画面进入全控)
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.sm), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(BorderColor))
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun UserBubble(message: ChatMessage.UserMessage, retryEnabled: Boolean = true, onRetry: () -> Unit = {}) {
    val text = message.text
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val maxBubbleWidth = (config.screenWidthDp.dp * 0.78f)
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(horizontalAlignment = Alignment.End) {
          Box {
            Surface(
                shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
                color = UserBubbleColor,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
                    fontSize = OctopusType.bodyStrong,
                    color = EchoChatStyle.onAccentSurface,
                    lineHeight = 23.sp,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_save_as_routine)) },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(OctopusIconSize.small)) },
                    onClick = {
                        menu = false
                        val now = System.currentTimeMillis()
                        RoutineStore.add(
                            RoutineStore.Routine(
                                id = "rt_$now",
                                name = text.take(20),
                                prompt = text,
                                targetId = ControlTarget.id(),
                                targetLabel = ControlTarget.label(),
                                createdAt = now,
                            )
                        )
                        Toast.makeText(context, context.getString(R.string.chat_routine_saved_toast), Toast.LENGTH_SHORT).show()
                    },
                )
            }
          }
          MessageDeliveryLabel(message, retryEnabled, onRetry, Modifier.widthIn(max = maxBubbleWidth))
        }
    }
}

@Composable
internal fun AgentBubble(text: String, streaming: Boolean = false, onReading: () -> Unit = {}) {
    val config = LocalConfiguration.current
    val maxBubbleWidth = (config.screenWidthDp.dp * 0.82f)
    val media = remember(text) { extractChatMedia(text) }
    val displayText = remember(text, media) { stripMediaUrls(text, media.images + media.videos) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp),
            color = AgentBubbleColor,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
            ) {
                StreamingMarkdownText(displayText, streaming, TextPrimary.toArgb(), PrimaryColor.toArgb(), onReading)
                media.images.forEach { url ->
                    coil.compose.AsyncImage(
                        model = url,
                        contentDescription = "生成的图片",
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        modifier = Modifier
                            .padding(top = OctopusSpacing.sm)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                media.videos.forEach { url ->
                    InlineVideo(url)
                }
                AnswerActions(text, streaming, onReading, Modifier.align(Alignment.End))
            }
        }
    }
}

/** 从 agent 文本抽出生成的图片/视频 URL。agent 常给"纯 URL"而非 ![](url),Markwon 渲不了图,
 *  故图片也按裸 URL 检测后用 AsyncImage 内嵌(只认 agnes 输出域,避免误渲普通链接)。 */
private data class ChatMedia(val images: List<String>, val videos: List<String>)

private fun extractChatMedia(text: String): ChatMedia {
    val urls = Regex("""https?://\S+""").findAll(text)
        .map { it.value.trimEnd('.', ',', '。', ')', ']', '"', '\'') }
        .filter { it.contains("agnes-ai.space") }
        .distinct().toList()
    val videos = urls.filter { it.contains("/videos/") || it.endsWith(".mp4") }
    val images = urls.filter { it !in videos }
    return ChatMedia(images, videos)
}

/** 展示文本去掉已内嵌的媒体 URL + 残留的"图片链接/视频链接:"标签行(只露内嵌图,不露图片来源 URL)。 */
private fun stripMediaUrls(text: String, urls: List<String>): String {
    var s = text
    for (u in urls) s = s.replace(u, "")
    s = s.lineSequence()
        .filterNot { it.trim().matches(Regex("""(图片|视频)?(链接|地址)\s*[:：]?""")) }
        .joinToString("\n")
    return s.replace(Regex("""\n{3,}"""), "\n\n").trim()
}

/** 内嵌视频播放:系统 VideoView + 控件条(无需额外依赖)。 */
@Composable
private fun InlineVideo(url: String) {
    AndroidView(
        modifier = Modifier
            .padding(top = OctopusSpacing.sm)
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp)),
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                setVideoURI(android.net.Uri.parse(url))
                val controller = android.widget.MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

// ── 设置指引 ──────────────────────────────────────────

@Composable
private fun SetupGuideCard(
    llmOk: Boolean,
    a11yOk: Boolean,
    onConfigLlm: () -> Unit,
    onEnableA11y: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp), color = EchoChatStyle.accentSurface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_title), modifier = Modifier.weight(1f),
                    color = EchoChatStyle.onAccentSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${(if (llmOk) 1 else 0) + (if (a11yOk) 1 else 0)}/2", color = PrimaryColor, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    SetupRow(stringResource(R.string.setup_llm), llmOk, onConfigLlm)
                    SetupRow(stringResource(R.string.setup_a11y), a11yOk, onEnableA11y)
                }
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onClick).padding(vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (done) "✓" else "○", fontSize = OctopusType.body, color = if (done) SuccessColor else TextMuted)
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        Text(
            label, fontSize = OctopusType.label,
            color = if (done) TextMuted else TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (!done) Text(stringResource(R.string.setup_go), fontSize = OctopusType.caption, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
    }
}

// ── 工具步骤折叠 ──────────────────────────────────────

/** 渲染行：普通消息 or 连续工具调用组 or 同 batchId 并行批次。 */
private sealed class ChatRow {
    abstract val key: String
    data class Single(val msg: ChatMessage) : ChatRow() { override val key = "m${msg.id}" }
    data class ToolGroup(val tools: List<ChatMessage.ToolCall>) : ChatRow() { override val key = "g${tools.first().id}" }
    /** refine-chat-interaction Task 2:同 batchId 的并行 ToolCall 聚合为一行,由 [ToolBatchCard] 渲染。 */
    data class ToolBatch(val batchId: String, val calls: List<ChatMessage.ToolCall>) : ChatRow() { override val key = "b$batchId" }
}

/**
 * 同 batchId 聚合的渲染项 —— 纯数据视图,不依赖 [ChatRow](private),便于单元测试覆盖。
 *
 * - [Single]:batchId == null 的 ToolCall 或任何非 ToolCall 消息(保持原样,走 [ToolGroupItem] 路径)
 * - [Batch]:同 batchId 聚合的 ToolCall 组(走 [ToolBatchCard] 路径)
 *
 * 见 `refine-chat-interaction` spec Task 2。
 */
internal sealed class BatchAggregation {
    data class Single(val msg: ChatMessage) : BatchAggregation()
    data class Batch(val calls: List<ChatMessage.ToolCall>) : BatchAggregation()
}

/**
 * 把同 batchId 的 ToolCall 跨位置聚合为 [BatchAggregation.Batch];其余消息保持为 [BatchAggregation.Single]。
 *
 * - batchId == null 的 ToolCall:保持 Single(交给 [turnRows] 内 consecutive 折叠,向后兼容)
 * - batchId != null 的 ToolCall:首次出现位置发射整组,同 batchId 的后续 ToolCall 跳过(已聚合)
 *
 * 纯函数,refine-chat-interaction Task 2 单元测试直接覆盖([ToolBatchCardTest])。
 */
internal fun groupToolCallsByBatch(messages: List<ChatMessage>): List<BatchAggregation> {
    val batchCalls = mutableMapOf<String, MutableList<ChatMessage.ToolCall>>()
    messages.filterIsInstance<ChatMessage.ToolCall>().forEach { c ->
        c.batchId?.let { batchCalls.getOrPut(it) { mutableListOf() }.add(c) }
    }
    val emittedBatches = mutableSetOf<String>()
    val result = mutableListOf<BatchAggregation>()
    for (msg in messages) {
        if (msg is ChatMessage.ToolCall && msg.batchId != null) {
            if (msg.batchId !in emittedBatches) {
                emittedBatches.add(msg.batchId)
                result.add(BatchAggregation.Batch(batchCalls[msg.batchId]!!))
            }
            // 同 batchId 后续 ToolCall 跳过(已聚合到首次出现位置)
        } else {
            result.add(BatchAggregation.Single(msg))
        }
    }
    return result
}

/**
 * 逐「轮」成行(轮 = 用户消息之间的一段助手消息)。轮内:连续 ToolCall 折叠成组;
 * 产物(Artifact:HTML 预览 / 生成的图片、文件等)整体沉到该轮末尾,让助手文字回复
 * 先出、产物/预览垫底(用户要求),工具卡与文字的相对顺序不变。跨轮不重排。
 */
private fun buildChatRows(msgs: List<ChatMessage>): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var i = 0
    while (i < msgs.size) {
        if (msgs[i] is ChatMessage.UserMessage) {
            out.add(ChatRow.Single(msgs[i])); i++
        } else {
            val turnEnd = nextTurnEnd(msgs, i)
            out.addAll(turnRows(msgs.subList(i, turnEnd)))
            i = turnEnd
        }
    }
    return out
}

/** 从 start 起找到下一条 UserMessage 的下标(本轮助手消息的右开边界)。 */
private fun nextTurnEnd(msgs: List<ChatMessage>, start: Int): Int {
    var j = start
    while (j < msgs.size && msgs[j] !is ChatMessage.UserMessage) { j++ }
    return j
}

/** 一轮助手消息成行:同 batchId 并行批聚合;连续 batchId==null ToolCall 折叠;产物行整体后移到末尾,其余保持原序。 */
private fun turnRows(turn: List<ChatMessage>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    val aggregated = groupToolCallsByBatch(turn)
    var i = 0
    while (i < aggregated.size) {
        when (val item = aggregated[i]) {
            is BatchAggregation.Batch -> {
                // refine-chat-interaction Task 2:同 batchId 跨位置聚合为一张 ToolBatchCard
                val firstBatchId = item.calls.first().batchId
                rows.add(ChatRow.ToolBatch(firstBatchId!!, item.calls))
                i++
            }
            is BatchAggregation.Single -> {
                val m = item.msg
                if (m is ChatMessage.ToolCall) {
                    // batchId == null:保持 consecutive 折叠(向后兼容,ToolGroupItem 渲染)
                    val group = mutableListOf<ChatMessage.ToolCall>()
                    while (i < aggregated.size && aggregated[i] is BatchAggregation.Single &&
                        (aggregated[i] as BatchAggregation.Single).msg is ChatMessage.ToolCall) {
                        group.add((aggregated[i] as BatchAggregation.Single).msg as ChatMessage.ToolCall); i++
                    }
                    rows.add(ChatRow.ToolGroup(group))
                } else {
                    rows.add(ChatRow.Single(m)); i++
                }
            }
        }
    }
    val (artifacts, others) = rows.partition { it is ChatRow.Single && it.msg is ChatMessage.Artifact }
    return others + artifacts
}

private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when (name) {
    "get_screen_info" -> Icons.Filled.Visibility
    "open_app" -> Icons.AutoMirrored.Filled.Launch
    "tap", "long_press" -> Icons.Filled.TouchApp
    "input_text" -> Icons.Filled.Keyboard
    "swipe", "scroll_to_find" -> Icons.Filled.Swipe
    "take_screenshot" -> Icons.Filled.CameraAlt
    "wait" -> Icons.Filled.Timer
    "system_key" -> Icons.Filled.SettingsRemote
    "find_and_tap", "find_text", "find_node" -> Icons.Filled.Search
    "launch_freeform" -> Icons.AutoMirrored.Filled.OpenInNew
    else -> Icons.Filled.Build
}

private fun toolColor(name: String): Color = when (name) {
    "get_screen_info" -> OctopusTints.Window
    "open_app" -> OctopusTints.Plugin
    "tap", "long_press", "swipe", "scroll_to_find" -> OctopusTints.Routine
    "input_text" -> OctopusTints.Memory
    "take_screenshot" -> OctopusTints.Video
    "wait" -> OctopusTints.Trust
    "system_key" -> OctopusTints.Skill
    "find_and_tap", "find_text", "find_node" -> OctopusTints.Browser
    "launch_freeform" -> OctopusTints.Cloud
    else -> PrimaryColor
}

/** 单个工具直接显示;多个工具折叠成可展开的「N 步骤」块。 */
@Composable
private fun ToolGroupItem(
    tools: List<ChatMessage.ToolCall>,
    expanded: Boolean,
    onToggle: (Long) -> Unit,
    onShowDetail: (List<ChatMessage.ToolCall>) -> Unit,
) {
    if (tools.size == 1) { ToolCallItem(tools[0]) { onShowDetail(tools) }; return }
    val gid = tools.first().id
    val firstTint = toolColor(tools.first().toolName)
    val firstIcon = toolIcon(tools.first().toolName)
    Surface(
        shape = OctopusShape.medium,
        color = PrimaryColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
        modifier = Modifier.clickable { onToggle(gid) },
        shadowElevation = OctopusThemeStyle.cardShadow(1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolColorBar(firstTint)
                Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                Icon(firstIcon, contentDescription = null, tint = firstTint, modifier = Modifier.size(OctopusIconSize.small))
                Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                Text(
                    stringResource(R.string.chat_tool_steps, tools.size),
                    fontSize = OctopusType.caption, color = PrimaryColor, fontWeight = FontWeight.SemiBold,
                )
                if (!expanded) {
                    Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                    Text(
                        "· ${tools.last().toolName} ${toolCallSummary(tools.last())}",
                        fontSize = OctopusType.caption, color = TextMuted,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { onShowDetail(tools) }) {
                    Text(stringResource(R.string.tool_detail_details), fontSize = OctopusType.tag)
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
            }
            if (expanded) {
                tools.forEach { t ->
                    Spacer(modifier = Modifier.height(OctopusSpacing.sm))
                    ToolCallItem(t) { onShowDetail(listOf(t)) }
                }
            }
        }
    }
}

@Composable
private fun ToolColorBar(color: Color) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(20.dp)
            .background(color, RoundedCornerShape(2.dp))
    )
}

@Composable
private fun ToolCallItem(msg: ChatMessage.ToolCall, onShowDetail: () -> Unit = {}) {
    val tint = toolColor(msg.toolName)
    val icon = toolIcon(msg.toolName)
    Surface(
        modifier = Modifier.clickable(onClick = onShowDetail),
        shape = OctopusShape.medium,
        color = PrimaryColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
        shadowElevation = OctopusThemeStyle.cardShadow(1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolColorBar(tint)
            Spacer(modifier = Modifier.width(OctopusSpacing.sm))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
            Spacer(modifier = Modifier.width(OctopusSpacing.sm))
            Text(
                msg.toolName,
                fontSize = OctopusType.caption,
                color = PrimaryColor,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (msg.args.isNotEmpty()) {
                Spacer(modifier = Modifier.width(OctopusSpacing.xs))
                // 单行省略：长无空格串（如 package_name=…deskclock）此前会被挤成每行一个字、竖排。
                Text(
                    msg.args,
                    modifier = Modifier.weight(1f),
                    fontSize = OctopusType.caption,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            run {
                Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                // 结果限宽 + 单行省略，避免与 args 抢宽度后被压成竖排字符。
                Text(
                    toolCallSummary(msg),
                    modifier = Modifier.widthIn(max = 120.dp),
                    fontSize = OctopusType.caption,
                    color = toolStatusColor(msg.status),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 并行工具批次折叠卡(refine-chat-interaction Task 2)。
 *
 * 同 batchId 的多个 ToolCall 聚合为单张卡片:
 * - 折叠态(默认):「N 个工具并行执行中…」(全部完成则「N 个工具已执行」),副标题工具名列表
 *   (3 个以上显示前 2 个 + 「等 N 个」)
 * - 展开态:逐行工具名 + 状态图标(✓ 绿/✗ 红/⏳ 主色)+ 耗时(ms),并提供「详情」入口
 *   打开 [ToolsDetailPane] 查看完整 result(refine-chat-interaction Task 4)
 *
 * 极简 flat UI(INV-U5):OctopusShape.small 圆角、SurfaceDeepColor 单色实底、无阴影、无渐变。
 * 用既有 OctopusColors / OctopusShape / OctopusSpacing / OctopusType,不引入新颜色。
 *
 * @param onShowDetail 点击「详情」按钮打开 ToolsDetailPane 的回调
 */
@Composable
private fun ToolBatchCard(
    calls: List<ChatMessage.ToolCall>,
    onShowDetail: () -> Unit = {},
) {
    require(calls.isNotEmpty()) { "ToolBatchCard requires at least one call" }
    val firstId = calls.first().id
    var expanded by remember(firstId) { mutableStateOf(false) }
    val title = toolExecutionSummary(calls)
    val names = buildString {
        calls.take(2).joinToString(", ") { it.toolName }.let { append(it) }
        if (calls.size > 2) append(" 等 ${calls.size} 个")
    }
    Surface(
        shape = OctopusShape.small,
        color = SurfaceDeepColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OctopusSpacing.xs)
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = OctopusType.caption,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onShowDetail,
                    contentPadding = PaddingValues(horizontal = OctopusSpacing.sm, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.tool_detail_details), fontSize = OctopusType.tag)
                }
            }
            Spacer(modifier = Modifier.height(OctopusSpacing.xs))
            Text(names, fontSize = OctopusType.tag, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                Spacer(modifier = Modifier.height(OctopusSpacing.sm))
                calls.forEach { call ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (call.status) {
                                ChatMessage.ToolCallStatus.SUCCESS -> "✓"
                                ChatMessage.ToolCallStatus.FAILURE -> "✗"
                                ChatMessage.ToolCallStatus.RUNNING -> "⏳"
                                ChatMessage.ToolCallStatus.CANCELLED -> "■"
                            },
                            color = when (call.status) {
                                ChatMessage.ToolCallStatus.SUCCESS -> SuccessColor
                                ChatMessage.ToolCallStatus.FAILURE -> ErrorColor
                                ChatMessage.ToolCallStatus.RUNNING -> PrimaryColor
                                ChatMessage.ToolCallStatus.CANCELLED -> TextMuted
                            },
                            fontSize = OctopusType.caption,
                        )
                        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                        Text(
                            call.toolName,
                            fontSize = OctopusType.caption,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        call.durationMs?.let {
                            Spacer(modifier = Modifier.width(OctopusSpacing.xs))
                            Text("${it}ms", fontSize = OctopusType.tag, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

/** ⋮ 菜单里的一行功能快捷入口:图标 + 标题,点了收起菜单并打开对应功能页。 */
@Composable
private fun MoreMenuLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleRes: Int,
    target: Class<*>,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DropdownMenuItem(
        text = { Text(stringResource(titleRes)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = PrimaryColor) },
        onClick = {
            onDismiss()
            runCatching { ctx.startActivity(android.content.Intent(ctx, target)) }
        },
    )
}

@Composable
private fun ThinkingItem(text: String) {
    // 有耗时工具(如 generate_app)冒泡阶段进度时,显示阶段(「生成代码中…」),否则显示「思考中」
    val stage by com.apk.claw.android.agent.AgentProgressBus.stage.collectAsState()
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(PrimaryColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(OctopusIconSize.medium),
            )
        }
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        Text(stage ?: text, fontSize = OctopusType.label, color = TextSecondary)
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        (0..2).forEach { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Text(
                "·",
                fontSize = OctopusType.title,
                fontWeight = FontWeight.Bold,
                color = PrimaryColor.copy(alpha = alpha),
            )
        }
    }
}

/**
 * 产物卡片(类 Claude Artifacts)—— 在对话流中展示工具产生的结构化产物。
 *
 * - HTML: 「预览」按钮,点击调 [com.apk.claw.android.ui.web.WebActivity.startHtml] 全屏预览
 *   (preview_html / generate_app 产生的 HTML 字符串)。
 * - IMAGE: 缩略图,点击在 WebActivity 中全屏查看(take_screenshot 等)。
 * - FILE: 文件路径卡片(run_code writeFile / file_ops / generate_app 等产生的文件)。
 * - DIFF: diff 文本渲染块(edit_file 等)。
 *
 * refine-chat-interaction Task 3:新增 [onClick] 回调,卡片整体可点击,
 * 由调用方注入「打开右侧详情抽屉」逻辑(INV-U2:详情不在消息流展开)。
 *
 * 大 payload(HTML/图片 base64)从 [ChatStore.loadPayload] 按需读取,主消息列表只存引用。
 *
 * @param onClick 卡片点击回调;Task 4 在此处根据 artifact.kind 创建对应 DetailPane
 */
@Composable
private fun ArtifactCard(artifact: ChatMessage.Artifact, onClick: () -> Unit = {}) {
    if (artifact.kind == ChatMessage.ArtifactKind.FILE && artifact.payloadRef.startsWith(RemoteArtifactTransfer.PREFIX)) {
        RemoteArtifactCard(artifact)
        return
    }
    val tint = artifactTint(artifact.kind)
    val icon = artifactIcon(artifact.kind)
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = OctopusShape.medium,
        color = tint.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
        shadowElevation = OctopusThemeStyle.cardShadow(1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            ArtifactHeader(artifact, tint, icon)
            ArtifactBody(artifact, kind = artifact.kind)
        }
    }
}

@Composable
private fun ArtifactHeader(artifact: ChatMessage.Artifact, tint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        Text(
            artifact.title,
            modifier = Modifier.weight(1f),
            fontSize = OctopusType.caption,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (artifact.kind == ChatMessage.ArtifactKind.HTML) {
            TextButton(
                onClick = {
                    val html = ChatStore.loadPayload(artifact.payloadRef) ?: return@TextButton
                    com.apk.claw.android.ui.web.WebActivity.startHtml(context, html, artifact.title)
                },
                contentPadding = PaddingValues(horizontal = OctopusSpacing.sm, vertical = 0.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(OctopusIconSize.small),
                )
                Spacer(modifier = Modifier.width(ARTIFACT_BTN_GAP))
                Text(stringResource(R.string.chat_artifact_preview), fontSize = OctopusType.caption)
            }
        }
    }
}

@Composable
private fun ArtifactBody(artifact: ChatMessage.Artifact, kind: ChatMessage.ArtifactKind) {
    val context = LocalContext.current
    when (kind) {
        ChatMessage.ArtifactKind.IMAGE -> ArtifactImage(artifact, context)
        ChatMessage.ArtifactKind.FILE -> Text(
            artifact.payloadRef,
            fontSize = OctopusType.caption,
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = OctopusSpacing.xs),
        )
        ChatMessage.ArtifactKind.DIFF -> {
            val diff = remember(artifact.payloadRef) { ChatStore.loadPayload(artifact.payloadRef) }
            if (diff != null) DiffView(diff)
        }
        ChatMessage.ArtifactKind.HTML -> Unit // 只显示预览按钮
        // refine-chat-interaction:3 个新类型的详情走右侧栏 DetailDrawer,消息流只显示 preview。
        // preview 渲染由 Task 4 子代理在 DetailPane 实现中补全;此处先放占位,
        // 不在消息流展开详情(INV-U2)。
        ChatMessage.ArtifactKind.PLAN -> PlanPreview(artifact)
        ChatMessage.ArtifactKind.CODE_SNIPPET -> CodeSnippetPreview(artifact)
        ChatMessage.ArtifactKind.TEXT -> TextPreview(artifact)
    }
}

// refine-chat-interaction Task 1:3 个新 ArtifactKind 的 preview 占位。
// 消息流只显示简短预览(标题 + 1-3 行),详情由 Task 3/4 子代理实现右侧栏 DetailPane。
@Composable
private fun PlanPreview(artifact: ChatMessage.Artifact) {
    val plan = remember(artifact.payloadRef) { ChatStore.loadPayload(artifact.payloadRef) }
    val stepCount = remember(plan) {
        runCatching {
            org.json.JSONArray(plan).length()
        }.getOrDefault(0)
    }
    Text(
        "计划 · $stepCount 步 · 点击查看详情",
        fontSize = OctopusType.caption,
        color = TextMuted,
        modifier = Modifier.padding(top = OctopusSpacing.xs),
    )
}

@Composable
private fun CodeSnippetPreview(artifact: ChatMessage.Artifact) {
    val snippet = remember(artifact.payloadRef) { ChatStore.loadPayload(artifact.payloadRef) }
    val preview = remember(snippet) {
        snippet?.lineSequence()?.take(2)?.joinToString("\n") ?: ""
    }
    Text(
        preview.ifBlank { "代码片段 · 点击查看详情" },
        fontSize = OctopusType.caption,
        color = TextSecondary,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = OctopusSpacing.xs),
    )
}

@Composable
private fun TextPreview(artifact: ChatMessage.Artifact) {
    val context = LocalContext.current
    val body = remember(artifact.payloadRef) { ChatStore.loadPayload(artifact.payloadRef) }
    // refine-chat-interaction Task 6:Git 工具产物 body 形如 "URL: https://...\n\n正文",
    // 解析首行 URL 前缀。若存在则显示「打开」按钮(Intent.ACTION_VIEW),例如 PR 卡片
    // 可一键跳转浏览器查看。其余行作为预览文本。
    val urlPrefix = "URL: "
    val (displayUrl, previewText) = remember(body) {
        if (body != null && body.startsWith(urlPrefix)) {
            val firstLine = body.lineSequence().firstOrNull() ?: ""
            val url = firstLine.removePrefix(urlPrefix).trim()
            // 跳过首行 URL 和随后的空行,取下一行作为预览
            val preview = body.lineSequence().drop(1).dropWhile { it.isBlank() }
                .firstOrNull()?.take(80) ?: artifact.title
            url to preview
        } else {
            null to (body?.lineSequence()?.firstOrNull()?.take(80) ?: "")
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = OctopusSpacing.xs),
    ) {
        Text(
            previewText.ifBlank { artifact.title },
            fontSize = OctopusType.caption,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (displayUrl != null) {
            Spacer(modifier = Modifier.width(OctopusSpacing.sm))
            TextButton(
                onClick = {
                    val uri = runCatching { android.net.Uri.parse(displayUrl) }.getOrNull()
                        ?: return@TextButton
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                contentPadding = PaddingValues(horizontal = OctopusSpacing.sm, vertical = 0.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(OctopusIconSize.small),
                )
                Spacer(modifier = Modifier.width(ARTIFACT_BTN_GAP))
                Text(stringResource(R.string.chat_artifact_open), fontSize = OctopusType.caption)
            }
        }
    }
}

@Composable
private fun ArtifactImage(artifact: ChatMessage.Artifact, context: android.content.Context) {
    val base64 = remember(artifact.payloadRef) { ChatStore.loadPayload(artifact.payloadRef) } ?: return
    val bytes = remember(base64) {
        runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }.getOrNull()
    } ?: return
    coil.compose.AsyncImage(
        model = bytes,
        contentDescription = artifact.title,
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
        modifier = Modifier
            .padding(top = OctopusSpacing.sm)
            .fillMaxWidth()
            .heightIn(max = ARTIFACT_IMG_MAX_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                // 用 WebActivity 全屏查看:包一层最简 HTML
                val html = imagePreviewHtml(base64)
                com.apk.claw.android.ui.web.WebActivity.startHtml(context, html, artifact.title)
            },
    )
}

private fun imagePreviewHtml(base64: String): String =
    "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<style>body{margin:0;background:#000;display:flex;min-height:100vh;" +
        "align-items:center;justify-content:center}" +
        "img{max-width:100%;max-height:100vh;object-fit:contain}</style>" +
        "</head><body><img src='data:image/jpeg;base64,$base64'></body></html>"

private fun artifactTint(kind: ChatMessage.ArtifactKind): Color = when (kind) {
    ChatMessage.ArtifactKind.HTML -> OctopusTints.Browser
    ChatMessage.ArtifactKind.IMAGE -> OctopusTints.Video
    ChatMessage.ArtifactKind.FILE -> OctopusTints.Memory
    ChatMessage.ArtifactKind.DIFF -> PrimaryColor
    // refine-chat-interaction:3 个新类型沿用 OctopusTints 单色,无渐变(INV-U5)
    ChatMessage.ArtifactKind.PLAN -> OctopusTints.Skill        // 蓝 —— 计划
    ChatMessage.ArtifactKind.CODE_SNIPPET -> OctopusTints.CatDev // 蓝 —— 代码
    ChatMessage.ArtifactKind.TEXT -> OctopusTints.Routine      // 橙 —— 文本结果
}

private fun artifactIcon(kind: ChatMessage.ArtifactKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    ChatMessage.ArtifactKind.HTML -> Icons.AutoMirrored.Filled.OpenInNew
    ChatMessage.ArtifactKind.IMAGE -> Icons.Filled.CameraAlt
    ChatMessage.ArtifactKind.FILE -> Icons.Filled.Storage
    ChatMessage.ArtifactKind.DIFF -> Icons.Filled.Build
    ChatMessage.ArtifactKind.PLAN -> Icons.AutoMirrored.Filled.ListAlt  // 计划列表
    ChatMessage.ArtifactKind.CODE_SNIPPET -> Icons.Filled.Code           // 代码片段
    ChatMessage.ArtifactKind.TEXT -> Icons.AutoMirrored.Filled.Notes     // 文本
}

/**
 * refine-chat-interaction Task 4:根据 [ChatMessage.Artifact.kind] 创建对应的 [DetailPane]。
 *
 * - PLAN:解析 payloadRef 中的 planJson,构造 [PlanDetailPane];批准按钮触发 exit_plan_mode 工具
 * - CODE_SNIPPET:从 title 解析 file/startLine/endLine,snippet 从 payloadRef 读取
 * - DIFF:从 payloadRef 读取 diff 文本
 * - TEXT:从 payloadRef 读取 body
 * - HTML/IMAGE/FILE:不在此打开右侧栏(预览/打开由卡片内独立按钮处理),返回 null
 *
 * 返回 null 表示不打开 DetailDrawer(消息流卡片仅做 preview,INV-U2)。
 *
 * @param onClose 关闭 DetailDrawer 的回调(由 PlanDetailPane 批准后调用,触发工具并关闭抽屉)
 */
private fun artifactDetailPane(
    artifact: ChatMessage.Artifact,
    onClose: () -> Unit,
): DetailPane? = when (artifact.kind) {
    ChatMessage.ArtifactKind.PLAN -> {
        val planJson = ChatStore.loadPayload(artifact.payloadRef) ?: ""
        PlanDetailPane(planJson) {
            // 触发 exit_plan_mode 工具(简化:直接调用 ToolRegistry,绕过 Agent 流水线)。
            runCatching {
                com.apk.claw.android.tool.ToolRegistry.getInstance()
                    .executeTool("exit_plan_mode", emptyMap())
            }
            // 关闭抽屉 —— DetailDrawer 的 onClose 也会触发,这里显式置空保证状态一致。
            onClose()
        }
    }
    ChatMessage.ArtifactKind.CODE_SNIPPET -> {
        val snippet = ChatStore.loadPayload(artifact.payloadRef) ?: ""
        val (file, start, end) = parseCodeSnippetTitle(artifact.title)
        CodeDetailPane(file, start, end, snippet)
    }
    ChatMessage.ArtifactKind.DIFF -> {
        val diff = ChatStore.loadPayload(artifact.payloadRef) ?: ""
        DiffDetailPane(diff)
    }
    ChatMessage.ArtifactKind.TEXT -> {
        val body = ChatStore.loadPayload(artifact.payloadRef) ?: ""
        TextDetailPane(artifact.title, body)
    }
    // HTML/IMAGE/FILE 的详情走卡片内既有「预览/打开」按钮,不打开右侧栏。
    ChatMessage.ArtifactKind.HTML,
    ChatMessage.ArtifactKind.IMAGE,
    ChatMessage.ArtifactKind.FILE -> null
}

/**
 * 解析 CODE_SNIPPET Artifact 的 title 为 (file, startLine, endLine)。
 *
 * - 输入形如 `file.kt:42-58` 或 `path/to/Foo.java:1-100`
 * - 无法解析时返回 `Triple("", 0, 0)`(DetailPane 仍可渲染,只是标题/行号缺失)
 *
 * refine-chat-interaction Task 4:internal 供单元测试直接调用。
 */
internal fun parseCodeSnippetTitle(title: String): Triple<String, Int, Int> {
    val match = CODE_SNIPPET_TITLE_REGEX.find(title) ?: return Triple("", 0, 0)
    val file = match.groupValues[1]
    val start = match.groupValues[2].toIntOrNull() ?: 0
    val end = match.groupValues[3].toIntOrNull() ?: 0
    return Triple(file, start, end)
}

private val CODE_SNIPPET_TITLE_REGEX = Regex("^(.+):(\\d+)-(\\d+)\$")

/** diff 渲染块:增行绿色 / 删行红色 / hunk 头主色 / 其余次要色,过长截断。 */
@Composable
private fun DiffView(diff: String) {
    val lines = remember(diff) { diff.lineSequence().toList() }
    val shown = remember(lines) { lines.take(DIFF_MAX_LINES) }
    Surface(
        shape = OctopusShape.small,
        color = SurfaceDeepColor,
        modifier = Modifier.fillMaxWidth().padding(top = OctopusSpacing.sm),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.sm)) {
            shown.forEach { line -> DiffLine(line) }
            if (lines.size > shown.size) {
                Text(
                    "… (共 ${lines.size} 行,已截断)",
                    fontSize = DIFF_TRUNCATED_FONT,
                    color = TextMuted,
                    modifier = Modifier.padding(top = DIFF_TRUNCATED_GAP),
                )
            }
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    val color = when {
        line.startsWith("+") && !line.startsWith("+++") -> SuccessColor
        line.startsWith("-") && !line.startsWith("---") -> ErrorColor
        line.startsWith("@@") -> PrimaryColor
        else -> TextSecondary
    }
    Text(
        line,
        fontSize = DIFF_LINE_FONT,
        color = color,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val DIFF_MAX_LINES = 50
private val DIFF_LINE_FONT = 11.sp
private val DIFF_TRUNCATED_FONT = 10.sp
private val ARTIFACT_BTN_GAP = 4.dp
private val ARTIFACT_IMG_MAX_HEIGHT = 240.dp
private val DIFF_TRUNCATED_GAP = 4.dp

@Composable
private fun ScrollToBottomButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.8f, animationSpec = tween(180)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f, animationSpec = tween(120)),
    ) {
        Surface(
            modifier = Modifier
                .clip(OctopusShape.capsule)
                .clickable(onClick = onClick),
            shape = OctopusShape.capsule,
            color = OctopusBackground.cardSurface,
            border = BorderStroke(1.dp, OctopusBackground.cardBorder),
            shadowElevation = OctopusThemeStyle.cardShadow(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PrimaryColor,
                    modifier = Modifier.size(OctopusIconSize.small),
                )
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text(
                    stringResource(R.string.chat_scroll_bottom),
                    color = PrimaryColor,
                    fontSize = OctopusType.label,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 澄清问卷卡片——显示在输入框上方，用户点选选项后点"确认"填入输入框。
 * 每个问题展示为标题 + 一组选项 Chip（单选），选完所有题后"确认"按钮亮起。
 * 确认后答案填入输入框，用户可补充文字或直接点发送键提交。
 * 右上角有关闭按钮，点关闭直接取消问卷（用户可继续打字自由输入）。
 */
@Composable
private fun ClarifyFormCard(
    form: PendingClarifyForm,
    onSelect: (qIdx: Int, optIdx: Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val allSelected = form.selections.all { it >= 0 }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg)
            .padding(bottom = OctopusSpacing.xs),
        shape = OctopusShape.xl,
        color = PrimaryColor.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.25f)),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.md)) {
            // 标题行：图标 + 标题 + 关闭按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = PrimaryColor,
                    modifier = Modifier.size(OctopusIconSize.small),
                )
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(
                    "帮我确认几个小问题",
                    fontSize = OctopusType.bodyStrong,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryColor,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            // 每个问题：问题标题 + 选项 Chips
            form.questions.forEachIndexed { qIdx, question ->
                if (qIdx > 0) Spacer(Modifier.height(OctopusSpacing.md))
                Text(
                    question.q,
                    fontSize = OctopusType.body,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(OctopusSpacing.sm))
                val chunked = question.options.chunked(2)
                chunked.forEach { rowOpts ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = OctopusSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
                    ) {
                        rowOpts.forEach { opt ->
                            val optIdx = question.options.indexOf(opt)
                            val selected = form.selections.getOrNull(qIdx) == optIdx
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSelect(qIdx, optIdx) },
                                shape = OctopusShape.large,
                                color = if (selected) PrimaryColor else SurfaceDeepColor,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) PrimaryColor else BorderColor.copy(alpha = 0.5f),
                                ),
                            ) {
                                Text(
                                    opt,
                                    modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
                                    fontSize = OctopusType.bodyStrong,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) OnPrimaryColor else TextPrimary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (rowOpts.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(OctopusSpacing.md))
            // 确认按钮：点了把答案填入输入框，用户可补充后手动发送
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        color = if (allSelected) PrimaryColor else TextMuted.copy(alpha = 0.25f),
                        shape = OctopusShape.large,
                    )
                    .clickable(enabled = allSelected, onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (allSelected) "确认" else "请先选择所有选项",
                    color = if (allSelected) OnPrimaryColor else TextMuted,
                    fontSize = OctopusType.bodyStrong,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun toolStatusColor(status: ChatMessage.ToolCallStatus): Color = when (status) {
    ChatMessage.ToolCallStatus.RUNNING -> PrimaryColor
    ChatMessage.ToolCallStatus.SUCCESS -> SuccessColor
    ChatMessage.ToolCallStatus.FAILURE -> ErrorColor
    ChatMessage.ToolCallStatus.CANCELLED -> TextMuted
}
