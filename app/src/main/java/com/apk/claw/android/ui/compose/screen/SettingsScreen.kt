package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.capture.ScreenCaptureService
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.octopus_mobile.RemoteStreamPrefs
import com.apk.claw.android.server.RemoteConsoleGateway
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.widget.AdvancedPermissionDialog
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.tvFocusable
import com.apk.claw.android.ui.compose.theme.tvOverscan
import com.apk.claw.android.ui.featurescreens.EvolutionActivity
import com.apk.claw.android.ui.featurescreens.FileManagerActivity
import com.apk.claw.android.ui.featurescreens.MemoryActivity
import com.apk.claw.android.ui.featurescreens.McpServersActivity
import com.apk.claw.android.ui.featurescreens.PersonasActivity
import com.apk.claw.android.ui.featurescreens.TrustCenterActivity
import com.apk.claw.android.ui.featurescreens.WakeWordSettingsActivity
import com.apk.claw.android.ui.featurescreens.WorkflowsActivity
import com.apk.claw.android.ui.settings.LlmConfigActivity
import com.apk.claw.android.ui.settings.RuntimeConfigActivity
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.account.AccountActivity
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.update.AppUpdater
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import kotlinx.coroutines.launch

// ── 真实权限/状态探测 ──────────────────────────────────

private fun isNotifEnabled(c: Context): Boolean =
    runCatching { NotificationManagerCompat.from(c).areNotificationsEnabled() }.getOrDefault(false)

private fun isOverlayGranted(c: Context): Boolean =
    runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false)

private fun isBatteryUnrestricted(c: Context): Boolean = runCatching {
    val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(c.packageName) ?: false
}.getOrDefault(false)

private fun isStorageGranted(c: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        c, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isShizukuReady(): Boolean = runCatching { ShizukuManager.isAvailable() }.getOrDefault(false)

/** 未授权权限的跳转 Intent */
private fun permissionIntent(index: Int, context: Context): Intent? = when (index) {
    0 -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)           // 无障碍
    1 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {  // 通知
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    2 -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { // 悬浮窗
        data = Uri.parse("package:${context.packageName}")
    }
    3 -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) // 电池
    4 -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { // 存储
        data = Uri.parse("package:${context.packageName}")
    }
    5 -> null // Shizuku 需要单独 App，无法直接跳转
    else -> null
}

private const val SHOW_BYO_MODEL_CONFIG = false

private val PrimaryColor get() = OctopusColors.Primary
private val SuccessColor get() = OctopusColors.Success
private val WarningColor get() = OctopusColors.Warning
private val ErrorColor get() = OctopusColors.Error
private val AccentColor get() = OctopusColors.Accent
private val SurfaceColor get() = OctopusColors.Surface
private val SurfaceVariantColor get() = OctopusColors.SurfaceVariant
private val BackgroundColor get() = OctopusColors.Background
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted
private val BorderColor get() = OctopusColors.Border

private data class PermissionUi(val icon: ImageVector, val name: String, val ok: Boolean)

