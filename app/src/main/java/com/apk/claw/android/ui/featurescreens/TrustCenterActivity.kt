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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.ui.graphics.Color
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
                    Icon(Icons.Filled.Smartphone, contentDescription = null, tint = FPrimary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deviceModel(), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Android ${Build.VERSION.RELEASE}  ·  🌐 $ip", color = FMuted, fontSize = 11.sp)
                    }
                    if (batteryLevel in 0..100) {
                        Text("🔋 $batteryLevel%", fontSize = 12.sp, color = if (batteryLevel <= 20) FWarning else FSub)
                    }
                }
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_capabilities))
            CapabilityRow(Icons.Filled.AccessibilityNew, stringResource(R.string.home_card_accessibility_title), stringResource(R.string.trustcenter_capability_accessibility_desc), a11y) {
                openIntent(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            CapabilityRow(Icons.Filled.Layers, stringResource(R.string.perm_overlay), stringResource(R.string.trustcenter_capability_overlay_desc), overlay) {
                openIntent(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPkg = true)
            }
            CapabilityRow(Icons.Filled.BatteryChargingFull, stringResource(R.string.trustcenter_capability_battery), stringResource(R.string.trustcenter_capability_battery_desc), battery) {
                openIntent(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPkg = true)
            }
            CapabilityRow(Icons.Filled.Notifications, stringResource(R.string.ctrl_notifications), stringResource(R.string.trustcenter_capability_notification_desc), notif) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }
            CapabilityRow(Icons.Filled.Bolt, stringResource(R.string.trustcenter_capability_shizuku), stringResource(R.string.trustcenter_capability_shizuku_desc), shizuku) {
                runCatching { ShizukuManager.requestPermission() }
            }
            CapabilityRow(Icons.Filled.Storage, stringResource(R.string.perm_storage), stringResource(R.string.trustcenter_capability_storage_desc), storage) {
                openIntent(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPkg = true)
            }

            FSectionTitle(stringResource(R.string.trustcenter_section_lannetwork))
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.trustcenter_lan_allow_toggle), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.trustcenter_lan_allow_desc), color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = lanOn,
                        onCheckedChange = { lanOn = it; KVUtils.setLanControlEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = FSuccess, checkedThumbColor = Color.White),
                    )
                }
            }
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trustcenter_lan_discoverable_label), color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(lanAddr ?: stringResource(R.string.trustcenter_lan_not_started), color = if (lanAddr != null) FSuccess else FMuted, fontSize = 12.sp)
                }
            }

            FSectionTitle("权限模式 · 设备用途分层")
            // 当前模式标签
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前模式", color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val modeLabel = if (advOn) "完全权限模式" else "审批模式"
                    val modeColor = if (advOn) FWarning else FSuccess
                    FPill(modeLabel, modeColor)
                }
            }
            // 审批模式说明
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("审批模式 · 日常主力机", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "安全优先。高危工具（发短信/发 Intent/装应用/文件读写删/浏览器执行 JS）" +
                                "调用时弹窗人工确认。来源闸门、路径沙箱、宪法法官全部开启。" +
                                "适合日常主力机。",
                            color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = !advOn,
                        onCheckedChange = { if (it) { PermissionModeManager.switchMode(PermissionMode.APPROVAL, "user_switch_trustcenter"); advOn = false } },
                        colors = SwitchDefaults.colors(checkedTrackColor = FSuccess, checkedThumbColor = Color.White),
                    )
                }
            }
            // 完全权限模式说明
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("完全权限模式 · 闲置/群控机", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "⚠️ 释放最大能力。母体、局域网、主动规则可无确认执行全部高危工具，" +
                                "文件工具不再限制在 /sdcard。会显著降低安全性——" +
                                "仅用于你完全掌控的闲置/专用自动化手机，日常主力机请勿开启。" +
                                "隐私扫描、审计日志、断路器三项不可关闭（防失控）。",
                            color = FWarning, fontSize = 10.sp, lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = advOn,
                        onCheckedChange = { if (it) { PermissionModeManager.switchMode(PermissionMode.FULL_POWER, "user_switch_trustcenter"); advOn = true } },
                        colors = SwitchDefaults.colors(checkedTrackColor = FWarning, checkedThumbColor = Color.White),
                    )
                }
            }

            // 演示/只读模式:安全预览 Agent 会怎么做(改动型全跳过,只读照常)。fail-safe。
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("演示 / 只读模式", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "开启后 Agent 能看屏、规划、走完流程,但所有会改动的操作(点按/输入/发消息/" +
                                "装应用/跑代码…)全部跳过、只演示不执行。安全预览它会怎么做——首次上手或不放心时用。",
                            color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
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
                        Text("允许远程来源执行高危工具", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "⚠️ 开启后，母体 WebSocket / 局域网 HTTP / 主动规则可无需确认执行" +
                                "发短信/装应用/文件读写删等高危工具。默认关闭（拦截），仅在你完全掌控的受控环境开启。",
                            color = if (remoteHi) FWarning else FMuted, fontSize = 10.sp, lineHeight = 14.sp,
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
                        colors = SwitchDefaults.colors(checkedTrackColor = FWarning, checkedThumbColor = Color.White),
                    )
                }
            }

            // 验证码短信自动复制
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("验证码短信自动复制", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "收到验证码短信时自动提取并复制到剪贴板。开启需授予「接收短信」权限，" +
                                "并会启用主动规则引擎（内建规则仅做剪贴板/通知，高危工具在主动路径已被拦截）。" +
                                "⚠️ 验证码会进入系统剪贴板，可能被其他应用读取——按需开启。",
                            color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
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
                        colors = SwitchDefaults.colors(checkedTrackColor = FPrimary, checkedThumbColor = Color.White),
                    )
                }
            }

            FSectionTitle("访问与审计")
            NavRow(
                title = "通道访问控制（ACL）",
                desc = "管理各聊天通道的授权发送者名单，开关访问控制与远程高危工具放行",
            ) { ctx.startActivity(Intent(ctx, ChannelAclActivity::class.java)) }
            NavRow(
                title = "操作审计日志",
                desc = "查看 Agent 最近的中/高危工具调用记录与拦截决策",
            ) { ctx.startActivity(Intent(ctx, AuditLogActivity::class.java)) }

            FSectionTitle("自进化引擎 · 实测效果")
            EvolutionMetricsCard(tick) { tick++ }

            FSectionTitle("操作经验 · 这台设备学到的")
            InteractionLessonsCard(tick) { tick++ }

            FSectionTitle("关于你的记忆 · Agent 记住的")
            UserMemoryCard(tick) { tick++ }

            FSectionTitle("知识备份 · 带走 Agent 的脑子")
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
                    Icon(Icons.Filled.Block, contentDescription = null, tint = FWarning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.trustcenter_action_revoke), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.trustcenter_action_revoke_desc), color = FMuted, fontSize = 10.sp)
                    }
                }
            }
            Text(
                stringResource(R.string.trustcenter_instruction_manual_disable),
                color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
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
                Text(desc, color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            FPill(if (granted) stringResource(R.string.trust_center_granted) else stringResource(R.string.trust_center_not_granted), if (granted) FSuccess else FMuted)
            Text("›", color = FMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun ReadinessCard(r: SetupReadiness.Result) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("准备就绪度", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${r.readyCount}/${r.total} 项已就绪", color = FMuted, fontSize = 10.sp)
            }
            val pctColor = when {
                !r.isReady -> FWarning
                r.percent == 100 -> FSuccess
                else -> FPrimary
            }
            FPill("${r.percent}%", pctColor)
        }
        if (r.missingCritical.isNotEmpty()) {
            Text(
                "⚠️ 核心前置缺失：" + r.missingCritical.joinToString("、") { it.label } +
                    "。补齐后 Agent 才能正常工作。",
                color = FWarning, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                "核心前置已齐备，可以开始使用。",
                color = FSuccess, fontSize = 10.sp,
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
                Text(desc, color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Text("›", color = FMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

private const val PCT_SCALE = 100.0

/** 自进化引擎(反射/免疫/经验账本)的实测效果 —— 数据来自 [EvolutionMetrics]。 */
@Composable
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
    val hasData = hit + miss + immuneCall + ledgerErr > 0L
    FCard {
        MetricRow("⚡ 反射快路径命中率", reflexRate, "省下 $hit 次完整 LLM（$hit/${hit + miss}）")
        Spacer(Modifier.height(8.dp))
        MetricRow("🛡️ 免疫系统告警率", immuneRate, "$immuneWarn 次告警 / $immuneCall 次预检")
        Spacer(Modifier.height(8.dp))
        MetricRow("📒 经验账本", "记 $ledgerErr", "修复 $ledgerRepair · 注入规避 $injected 次")
        Spacer(Modifier.height(8.dp))
        MetricRow("🔀 模型故障转移", "$failover 次", "主模型失败自动切备用(需在配置里填备用模型)")
        if (!hasData) {
            Text(
                "暂无数据——跑几个任务后这里会显示反射命中率、免疫告警率等硬指标。",
                color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Text(
                "重置统计", color = FWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { EvolutionMetrics.reset(); EvolutionMetrics.persist(); onReset() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = FText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
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
            Text(
                "还没有经验。点下方「教它一条规矩」直接告诉它怎么操作(如「打开淘宝先关弹窗」);" +
                    "跑自动化任务遇到的坑(找不到节点/弹窗遮挡/加载超时…)也会自动沉淀在这里,下次避开。",
                color = FMuted, fontSize = 11.sp, lineHeight = 15.sp,
            )
        } else {
            lessons.forEachIndexed { i, l ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            (if (l.manual) "📌 " else "") + l.title,
                            color = FText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (l.mitigation.isNotBlank()) {
                            Text("规避:${l.mitigation}", color = FMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        } else if (l.manual) {
                            Text("你定的规矩 · 优先级最高", color = FMuted, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (l.manual) {
                        Text(
                            "删除", color = FWarning, fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { InteractionLedger.removeManualRule(l.title); onReset() }
                                .padding(4.dp),
                        )
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
            Text(
                "＋ 教它一条规矩", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { draft = ""; showAdd = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (hasLearned) {
                Text(
                    "重置经验(保留规矩)", color = FWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
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
            title = { Text("教它一条操作规矩") },
            text = {
                Column {
                    Text(
                        "用一句话告诉 Agent 该怎么操作,会以最高优先级注入。例:打开淘宝先关弹窗再操作;发消息前先确认对象。",
                        color = FMuted, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("输入一条规矩…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    InteractionLedger.addManualRule(draft); showAdd = false; onReset()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
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
            Text(
                "Agent 还没记住关于你的事。它会在对话里留意你透露的偏好/事实(常用 App、饮食忌口、称呼、" +
                    "住址等)记下来;你也可以「记一条」直接告诉它。这些只存在本机,随时可删。",
                color = FMuted, fontSize = 11.sp, lineHeight = 15.sp,
            )
        } else {
            memories.forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                val label = when (m.type) {
                    MemoryStore.MemoryType.PREFERENCE -> "偏好"
                    MemoryStore.MemoryType.FACT -> "事实"
                    MemoryStore.MemoryType.CONTEXT -> "近期"
                }
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.content, color = FText, fontSize = 13.sp, lineHeight = 17.sp)
                        Text("$label · 来源 ${m.source}", color = FMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "删除", color = FWarning, fontSize = 11.sp,
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
            Text(
                "＋ 记一条", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { draft = ""; showAdd = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (memories.isNotEmpty()) {
                Text(
                    "清空记忆", color = FWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
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
            title = { Text("记一条关于你的事") },
            text = {
                Column {
                    Text(
                        "告诉 Agent 一条要长期记住的偏好或事实,后续任务会据此调整。例:我用饿了么点外卖;对花生过敏;称呼我老王。",
                        color = FMuted, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("输入一条…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { store.addUserFact(draft); showAdd = false; onReset() }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
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
        Text(
            "把你教的规矩 + Agent 记住的关于你的事备份/迁移:复制到剪贴板、存成文件、或从同账号" +
                "局域网设备拉取。只在本机/设备间流转,不上传任何服务器。",
            color = FMuted, fontSize = 11.sp, lineHeight = 15.sp,
        )
        // ── 剪贴板 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "导出到剪贴板", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("octopus-knowledge", KnowledgeLocal.gather()))
                        toast("知识包已复制到剪贴板")
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                "从剪贴板导入", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                        val r = KnowledgeLocal.restore(text)
                        toast(if (r == null) "剪贴板里不是有效的知识包" else "已导入 ${r.first} 条规矩 + ${r.second} 条记忆")
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
                "导出到文件", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val f = java.io.File(context.getExternalFilesDir(null), "octopus-knowledge.json")
                        val ok = runCatching { f.writeText(KnowledgeLocal.gather()) }.isSuccess
                        toast(if (ok) "已导出到文件:${f.absolutePath}" else "导出失败")
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                "从文件导入", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val f = java.io.File(context.getExternalFilesDir(null), "octopus-knowledge.json")
                        val text = runCatching { f.readText() }.getOrDefault("")
                        val r = KnowledgeLocal.restore(text)
                        toast(if (r == null) "未找到备份文件或格式非法" else "已从文件导入 ${r.first} 条规矩 + ${r.second} 条记忆")
                        if (r != null) onChanged()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        // ── 局域网设备拉取 ──
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                "⬇ 从局域网设备拉取", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        Thread {
                            val target = ClawApplication.instance.deviceRegistry.getOnlineDevices()
                                .firstOrNull { it.authToken.isNotBlank() }
                            val msg = if (target == null) {
                                "未发现已配对的在线局域网设备(先在设备里配对同账号设备)"
                            } else {
                                val r = KnowledgeSync.pullFrom(target.getBaseUrl(), target.authToken, OkHttpClient())
                                if (r == null) {
                                    "拉取失败:${target.deviceName} 无响应或数据非法"
                                } else {
                                    "已从 ${target.deviceName} 拉取 ${r.first} 条规矩 + ${r.second} 条记忆"
                                }
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                onChanged()
                            }
                        }.start()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
