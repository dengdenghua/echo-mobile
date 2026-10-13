@file:Suppress("TooManyFunctions", "MagicNumber", "MaxLineLength", "LongMethod")

package com.apk.claw.android.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountFeature
import com.apk.claw.android.account.AccountGate
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.ui.account.SignInRequiredPanel
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import com.apk.claw.android.voice.realtime.VoiceRealtimeClient
import com.apk.claw.android.ui.compose.screen.ChatMessage
import com.apk.claw.android.ui.compose.screen.ChatStore
import com.apk.claw.android.ui.compose.screen.SessionStore
import com.apk.claw.android.ui.desktop.CharacterRegistry
import com.apk.claw.android.voice.realtime.VoiceState
import com.apk.claw.android.voice.realtime.VoiceStats
import kotlinx.coroutines.delay

/**
 * 全屏实时语音通话页(豆包式)。对话页输入栏「通话」按钮进这里。
 * 通话时常亮不熄屏;离屏(onDispose)自动挂断释放麦克风。
 */
class VoiceCallActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OctopusTheme {
                // 本地模式(未登录):语音走账号的语音服务 —— 显示「登录后可用」,不去连、不申请麦克风。
                if (AccountGate.isAvailable(AccountFeature.VOICE_CALL)) {
                    VoiceCallScreen(onClose = { finish() })
                } else {
                    SignInRequiredPanel(
                        feature = AccountFeature.VOICE_CALL,
                        onSignIn = {
                            startActivity(LoginActivity.signInIntent(this))
                            finish()
                        },
                        onClose = { finish() },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OctopusBackground.pageBrush())
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceCallScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val client = remember { VoiceRealtimeClient() }
    val state by client.state.collectAsState()
    val stats by client.stats.collectAsState()
    val aiText by client.aiTranscript.collectAsState()
    val userText by client.userTranscript.collectAsState()
    val speaking by client.speaking.collectAsState()

    val hasMic = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) client.connect() }
    val startCall = {
        if (hasMic()) client.connect() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        bindSharedSession(client)  // L2:挂到与文字/TV桌面同一会话,注入历史 + 每轮存回
        client.onDeviceTool = { name, args -> executeDeviceTool(context, name, args) }
        startCall()
    }
    DisposableEffect(Unit) { onDispose { client.disconnect() } }
    // 免提外放:通话时切 VoIP 通信模式并强制走扬声器 —— 默认 USAGE_VOICE_COMMUNICATION 走听筒(声音小),
    // MODE_IN_COMMUNICATION 又能保留 AudioIn/AudioOut 的硬件回声消除。离屏还原。
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { spk -> runCatching { am.setCommunicationDevice(spk) } }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
        onDispose {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                am.mode = prevMode
            }
        }
    }
    val toolHint by client.toolHint.collectAsState()

    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (state is VoiceState.Connected) {
            seconds = 0
            while (true) { delay(1000); seconds++ }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = OctopusSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CallHeader(
            state = state, seconds = seconds, stats = stats, onClose = onClose,
            onSettings = {
                runCatching {
                    context.startActivity(android.content.Intent(context, VoiceSettingsActivity::class.java))
                }
            },
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is VoiceState.Connected -> ActiveCallView(speaking = speaking, aiText = aiText, userText = userText, toolHint = toolHint)
                is VoiceState.Ended -> EndedView(reason = s.reason, onRetry = startCall, onClose = onClose)
                is VoiceState.Error -> EndedView(reason = s.msg, onRetry = startCall, onClose = onClose)
                else -> ConnectingView()
            }
        }
        if (state is VoiceState.Connected || state is VoiceState.Connecting) {
            HangUpButton(onClick = { client.disconnect(); onClose() })
            Spacer(Modifier.height(OctopusSpacing.xl))
        }
    }
}

@Composable
private fun CallHeader(state: VoiceState, seconds: Int, stats: VoiceStats, onClose: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.voice_call_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleLg,
                fontWeight = FontWeight.SemiBold,
            )
            val sub = callSubtitle(state, seconds, stats)
            if (sub.isNotEmpty()) {
                Text(sub, color = OctopusColors.TextTertiary, fontSize = OctopusType.caption)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.voice_settings_title), tint = OctopusColors.TextSecondary)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.voice_call_close), tint = OctopusColors.TextSecondary)
        }
    }
}

@Composable
private fun callSubtitle(state: VoiceState, seconds: Int, stats: VoiceStats): String = when (state) {
    is VoiceState.Connected -> {
        val t = "%02d:%02d".format(seconds / 60, seconds % 60)
        val bill = if (stats.billedMode == "free") stringResource(R.string.voice_call_billing_free)
        else stringResource(R.string.voice_call_billing_paid, stats.creditsSpent)
        "$t · $bill"
    }
    is VoiceState.Connecting -> stringResource(R.string.voice_call_connecting)
    else -> ""
}

@Composable
private fun ConnectingView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = OctopusColors.Primary)
        Spacer(Modifier.height(OctopusSpacing.lg))
        Text(stringResource(R.string.voice_call_connecting), color = OctopusColors.TextSecondary, fontSize = OctopusType.body)
    }
}

