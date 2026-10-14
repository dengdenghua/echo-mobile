@file:Suppress("TooManyFunctions")   // 信任中心屏由多个小卡片 composable 组成,加操作经验卡后达阈值

package com.apk.claw.android.ui.featurescreens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.EvolutionMetrics
import com.apk.claw.android.octopus_mobile.UsageStats
import com.apk.claw.android.octopus_mobile.InteractionLedger
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.KnowledgeLocal
import com.apk.claw.android.octopus_mobile.KnowledgeSync
import okhttp3.OkHttpClient
import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import com.apk.claw.android.octopus_mobile.SetupReadiness
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.octopus_mobile.safety.PermissionMode
import com.apk.claw.android.octopus_mobile.safety.PermissionModeManager
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.octopus_mobile.proactive.ProactiveRuleEngine
import com.apk.claw.android.ui.compose.theme.EchoFieldColors

class TrustCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { TrustCenterScreen(onBack = { finish() }) }
    }
}

private fun deviceModel(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() }

private fun batteryPct(c: Context): Int = runCatching {
    (c.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
        ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
}.getOrDefault(-1)

private fun lanIp(): String = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress ?: "—"
}.getOrDefault("—")

private fun openIntent(c: Context, action: String, withPkg: Boolean = false) {
    runCatching {
        val i = Intent(action)
        if (withPkg) i.data = Uri.parse("package:" + c.packageName)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }
}