@Composable
fun SettingsScreen(onMessage: (String) -> Unit = {}, onNavigateToCreatorCenter: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var showRemotePairDialog by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairBusy by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    var workspaceDraft by remember { mutableStateOf("") }
    var showFallbackDialog by remember { mutableStateOf(false) }
    var fallbackDraft by remember { mutableStateOf("") }
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permissionStates = remember(refreshTick) {
        listOf(
            ClawAccessibilityService.isRunning(),
            isNotifEnabled(context),
            isOverlayGranted(context),
            isBatteryUnrestricted(context),
            isStorageGranted(context),
            isShizukuReady(),
        )
    }
    val readyCount = permissionStates.count { it }
    val lanAddr = remember(refreshTick) { runCatching { ConfigServerManager.getAddress() }.getOrNull() }
    val loggedIn = remember(refreshTick) { AccountStore.isLoggedIn }
    val credits = remember(refreshTick) { AccountStore.credits }
    val isMember = remember(refreshTick) { AccountStore.byoUnlocked }
    val remotePaired = remember(refreshTick) { RemoteConsoleGateway.isPaired }
    val remoteConnected = remember(refreshTick) { RemoteConsoleGateway.isConnected }

    if (showRemotePairDialog) {
        AlertDialog(
            onDismissRequest = { if (!pairBusy) showRemotePairDialog = false },
            title = { Text(stringResource(R.string.remote_console_pair_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                    Text(stringResource(R.string.remote_console_pair_desc), color = TextMuted, fontSize = OctopusType.caption, lineHeight = 16.sp)
                    // 一键绑定:已登录即可,设备自 start 自 claim,免去手输配对码。
                    androidx.compose.material3.Button(
                        enabled = !pairBusy,
                        onClick = {
                            scope.launch {
                                pairBusy = true
                                val result = runCatching { RemoteConsoleGateway.autoPairWithAccount() }
                                pairBusy = false
                                result.onSuccess {
                                    onMessage(it)
                                    showRemotePairDialog = false
                                    refreshTick++
                                }.onFailure {
                                    onMessage(it.message ?: context.getString(R.string.remote_console_pair_failed))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (pairBusy) "绑定中…" else "一键绑定到本账号(免输码)")
                    }
                    Text("或手动输入配对码:", color = TextMuted, fontSize = OctopusType.caption)
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it.filter(Char::isDigit).take(9) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.remote_console_pair_code_label)) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pairCode.length >= 6 && !pairBusy, onClick = {
                    scope.launch {
                        pairBusy = true
                        val result = runCatching { RemoteConsoleGateway.claimPairCode(pairCode) }
                        pairBusy = false
                        result.onSuccess {
                            onMessage(it)
                            pairCode = ""
                            showRemotePairDialog = false
                            refreshTick++
                        }.onFailure {
                            onMessage(it.message ?: context.getString(R.string.remote_console_pair_failed))
                        }
                    }
                }) {
                    Text(if (pairBusy) stringResource(R.string.status_loading) else stringResource(R.string.remote_console_pair_confirm))
                }
            },
            dismissButton = {
                TextButton(enabled = !pairBusy, onClick = { showRemotePairDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { showWorkspaceDialog = false },
            title = { Text(stringResource(R.string.settings_workspace_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                    Text(stringResource(R.string.settings_workspace_desc), color = TextMuted, fontSize = OctopusType.caption, lineHeight = 16.sp)
                    OutlinedTextField(
                        value = workspaceDraft,
                        onValueChange = { workspaceDraft = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_workspace_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = workspaceDraft.isNotBlank(), onClick = {
                    KVUtils.setScriptWorkspace(workspaceDraft.trim())
                    showWorkspaceDialog = false
                    refreshTick++
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWorkspaceDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showFallbackDialog) {
        AlertDialog(
            onDismissRequest = { showFallbackDialog = false },
            title = { Text("LLM 备用模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                    Text(
                        "主模型限流/超载/不存在时,自动按顺序切到备用模型(与主模型同 baseUrl/apiKey,只换模型名)。" +
                            "逗号分隔,留空=不启用。例:qwen3.5-flash,deepseek-chat",
                        color = TextMuted, fontSize = OctopusType.caption, lineHeight = 16.sp,
                    )
                    OutlinedTextField(
                        value = fallbackDraft,
                        onValueChange = { fallbackDraft = it },
                        singleLine = true,
                        label = { Text("备用模型(逗号分隔)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    KVUtils.setLlmFallbackModels(fallbackDraft.trim())
                    showFallbackDialog = false
                    refreshTick++
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showFallbackDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showKeepAliveDialog) {
        KeepAliveCheckDialog(onDismiss = { showKeepAliveDialog = false })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(OctopusBackground.pageBrush()).statusBarsPadding().tvOverscan(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
    ) {
        item {
            SettingsHeader(
                readyCount = readyCount,
                lanAddr = lanAddr,
            )
        }

        item {
            val acct = remember(refreshTick) { AccountStore.mobile.ifEmpty { AccountStore.email } }
            SettingsCard(stringResource(R.string.menu_account), Icons.Filled.AccountCircle, compact = true, onClick = {
                val target = if (AccountStore.isLoggedIn) AccountActivity::class.java else LoginActivity::class.java
                context.startActivity(Intent(context, target))
            }) {
                if (loggedIn) {
                    SettingsRow(
                        Icons.Filled.AccountCircle,
                        acct,
                        stringResource(R.string.account_credits_label) + ": " + credits + (if (isMember) " · VIP" else ""),
                    )
                } else {
                    SettingsRow(
                        Icons.Filled.AccountCircle,
                        stringResource(R.string.account_login_title),
                        stringResource(R.string.account_login_tip),
                    )
                }
            }
        }

        // ── 创作者中心入口(仅登录可见) ──
        if (loggedIn) {
            item {
                SettingsCard(stringResource(R.string.creator_center_title), Icons.Filled.Star, compact = true, onClick = {
                    onNavigateToCreatorCenter()
                }) {
                    SettingsRow(
                        Icons.Filled.Star,
                        stringResource(R.string.creator_center_title),
                        stringResource(R.string.creator_center_entry_desc),
                    )
                }
            }
        }

        // ── 权限：可点击跳转系统设置 ──
        item {
            val permNames = listOf(
                stringResource(R.string.perm_accessibility),
                stringResource(R.string.perm_notification),
                stringResource(R.string.perm_overlay),
                stringResource(R.string.perm_battery),
                stringResource(R.string.perm_storage),
                "Shizuku",
            )
            val permIcons = listOf(
                Icons.Filled.SettingsAccessibility,
                Icons.Filled.Notifications,
                Icons.Filled.PhoneAndroid,
                Icons.Filled.BatteryChargingFull,
                Icons.Filled.Storage,
                Icons.Filled.Security,
            )
            SettingsCard(stringResource(R.string.settings_section_permissions), Icons.Filled.Shield, compact = true) {
                // 摘要行
                val perms = permNames.mapIndexed { i, name -> PermissionUi(permIcons[i], name, permissionStates[i]) }
                PermissionSummaryRow(readyCount, perms)
                Spacer(Modifier.height(OctopusSpacing.sm))
                // 逐项权限（未授权可点击跳转）
                perms.forEachIndexed { i, perm ->
                    PermissionItemRow(
                        permission = perm,
                        onClick = {
                            if (!perm.ok) {
                                if (i == 5) {
                                    // Shizuku 没有系统设置页可跳，走专门的安装/授权引导弹窗
                                    AdvancedPermissionDialog.show(context)
                                } else {
                                    permissionIntent(i, context)?.let {
                                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(it)
                                    }
                                }
                            }
                        },
                    )
                }
                // 无障碍老掉线的自助排查入口 —— 直达「保活体检」
                Spacer(Modifier.height(OctopusSpacing.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OctopusShape.small)
                        .clickable { showKeepAliveDialog = true }
                        .padding(vertical = OctopusSpacing.xs),
                ) {
                    Icon(
                        Icons.Filled.SettingsAccessibility, contentDescription = null,
                        tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.small),
                    )
                    Spacer(Modifier.width(OctopusSpacing.sm))
                    Text(
                        "无障碍老掉线?点这里做「保活体检」",
                        color = PrimaryColor, fontSize = OctopusType.caption,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ChevronRight, contentDescription = null,
                        tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small),
                    )
                }
            }
        }

        if (loggedIn && SHOW_BYO_MODEL_CONFIG) {
            item {
                val notConfiguredText = stringResource(R.string.status_not_configured)
                val configuredText = stringResource(R.string.settings_llm_api_key_configured)
                val modelName = KVUtils.getLlmModelName().ifBlank { notConfiguredText }
                val baseUrl = KVUtils.getLlmBaseUrl().ifBlank { notConfiguredText }
                val apiKey = KVUtils.getLlmApiKey()
                val keyMasked = if (apiKey.length >= 8) apiKey.take(5) + "••••" + apiKey.takeLast(4) else if (apiKey.isBlank()) notConfiguredText else configuredText
                SettingsCard(stringResource(R.string.settings_section_model), Icons.Filled.Memory, onClick = {
                    context.startActivity(Intent(context, LlmConfigActivity::class.java))
                }) {
                    SettingsRow(Icons.Filled.Api, modelName, baseUrl, trailing = keyMasked)
                    val byoHint = when {
                        !isMember -> stringResource(R.string.account_byo_member_hint)
                        credits > 0L -> stringResource(R.string.account_byo_credits_hint)
                        else -> null
                    }
                    byoHint?.let {
                        Spacer(Modifier.height(OctopusSpacing.sm))
                        Text(it, color = TextMuted, fontSize = OctopusType.caption, lineHeight = 15.sp)
                    }
                }
            }
        }

        item {
            // 「远程控制电脑」已统一并入顶栏的设备目标选择器（TargetSelector），此处不再重复入口。
            SettingsCard(stringResource(R.string.settings_device_control_section), Icons.Filled.Monitor, compact = true) {
                ClickableSettingsRow(
                    Icons.Filled.Hub,
                    stringResource(R.string.remote_console_title),
                    when {
                        remoteConnected -> stringResource(R.string.remote_console_status_connected)
                        remotePaired -> stringResource(R.string.remote_console_status_paired)
                        else -> stringResource(R.string.remote_console_status_unpaired)
                    },
                ) {
                    if (!AccountStore.isLoggedIn) {
                        onMessage(context.getString(R.string.remote_console_login_required))
                    } else {
                        showRemotePairDialog = true
                    }
                }
                SettingsDivider()
                val streamQuality = remember(refreshTick) { RemoteStreamPrefs.label() }
                ClickableSettingsRow(
                    Icons.Filled.GraphicEq,
                    "远程画质",
                    "$streamQuality · 点击切换(流畅 30fps / 清晰 1080p)",
                ) {
                    val next = if (RemoteStreamPrefs.isSharp()) RemoteStreamPrefs.SMOOTH else RemoteStreamPrefs.SHARP
                    RemoteStreamPrefs.setMode(next)
                    refreshTick++
                }
            }
        }

        // ── 高级：折叠提示增强 ──
        item {
            var advExpanded by remember { mutableStateOf(false) }
            SettingsCard(stringResource(R.string.settings_advanced_title), Icons.Filled.Tune, compact = true, onClick = { advExpanded = !advExpanded }) {
                if (advExpanded) {
                    val currentWorkspace = remember(refreshTick) { KVUtils.getScriptWorkspace() }
                    ClickableSettingsRow(Icons.Filled.Storage, stringResource(R.string.settings_workspace_title), currentWorkspace) {
                        workspaceDraft = KVUtils.getScriptWorkspace()
                        showWorkspaceDialog = true
                    }
                    SettingsDivider()
                    val currentFallback = remember(refreshTick) {
                        KVUtils.getLlmFallbackModels().joinToString(", ").ifEmpty { "未设置(单模型,不故障转移)" }
                    }
                    ClickableSettingsRow(Icons.Filled.Api, "LLM 备用模型", currentFallback) {
                        fallbackDraft = KVUtils.getLlmFallbackModels().joinToString(",")
                        showFallbackDialog = true
                    }
                    SettingsDivider()
                    ClickableSettingsRow(Icons.Filled.Hub, stringResource(R.string.settings_octopus_runtime_title), stringResource(R.string.settings_runtime_plain_desc)) {
                        context.startActivity(Intent(context, RuntimeConfigActivity::class.java))
                    }
                    SettingsDivider()
                    ClickableSettingsRow(Icons.Filled.GraphicEq, stringResource(R.string.settings_remote_lan_title), "") {
                        context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.PcRemoteActivity::class.java))
                    }
                    SettingsDivider()
                    // 高清屏幕采集(MediaProjection):开→系统投屏授权后,网页遥控台/远控画面走高帧率快路;
                    // 关→无感回退无障碍截图(2-5fps)。授权是异步系统框,状态回来后随 refreshTick 校正。
                    val hdActive = remember(refreshTick) { ScreenCaptureService.isActive() }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconBubble(Icons.Filled.Monitor, PrimaryColor)
                        Spacer(Modifier.width(OctopusSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "高清屏幕采集",
                                color = TextPrimary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (hdActive) "已开启 · 远控画面高帧率" else "关 · 远控走无障碍截图(2-5fps)",
                                fontSize = OctopusType.caption, color = TextMuted,
                            )
                        }
                        Switch(
                            checked = hdActive,
                            onCheckedChange = { on ->
                                if (on) {
                                    ScreenCaptureService.requestStart(context)
                                } else {
                                    ScreenCaptureService.stop(context)
                                }
                            },
                        )
                    }
                    SettingsDivider()
                    // 不可逆动作·撤销窗口:本机在场时,发短信/发帖/发文件前给可撤销倒计时窗(默认开)。
                    var undoOn by remember { mutableStateOf(KVUtils.isUndoWindowEnabled()) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconBubble(Icons.Filled.Undo, PrimaryColor)
                        Spacer(Modifier.width(OctopusSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "不可逆操作 · 撤销窗口",
                                color = TextPrimary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (undoOn) "开 · 发短信/发帖/发文件前给 5 秒可撤销" else "关 · 不可逆动作直接执行",
                                fontSize = OctopusType.caption, color = TextMuted,
                            )
                        }
                        Switch(
                            checked = undoOn,
                            onCheckedChange = { on ->
                                undoOn = on
                                KVUtils.setUndoWindowEnabled(on)
                            },
                        )
                    }
                    SettingsDivider()
                    // 省流感知模式:本机树够用时注入树文字替代 vision 截图,省 token(默认关)。
                    var frugalOn by remember { mutableStateOf(KVUtils.isFrugalPerceptionMode()) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconBubble(Icons.Filled.Bolt, PrimaryColor)
                        Spacer(Modifier.width(OctopusSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "省流感知模式",
                                color = TextPrimary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (frugalOn) "开 · 树够用时用文字替代截图,省 token" else "关 · 每轮走 vision 截图(更准)",
                                fontSize = OctopusType.caption, color = TextMuted,
                            )
                        }
                        Switch(
                            checked = frugalOn,
                            onCheckedChange = { on ->
                                frugalOn = on
                                KVUtils.setFrugalPerceptionMode(on)
                            },
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_advanced_hint), color = TextMuted, fontSize = OctopusType.caption, lineHeight = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
                    }
                }
            }
        }

        // ── 渠道：内联显示各渠道状态 ──
        item {
            val channelNames = listOf(
                stringResource(R.string.channel_name_dingtalk),
                stringResource(R.string.channel_name_feishu),
                stringResource(R.string.channel_name_qq),
                stringResource(R.string.channel_name_discord),
                stringResource(R.string.channel_name_telegram),
                stringResource(R.string.channel_name_wechat),
            )
            val cfg = remember(refreshTick) {
                listOf(
                    KVUtils.getDingtalkAppKey().isNotEmpty() && KVUtils.getDingtalkAppSecret().isNotEmpty(),
                    KVUtils.getFeishuAppId().isNotEmpty() && KVUtils.getFeishuAppSecret().isNotEmpty(),
                    KVUtils.getQqAppId().isNotEmpty() && KVUtils.getQqAppSecret().isNotEmpty(),
                    KVUtils.getDiscordBotToken().isNotEmpty(),
                    KVUtils.getTelegramBotToken().isNotEmpty(),
                    KVUtils.getWechatBotToken().isNotEmpty(),
                )
            }
            SettingsCard(stringResource(R.string.settings_section_channels), Icons.Filled.Notifications, compact = true, onClick = {
                context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.ChannelsActivity::class.java))
            }) {
                // 摘要
                SettingsRow(
                    Icons.Filled.Notifications,
                    stringResource(R.string.settings_section_channels),
                    "${cfg.count { it }}/6",
                    trailing = if (cfg.any { it }) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured),
                )
                Spacer(Modifier.height(OctopusSpacing.sm))
                // 各渠道状态点
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
                ) {
                    channelNames.forEachIndexed { i, name ->
                        val connected = cfg[i]
                        Surface(
                            shape = OctopusShape.small,
                            color = if (connected) SuccessColor.copy(alpha = 0.10f) else SurfaceVariantColor,
                            border = BorderStroke(1.dp, if (connected) SuccessColor.copy(alpha = 0.20f) else BorderColor.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Box(modifier = Modifier.size(5.dp).background(
                                    if (connected) SuccessColor else TextMuted,
                                    CircleShape,
                                ))
                                Spacer(Modifier.width(OctopusSpacing.xs))
                                Text(name, fontSize = OctopusType.tag, color = if (connected) TextPrimary else TextMuted, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_appearance), Icons.Filled.LightMode, compact = true) {
                var themeMode: Boolean? by remember { mutableStateOf(KVUtils.getThemeMode()) }
                val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                val isLight = themeMode ?: !systemDark

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconBubble(Icons.Filled.LightMode, PrimaryColor)
                    Spacer(Modifier.width(OctopusSpacing.md))
                    // 命名成「深色模式」并让开关=开→深色(直觉一致),不再一直挂着「明亮模式」
                    Text(
                        stringResource(R.string.settings_dark_mode),
                        color = TextPrimary, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = !isLight,
                        onCheckedChange = { dark ->
                            val lightVal = !dark
                            KVUtils.setThemeMode(lightVal)
                            themeMode = lightVal
                            OctopusColors.isLight = lightVal
                        },
                    )
                }
                val modeText = if (themeMode == null) {
                    stringResource(R.string.settings_theme_follow_system)
                } else if (themeMode == true) {
                    stringResource(R.string.settings_theme_light)
                } else {
                    stringResource(R.string.settings_theme_dark)
                }
                Spacer(Modifier.height(OctopusSpacing.sm))
                Text(
                    modeText,
                    fontSize = OctopusType.caption,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 44.dp),
                )
            }
        }

        // ── 高级功能:市场重构分流 —— 自我优化 / 信任中心 / 记忆 ──
        item {
            SettingsCard(stringResource(R.string.feat_section_advanced), Icons.Filled.TrendingUp, compact = true) {
                ClickableSettingsRow(
                    Icons.Filled.TrendingUp,
                    stringResource(R.string.feat_evolution),
                    stringResource(R.string.feat_evolution_desc),
                ) {
                    context.startActivity(Intent(context, EvolutionActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Shield,
                    stringResource(R.string.feat_trust),
                    stringResource(R.string.feat_trust_desc),
                ) {
                    context.startActivity(Intent(context, TrustCenterActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Psychology,
                    stringResource(R.string.feat_memory),
                    stringResource(R.string.feat_memory_desc),
                ) {
                    context.startActivity(Intent(context, MemoryActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Api,
                    "MCP Server",
                    "外部工具协议服务器(Model Context Protocol)",
                ) {
                    context.startActivity(Intent(context, McpServersActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Face,
                    "角色卡",
                    "自定义 AI 人设性格/开场白/说话风格",
                ) {
                    context.startActivity(Intent(context, PersonasActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.AccountTree,
                    "工作流",
                    "多步骤编排（工具+Prompt+条件）",
                ) {
                    context.startActivity(Intent(context, WorkflowsActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Folder,
                    "文件管理",
                    "浏览 /sdcard 文件系统(需 Shizuku)",
                ) {
                    context.startActivity(Intent(context, FileManagerActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Mic,
                    stringResource(R.string.wakeword_feature_title),
                    stringResource(R.string.wakeword_feature_desc),
                ) {
                    context.startActivity(Intent(context, WakeWordSettingsActivity::class.java))
                }
                SettingsDivider()
                ClickableSettingsRow(
                    Icons.Filled.Terminal,
                    "Linux 容器",
                    "PRoot 沙箱(Alpine 内置 / Ubuntu 需下载)",
                ) {
                    context.startActivity(Intent(context, com.apk.claw.android.ui.featurescreens.LinuxSandboxSettingsActivity::class.java))
                }
            }
        }

        // ── 版本号 + 点此在线检查更新 ──
        item {
            val updCtx = LocalContext.current
            var checking by remember { mutableStateOf(false) }
            Text(
                if (checking) {
                    "检查更新中…"
                } else {
                    stringResource(R.string.settings_version_template, BuildConfig.VERSION_NAME) + " · 点此检查更新"
                },
                fontSize = OctopusType.caption, color = TextMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !checking) {
                        checking = true
                        scope.launch {
                            val r = AppUpdater.check()
                            checking = false
                            when (r) {
                                is AppUpdater.CheckResult.UpToDate ->
                                    Toast.makeText(updCtx, "已是最新版本", Toast.LENGTH_SHORT).show()
                                is AppUpdater.CheckResult.Error ->
                                    Toast.makeText(updCtx, r.message, Toast.LENGTH_LONG).show()
                                // 有新版:弹窗由根部 AppUpdateHost 自动弹出
                                is AppUpdater.CheckResult.Available -> Unit
                            }
                        }
                    }
                    .padding(vertical = OctopusSpacing.sm),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── 权限逐项行（未授权可点击跳转）──────────────────────────

@Composable
private fun PermissionItemRow(permission: PermissionUi, onClick: () -> Unit) {
    val clickable = !permission.ok
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = OctopusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            permission.icon,
            contentDescription = null,
            tint = if (permission.ok) SuccessColor else TextMuted,
            modifier = Modifier.size(OctopusIconSize.small),
        )
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(
            permission.name,
            fontSize = OctopusType.label,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (clickable) {
            Text(
                    stringResource(R.string.settings_go_setup),
                    fontSize = OctopusType.tag,
                color = PrimaryColor,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(OctopusIconSize.small),
            )
        } else {
            Surface(
                shape = OctopusShape.small,
                color = SuccessColor.copy(alpha = 0.12f),
            ) {
                Text(
                    stringResource(R.string.settings_status_ok),
                    modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                    fontSize = OctopusType.micro,
                    color = SuccessColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Header / Summary ──────────────────────────────────

@Composable
private fun SettingsHeader(readyCount: Int, lanAddr: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_title),
            fontSize = OctopusType.headline,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            letterSpacing = 0.sp,
        )
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(stringResource(R.string.settings_subtitle), color = TextMuted, fontSize = OctopusType.label, lineHeight = 16.sp, maxLines = 2)
        Spacer(Modifier.height(OctopusSpacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            HeroMetric(
                label = stringResource(R.string.settings_metric_permissions),
                value = "$readyCount/6",
                ok = readyCount >= 5,
                modifier = Modifier.weight(1f),
            )
            HeroMetric(
                label = stringResource(R.string.settings_metric_lan),
                value = if (lanAddr == null) stringResource(R.string.status_not_connected) else stringResource(R.string.status_online),
                ok = lanAddr != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PermissionSummaryRow(readyCount: Int, perms: List<PermissionUi>) {
    val missing = perms.filterNot { it.ok }.take(3).joinToString(" · ") { it.name }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBubble(Icons.Filled.Shield, if (readyCount >= 5) SuccessColor else WarningColor)
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text("$readyCount/6", color = TextPrimary, fontSize = OctopusType.title, fontWeight = FontWeight.Bold)
            Text(
                if (missing.isBlank()) stringResource(R.string.status_online) else missing,
                color = TextMuted,
                fontSize = OctopusType.caption,
                lineHeight = 15.sp,
                maxLines = 1,
            )
        }
        Surface(
            shape = OctopusShape.medium,
            color = if (readyCount >= 5) SuccessColor.copy(alpha = 0.12f) else WarningColor.copy(alpha = 0.11f),
        ) {
            Text(
                if (readyCount >= 5) stringResource(R.string.settings_status_ok) else stringResource(R.string.settings_status_set),
                modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                color = if (readyCount >= 5) SuccessColor else WarningColor,
                fontSize = OctopusType.tag,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    val shape = OctopusShape.medium
    val borderColor = OctopusBackground.cardBorder
    Box(
        modifier = modifier
            .clip(shape)
            .background(OctopusBackground.cardSurface, shape)
            .border(1.dp, borderColor, shape),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            Text(label, color = TextMuted, fontSize = OctopusType.tag, maxLines = 1)
            Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            value,
            color = when {
                    ok -> SuccessColor
                    value.contains(stringResource(R.string.status_not_configured)) -> WarningColor
                    else -> TextSecondary
                },
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

// ── 通用组件 ──────────────────────────────────────────

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBubble(icon, PrimaryColor)
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 17.sp, maxLines = 2)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(subtitle, fontSize = OctopusType.caption, color = TextMuted, lineHeight = 15.sp, maxLines = 2)
        }
        trailing?.let {
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(it, color = if (it == stringResource(R.string.status_not_configured)) WarningColor else SuccessColor, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
    }
}

@Composable
private fun ClickableSettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).tvFocusable().padding(vertical = OctopusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, PrimaryColor)
        Spacer(Modifier.width(OctopusSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 17.sp, maxLines = 2)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(subtitle, fontSize = OctopusType.caption, color = TextMuted, lineHeight = 15.sp, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
    }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    Surface(shape = OctopusShape.small, color = tint) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.padding(6.dp).size(OctopusIconSize.small))
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(OctopusSpacing.md))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderColor.copy(alpha = 0.65f)))
    Spacer(Modifier.height(OctopusSpacing.md))
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = OctopusShape.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OctopusBackground.cardSurface, shape)
            .border(1.dp, OctopusBackground.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).tvFocusable() else Modifier),
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
                Text(title, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold, color = TextSecondary, letterSpacing = 0.sp, lineHeight = 16.sp)
            }
            Spacer(modifier = Modifier.height(if (compact) OctopusSpacing.sm else OctopusSpacing.md))
            content()
        }
    }
}