@Composable
private fun ActiveCallView(speaking: Boolean, aiText: String, userText: String, toolHint: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        VoiceOrb(speaking = speaking)
        Spacer(Modifier.height(OctopusSpacing.xl))
        Text(
            when {
                toolHint.isNotBlank() -> "🔧 $toolHint"
                speaking -> stringResource(R.string.voice_call_speaking)
                else -> stringResource(R.string.voice_call_listening)
            },
            color = if (toolHint.isNotBlank()) OctopusColors.Primary else OctopusColors.TextTertiary,
            fontSize = OctopusType.caption,
        )
        Spacer(Modifier.height(OctopusSpacing.lg))
        val scroll = rememberScrollState()
        LaunchedEffect(aiText) { scroll.animateScrollTo(scroll.maxValue) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(OctopusShape.large)
                .background(OctopusBackground.cardSurface)
                .padding(OctopusSpacing.lg)
                .verticalScroll(scroll),
        ) {
            Text(aiText, color = OctopusColors.TextPrimary, fontSize = OctopusType.bodyLg, textAlign = TextAlign.Start)
        }
        if (userText.isNotBlank()) {
            Spacer(Modifier.height(OctopusSpacing.md))
            Text("🗣️ $userText", color = OctopusColors.TextTertiary, fontSize = OctopusType.caption)
        }
    }
}

@Composable
private fun VoiceOrb(speaking: Boolean) {
    val scale by animateFloatAsState(targetValue = if (speaking) 1.12f else 1.0f, label = "orb")
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (speaking) OctopusColors.Primary else OctopusColors.PrimaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = if (speaking) OctopusColors.OnPrimary else OctopusColors.Primary,
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun EndedView(reason: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(friendlyReason(reason), color = OctopusColors.TextPrimary, fontSize = OctopusType.bodyLg, textAlign = TextAlign.Center)
        Spacer(Modifier.height(OctopusSpacing.xl))
        Row {
            RoundTextButton(text = stringResource(R.string.voice_call_retry), primary = true, onClick = onRetry)
            Spacer(Modifier.width(OctopusSpacing.md))
            RoundTextButton(text = stringResource(R.string.voice_call_close), primary = false, onClick = onClose)
        }
    }
}

@Composable
private fun friendlyReason(reason: String): String = when (reason) {
    "insufficient_credits" -> stringResource(R.string.voice_call_reason_insufficient)
    "session_limit" -> stringResource(R.string.voice_call_reason_limit)
    else -> reason.ifBlank { stringResource(R.string.voice_call_ended) }
}

@Composable
private fun RoundTextButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(OctopusShape.capsule)
            .background(if (primary) OctopusColors.Primary else OctopusColors.FillSecondary)
            .clickable { onClick() }
            .padding(horizontal = OctopusSpacing.xl, vertical = OctopusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) OctopusColors.OnPrimary else OctopusColors.TextPrimary,
            fontSize = OctopusType.body,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * L2 共享记忆:把语音通话挂到与文字聊天/TV桌面**同一角色同一会话**(SessionStore/ChatStore),
 * 通话前置入最近历史(注入 omni 续上下文),每轮用户/AI 转录完成即存回该会话。
 */
private fun bindSharedSession(client: VoiceRealtimeClient) {
    val charId = CharacterRegistry.current.id
    val now = System.currentTimeMillis()
    SessionStore.ensureAtLeastOne(now, emptyList(), charId)
    val sid = SessionStore.currentId(charId) ?: SessionStore.create(now, charId).id
    client.seedHistory = ChatStore.load(sid).orEmpty().mapNotNull {
        when (it) {
            is ChatMessage.UserMessage -> true to it.text
            is ChatMessage.AgentMessage -> false to it.text
            else -> null
        }
    }.takeLast(12)
    client.onUserTurn = { t -> appendVoiceTurn(sid, ChatMessage.UserMessage(t)) }
    client.onAiTurn = { t -> appendVoiceTurn(sid, ChatMessage.AgentMessage(t)) }
}

private fun appendVoiceTurn(sid: String, msg: ChatMessage) {
    val cur = ChatStore.load(sid).orEmpty().toMutableList()
    cur.add(msg)
    ChatStore.save(sid, cur)
}

private val voiceToolMainHandler = android.os.Handler(android.os.Looper.getMainLooper())

/**
 * 设备侧工具执行(Phase 3-B)。omni 决定调用 → 服务端中继 → 这里在设备上执行 → 返回 JSON 结果串。
 * get_battery_level:读电量(只读);open_url:打开网址(有副作用,主线程起 Intent,不阻塞语音线程)。
 */
private fun executeDeviceTool(context: android.content.Context, name: String, argsJson: String): String = when (name) {
    "get_battery_level" -> {
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val lvl = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        """{"level":$lvl}"""
    }
    "open_url" -> {
        val url = runCatching { org.json.JSONObject(argsJson).optString("url") }.getOrNull().orEmpty()
        if (url.isBlank()) {
            """{"ok":false,"error":"no_url"}"""
        } else {
            voiceToolMainHandler.post {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            """{"ok":true}"""
        }
    }
    else -> """{"error":"unknown_tool"}"""
}

@Composable
private fun HangUpButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(OctopusColors.Error)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.CallEnd,
            contentDescription = stringResource(R.string.voice_call_hangup),
            tint = OctopusColors.OnPrimary,
            modifier = Modifier.size(30.dp),
        )
    }
}