@Composable
fun TrustCenterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val a11y = remember(tick) { ClawAccessibilityService.isRunning() }
    val overlay = remember(tick) { runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false) }
    val battery = remember(tick) {
        runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        }.getOrDefault(false)
    }
    val notif = remember(tick) { runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false) }
    val shizuku = remember(tick) { runCatching { ShizukuManager.isAvailable() }.getOrDefault(false) }
    val storage = remember(tick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) true
        else androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val lanAddr = remember(tick) { runCatching { ConfigServerManager.getAddress() }.getOrNull() }
    val targetLabel = remember(tick) { ControlTarget.label() }
    val hasReceiveSms = remember(tick) {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var smsOtpOn by remember(tick) { mutableStateOf(ProactiveRuleEngine.isGloballyEnabled() && hasReceiveSms) }
    val smsPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { ProactiveRuleEngine.setGloballyEnabled(true); smsOtpOn = true }
    }
    var lanOn by remember(tick) { mutableStateOf(KVUtils.isLanControlEnabled()) }
    var advOn by remember(tick) { mutableStateOf(KVUtils.isAdvancedAutomationMode()) }
    var remoteHi by remember(tick) { mutableStateOf(KVUtils.isRemoteHighRiskAllowed()) }
    var dryRunOn by remember(tick) { mutableStateOf(KVUtils.isDryRunMode()) }

    FeatureScaffold(title = stringResource(R.string.trustcenter_title), onBack = onBack) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.trustcenter_description),
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val readiness = remember(tick) { SetupReadiness.check(ctx) }
            ReadinessCard(readiness)

            FSectionTitle(stringResource(R.string.device_this_device))
            val batteryLevel = remember(tick) { batteryPct(ctx) }
            val ip = remember(tick) { lanIp() }
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Smartphone,
                        contentDescription = null,
                        tint = FPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deviceModel(), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Android ${Build.VERSION.RELEASE}", color = FMuted, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                Icons.Outlined.Lan,
                                contentDescription = stringResource(R.string.tc_lan_ip),
                                tint = FMuted,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(ip, color = FMuted, fontSize = 12.sp)
                        }
                    }
                    if (batteryLevel in 0..100) {
                        val batteryColor = if (batteryLevel <= LOW_BATTERY_PCT) FWarning else FSub
                        Icon(
                            Icons.Outlined.BatteryStd,
                            contentDescription = stringResource(R.string.tc_battery),
                            tint = batteryColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("$batteryLevel%", fontSize = 12.sp, color = batteryColor)
                    }
                }
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_capabilities))
            CapabilityRow(
                Icons.Outlined.AccessibilityNew,
                stringResource(R.string.home_card_accessibility_title),
                stringResource(R.string.trustcenter_capability_accessibility_desc),
                a11y,
            ) {
                openIntent(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            CapabilityRow(
                Icons.Outlined.Layers,
                stringResource(R.string.perm_overlay),
                stringResource(R.string.trustcenter_capability_overlay_desc),
                overlay,
            ) {
                openIntent(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPkg = true)
            }
            CapabilityRow(
                Icons.Outlined.BatteryChargingFull,
                stringResource(R.string.trustcenter_capability_battery),
                stringResource(R.string.trustcenter_capability_battery_desc),
                battery,
            ) {
                openIntent(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPkg = true)
            }
            CapabilityRow(
                Icons.Outlined.Notifications,
                stringResource(R.string.ctrl_notifications),
                stringResource(R.string.trustcenter_capability_notification_desc),
                notif,
            ) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }
            CapabilityRow(
                Icons.Outlined.Bolt,
                stringResource(R.string.trustcenter_capability_shizuku),
                stringResource(R.string.trustcenter_capability_shizuku_desc),
                shizuku,
            ) {
                runCatching { ShizukuManager.requestPermission() }
            }
            // Android 10 起分区存储无需授权，不列出（与就绪度、设置页的计数口径一致）
            if (SetupReadiness.storageNeedsGrant()) {
                CapabilityRow(
                    Icons.Outlined.Storage,
                    stringResource(R.string.perm_storage),
                    stringResource(R.string.trustcenter_capability_storage_desc),
                    storage,
                ) {
                    openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
                }
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_lannetwork))
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.trustcenter_lan_allow_toggle), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.trustcenter_lan_allow_desc),
                            color = FMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    Switch(
                        checked = lanOn,
                        onCheckedChange = { lanOn = it; KVUtils.setLanControlEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = FSuccess, checkedThumbColor = FOnPrimary),
                    )
                }
            }
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trustcenter_lan_discoverable_label), color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(lanAddr ?: stringResource(R.string.trustcenter_lan_not_started), color = if (lanAddr != null) FSuccess else FMuted, fontSize = 12.sp)
                }
            }

            FSectionTitle(stringResource(R.string.tc_section_permission_mode))
            // 当前模式标签
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.tc_current_mode),
                        color = FText,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    val modeLabel = stringResource(
                        if (advOn) R.string.tc_mode_full_power else R.string.tc_mode_approval,
                    )
                    val modeColor = if (advOn) FWarning else FSuccess
                    FPill(modeLabel, modeColor)
                }
            }
            // 审批模式说明
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tc_mode_approval_title),
                            color = FText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.tc_mode_approval_desc),
                            color = FMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = !advOn,
                        onCheckedChange = { if (it) { PermissionModeManager.switchMode(PermissionMode.APPROVAL, "user_switch_trustcenter"); advOn = false } },
                        colors = SwitchDefaults.colors(checkedTrackColor = FSuccess, checkedThumbColor = FOnPrimary),
                    )
                }
            }
            // 完全权限模式说明
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tc_mode_full_title),
                            color = FText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FWarningNote(stringResource(R.string.tc_mode_full_desc))
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = advOn,
                        onCheckedChange = { if (it) { PermissionModeManager.switchMode(PermissionMode.FULL_POWER, "user_switch_trustcenter"); advOn = true } },
                        colors = SwitchDefaults.colors(checkedTrackColor = FWarning, checkedThumbColor = FOnPrimary),
                    )
                }
            }

            // 演示/只读模式:安全预览 Agent 会怎么做(改动型全跳过,只读照常)。fail-safe。
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tc_dry_run_title),
                            color = FText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.tc_dry_run_desc),
                            color = FMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = dryRunOn,
                        onCheckedChange = { dryRunOn = it; KVUtils.setDryRunMode(it) },
                    )
                }
            }

            // 允许远程来源执行高危工具(提到首屏便于发现;ChannelAclActivity 也有同一开关)
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tc_remote_high_risk_title),
                            color = FText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FWarningNote(
                            stringResource(R.string.tc_remote_high_risk_desc),
                            color = if (remoteHi) FWarning else FMuted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = remoteHi,
                        onCheckedChange = {
                            remoteHi = it
                            KVUtils.setRemoteHighRiskAllowed(it)
                            // 高影响开关:写审计,留可追溯痕迹(与 ChannelAclActivity 一致)。
                            ToolAuditLog.recordSecuritySetting("允许远程来源执行高危工具", it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = FWarning, checkedThumbColor = FOnPrimary),
                    )
                }
            }

            // 验证码短信自动复制
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tc_sms_otp_title),
                            color = FText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.tc_sms_otp_desc),
                            color = FMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        FWarningNote(
                            stringResource(R.string.tc_sms_otp_warning),
                            color = FMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = smsOtpOn,
                        onCheckedChange = { want ->
                            if (want) {
                                if (hasReceiveSms) {
                                    ProactiveRuleEngine.setGloballyEnabled(true); smsOtpOn = true
                                } else {
                                    smsPermLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
                                }
                            } else {
                                ProactiveRuleEngine.setGloballyEnabled(false); smsOtpOn = false
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = FPrimary, checkedThumbColor = FOnPrimary),
                    )
                }
            }

            FSectionTitle(stringResource(R.string.tc_section_access_audit))
            NavRow(
                title = stringResource(R.string.tc_nav_acl_title),
                desc = stringResource(R.string.tc_nav_acl_desc),
            ) { ctx.startActivity(Intent(ctx, ChannelAclActivity::class.java)) }
            NavRow(
                title = stringResource(R.string.tc_nav_audit_title),
                desc = stringResource(R.string.tc_nav_audit_desc),
            ) { ctx.startActivity(Intent(ctx, AuditLogActivity::class.java)) }

            FSectionTitle(stringResource(R.string.tc_section_evolution))
            EvolutionMetricsCard(tick) { tick++ }

            FSectionTitle(stringResource(R.string.tc_section_lessons))
            InteractionLessonsCard(tick) { tick++ }

            FSectionTitle(stringResource(R.string.tc_section_memory))
            UserMemoryCard(tick) { tick++ }

            FSectionTitle(stringResource(R.string.tc_section_backup))
            KnowledgeBackupCard { tick++ }

            FSectionTitle(stringResource(R.string.trustcenter_section_target))
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trustcenter_target_label), color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    FPill(targetLabel, if (ControlTarget.isRemote()) FWarning else FPrimary)
                }
            }

            // 一键收回（可程序化的部分：关闭局域网广播 + 目标改回本机）
            Spacer(modifier = Modifier.height(8.dp))
            FCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        KVUtils.setLanControlEnabled(false)
                        lanOn = false
                        // 一键收回同时切回审批模式，恢复安全默认。
                        PermissionModeManager.switchMode(PermissionMode.APPROVAL, "user_revoke_all")
                        advOn = false
                        ControlTarget.setLocal()
                        tick++
                    },
                ) {
                    Icon(
                        Icons.Outlined.Block,
                        contentDescription = null,
                        tint = FWarning,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.trustcenter_action_revoke), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.trustcenter_action_revoke_desc),
                            color = FMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.trustcenter_instruction_manual_disable),
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CapabilityRow(icon: ImageVector, name: String, desc: String, granted: Boolean, onManage: () -> Unit) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onManage)) {
            Icon(icon, contentDescription = null, tint = if (granted) FSuccess else FSub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = FMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(8.dp))
            FPill(if (granted) stringResource(R.string.trust_center_granted) else stringResource(R.string.trust_center_not_granted), if (granted) FSuccess else FMuted)
            FChevron()
        }
    }
}

@Composable
private fun ReadinessCard(r: SetupReadiness.Result) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tc_readiness_title),
                    color = FText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.tc_readiness_count, r.readyCount, r.total),
                    color = FMuted,
                    fontSize = 12.sp,
                )
            }
            val pctColor = when {
                !r.isReady -> FWarning
                r.percent == 100 -> FSuccess
                else -> FPrimary
            }
            FPill("${r.percent}%", pctColor)
        }
        if (r.missingCritical.isNotEmpty()) {
            FWarningNote(
                stringResource(
                    R.string.tc_readiness_missing,
                    r.missingCritical.joinToString(stringResource(R.string.tc_list_separator)) { it.label },
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                stringResource(R.string.tc_readiness_ok),
                color = FSuccess, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NavRow(title: String, desc: String, onClick: () -> Unit) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = FMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            FChevron()
        }
    }
}

private const val PCT_SCALE = 100.0

/** At or below this battery level the device card shows the level in warning amber. */
private const val LOW_BATTERY_PCT = 20

/** 自进化引擎(反射/免疫/经验账本)的实测效果 —— 数据来自 [EvolutionMetrics]。 */
@Composable
@Suppress("LongMethod")   // Compose 卡片:五行指标 + 空状态 + 重置,声明式 UI 天然偏长
private fun EvolutionMetricsCard(tick: Int, onReset: () -> Unit) {
    val hit = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.REFLEX_HIT) }
    val miss = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.REFLEX_MISS) }
    val reflexRate = remember(tick) {
        String.format(java.util.Locale.US, "%.1f%%", EvolutionMetrics.reflexHitRate() * PCT_SCALE)
    }
    val immuneCall = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.IMMUNE_CALL) }
    val immuneWarn = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.IMMUNE_WARN) }
    val immuneRate = remember(tick) {
        String.format(java.util.Locale.US, "%.1f%%", EvolutionMetrics.immuneWarnRate() * PCT_SCALE)
    }
    val ledgerErr = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.LEDGER_ERROR) }
    val ledgerRepair = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.LEDGER_REPAIR) }
    val injected = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.MITIGATION_INJECTED) }
    val failover = remember(tick) { EvolutionMetrics.get(EvolutionMetrics.MODEL_FAILOVER) }
    val usageTasks = remember(tick) { UsageStats.tasks() }
    val usageTokens = remember(tick) { UsageStats.tokens() }
    val hasData = hit + miss + immuneCall + ledgerErr + usageTasks > 0L
    FCard {
        MetricRow(
            Icons.Outlined.Bolt,
            stringResource(R.string.tc_metric_reflex),
            reflexRate,
            stringResource(R.string.tc_metric_reflex_sub, hit, hit, hit + miss),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow(
            Icons.Outlined.Shield,
            stringResource(R.string.tc_metric_immune),
            immuneRate,
            stringResource(R.string.tc_metric_immune_sub, immuneWarn, immuneCall),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow(
            Icons.AutoMirrored.Outlined.MenuBook,
            stringResource(R.string.tc_metric_ledger),
            stringResource(R.string.tc_metric_ledger_value, ledgerErr),
            stringResource(R.string.tc_metric_ledger_sub, ledgerRepair, injected),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow(
            Icons.Outlined.AltRoute,
            stringResource(R.string.tc_metric_failover),
            stringResource(R.string.tc_metric_failover_value, failover),
            stringResource(R.string.tc_metric_failover_sub),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow(
            Icons.Outlined.BarChart,
            stringResource(R.string.tc_metric_usage),
            stringResource(R.string.tc_metric_usage_value, usageTasks),
            stringResource(R.string.tc_metric_usage_sub, usageTokens),
        )
        if (!hasData) {
            Text(
                stringResource(R.string.tc_metric_empty),
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Text(
                stringResource(R.string.tc_metric_reset),
                color = FWarning,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { EvolutionMetrics.reset(); EvolutionMetrics.persist(); UsageStats.reset(); onReset() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MetricRow(icon: ImageVector, label: String, value: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            FIconLabel(icon, label)
            Text(sub, color = FMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 22.dp))
        }
        Spacer(Modifier.width(8.dp))
        FPill(value, FPrimary)
    }
}

/**
 * 这台设备的操作经验 —— 自动学到的规避(来自 [InteractionLedger] GUI 失败)+ 用户手动教的规矩。
 * 用户可直接「教它一条规矩」(最高优先注入),让 Agent 行为可纠正、可控。
 */
@Composable
@Suppress("LongMethod")   // Compose 卡片:经验列表 + 教规矩入口 + 弹窗,声明式 UI 天然偏长
private fun InteractionLessonsCard(tick: Int, onReset: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val lessons = remember(tick) { InteractionLedger.snapshot() }
    val hasLearned = lessons.any { !it.manual }
    FCard {
        if (lessons.isEmpty()) {
            Text(stringResource(R.string.tc_lessons_empty), color = FMuted, fontSize = 12.sp, lineHeight = 17.sp)
        } else {
            lessons.forEachIndexed { i, l ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        val titleColor = if (l.manual && !l.enabled) FMuted else FText
                        if (l.manual) FIconLabel(Icons.Outlined.PushPin, l.title, color = titleColor)
                        else Text(l.title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (l.mitigation.isNotBlank()) {
                            Text(
                                stringResource(R.string.tc_lessons_mitigation, l.mitigation),
                                color = FMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        } else if (l.manual) {
                            Text(
                                stringResource(
                                    if (l.enabled) R.string.tc_lessons_rule_on else R.string.tc_lessons_rule_off,
                                ),
                                color = FMuted, fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (l.manual) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    if (l.enabled) R.string.tc_action_disable else R.string.tc_action_enable,
                                ),
                                color = FPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable {
                                        InteractionLedger.setManualRuleEnabled(l.title, !l.enabled)
                                        onReset()
                                    }
                                    .padding(4.dp),
                            )
                            Text(
                                stringResource(R.string.common_delete), color = FWarning, fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { InteractionLedger.removeManualRule(l.title); onReset() }
                                    .padding(4.dp),
                            )
                        }
                    } else {
                        FPill("×${l.count}", FPrimary)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FTextAction(Icons.Outlined.Add, stringResource(R.string.tc_lessons_add)) { draft = ""; showAdd = true }
            if (hasLearned) {
                Text(
                    stringResource(R.string.tc_lessons_reset),
                    color = FWarning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { InteractionLedger.clearLessons(); onReset() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tc_lessons_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.tc_lessons_dialog_body),
                        color = FMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.tc_lessons_dialog_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = EchoFieldColors.outlined(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    InteractionLedger.addManualRule(draft); showAdd = false; onReset()
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Agent 记住的关于用户的事(跨会话记忆)—— 数据来自 [MemoryStore](KVUtils 固定 key,任意实例共享)。
 * 这些是隐私敏感的 PII(偏好/事实/上下文),用户应看得见、删得掉、也能主动「记一条」。只存本机。
 */
@Composable
@Suppress("LongMethod")   // Compose 卡片:记忆列表 + 记一条入口 + 弹窗,声明式 UI 天然偏长
private fun UserMemoryCard(tick: Int, onReset: () -> Unit) {
    val store = remember { MemoryStore() }
    var showAdd by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val memories = remember(tick) { store.getMemories() }
    FCard {
        if (memories.isEmpty()) {
            Text(stringResource(R.string.tc_memory_empty), color = FMuted, fontSize = 12.sp, lineHeight = 17.sp)
        } else {
            memories.forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                val label = stringResource(
                    when (m.type) {
                        MemoryStore.MemoryType.PREFERENCE -> R.string.tc_memory_type_preference
                        MemoryStore.MemoryType.FACT -> R.string.tc_memory_type_fact
                        MemoryStore.MemoryType.CONTEXT -> R.string.tc_memory_type_context
                    },
                )
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.content, color = FText, fontSize = 13.sp, lineHeight = 17.sp)
                        Text(stringResource(R.string.tc_memory_meta, label, m.source), color = FMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.common_delete), color = FWarning, fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { store.removeMemory(m.id); onReset() }
                            .padding(4.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FTextAction(Icons.Outlined.Add, stringResource(R.string.tc_memory_add)) { draft = ""; showAdd = true }
            if (memories.isNotEmpty()) {
                Text(
                    stringResource(R.string.tc_memory_clear),
                    color = FWarning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { store.clearAll(); onReset() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tc_memory_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.tc_memory_dialog_body),
                        color = FMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.tc_memory_dialog_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = EchoFieldColors.outlined(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { store.addUserFact(draft); showAdd = false; onReset() }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * 知识备份/迁移 —— 把用户规矩([InteractionLedger] manual)+ 记忆([MemoryStore])做成
 * [KnowledgeBundle] 文本,三种模态备份/迁移:剪贴板、文件、局域网设备拉取。采集/恢复统一走
 * [KnowledgeLocal]。只在本机/设备间迁移,不上传服务器。
 */
@Composable
@Suppress("LongMethod")   // Compose 卡片:说明 + 剪贴板/文件/局域网三模态按钮,声明式偏长
private fun KnowledgeBackupCard(onChanged: () -> Unit) {
    val context = LocalContext.current
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    FCard {
        Text(stringResource(R.string.tc_backup_desc), color = FMuted, fontSize = 12.sp, lineHeight = 17.sp)
        // ── 剪贴板 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.tc_backup_export_clipboard),
                color = FPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("octopus-knowledge", KnowledgeLocal.gather()))
                        toast(context.getString(R.string.tc_backup_copied))
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.tc_backup_import_clipboard),
                color = FPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                        val r = KnowledgeLocal.restore(text)
                        toast(
                            if (r == null) context.getString(R.string.tc_backup_clipboard_invalid)
                            else context.getString(
                                R.string.tc_backup_imported,
                                r.first,
                                r.second,
                            ),
                        )
                        if (r != null) onChanged()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        // ── 文件(持久归档,重启不丢,可经任意渠道分享)──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.tc_backup_export_file),
                color = FPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val f = java.io.File(context.getExternalFilesDir(null), "octopus-knowledge.json")
                        val ok = runCatching { f.writeText(KnowledgeLocal.gather()) }.isSuccess
                        toast(
                            if (ok) context.getString(R.string.tc_backup_exported_file, f.absolutePath)
                            else context.getString(R.string.tc_backup_export_failed),
                        )
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.tc_backup_import_file),
                color = FPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val f = java.io.File(context.getExternalFilesDir(null), "octopus-knowledge.json")
                        val text = runCatching { f.readText() }.getOrDefault("")
                        val r = KnowledgeLocal.restore(text)
                        toast(
                            if (r == null) context.getString(R.string.tc_backup_file_invalid) else context.getString(
                                R.string.tc_backup_imported_file,
                                r.first,
                                r.second,
                            ),
                        )
                        if (r != null) onChanged()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        // ── 局域网设备拉取 ──
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            FTextAction(Icons.Outlined.Download, stringResource(R.string.tc_backup_pull_lan)) {
                        Thread {
                            val target = ClawApplication.instance.deviceRegistry.getOnlineDevices()
                                .firstOrNull { it.authToken.isNotBlank() }
                            val msg = if (target == null) {
                                context.getString(R.string.tc_backup_no_device)
                            } else {
                                val r = KnowledgeSync.pullFrom(target.getBaseUrl(), target.authToken, OkHttpClient())
                                if (r == null) {
                                    context.getString(R.string.tc_backup_pull_failed, target.deviceName)
                                } else {
                                    context.getString(R.string.tc_backup_pulled, target.deviceName, r.first, r.second)
                                }
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                onChanged()
                            }
                        }.start()
            }
        }
    }
}

/** A text action with a leading outlined icon (replaces "＋ …" / "⬇ …" glyph prefixes). */
@Composable
private fun FTextAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = FPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
