package com.apk.claw.android.ui.featurescreens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.service.WakeWordService
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.wakeword.WakeWordSettings

/**
 * 唤醒词设置页 —— 用户开关 + 关键词 + 灵敏度 + 省电策略 + 隐私同意 + 状态显示。
 *
 * 关键交互:
 *  - 开启开关前必须先通过「常驻麦克风隐私告知」+ 运行时 RECORD_AUDIO 授权
 *  - 隐私同意存 [WakeWordSettings.setConsented],记录用户已知晓"麦克风将常驻"
 *  - 关键词 / 灵敏度 / 省电策略 变更时,若服务正在运行则自动重启服务以生效
 *  - 引擎类型不可切换(当前只有 demo),仅展示
 */
class WakeWordSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { WakeWordSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun WakeWordSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(WakeWordSettings.isEnabled()) }
    var consented by remember { mutableStateOf(WakeWordSettings.hasConsented()) }
    var keyword by remember { mutableStateOf(WakeWordSettings.getKeyword()) }
    var sensitivity by remember { mutableStateOf(WakeWordSettings.getSensitivity()) }
    var onlyCharging by remember { mutableStateOf(WakeWordSettings.isOnlyCharging()) }
    var onlyScreenOff by remember { mutableStateOf(WakeWordSettings.isOnlyScreenOff()) }
    var running by remember { mutableStateOf(WakeWordService.isRunning()) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    // 麦克风权限运行时申请
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // 权限已获,继续开启流程
            consented = true
            WakeWordSettings.setConsented(true)
            enabled = true
            WakeWordSettings.setEnabled(true)
            WakeWordService.start(ctx)
            running = true
        }
    }

    val onToggleEnabled: (Boolean) -> Unit = { wantOn ->
        if (wantOn) {
            // 开启前:先弹隐私同意 → 再申请麦克风权限 → 启动服务
            if (!consented) {
                showPrivacyDialog = true
            } else {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            enabled = false
            WakeWordSettings.setEnabled(false)
            WakeWordService.stop(ctx)
            running = false
        }
    }

    val onKeywordChange: (String) -> Unit = { newKw ->
        keyword = newKw
        WakeWordSettings.setKeyword(newKw)
        if (running) { WakeWordService.stop(ctx); WakeWordService.start(ctx) }
    }

    val onSensitivityChange: (WakeWordSettings.Sensitivity) -> Unit = { s ->
        sensitivity = s
        WakeWordSettings.setSensitivity(s)
        if (running) { WakeWordService.stop(ctx); WakeWordService.start(ctx) }
    }

    val onOnlyChargingChange: (Boolean) -> Unit = { v ->
        onlyCharging = v
        WakeWordSettings.setOnlyCharging(v)
        if (running) { WakeWordService.stop(ctx); WakeWordService.start(ctx) }
    }

    val onOnlyScreenOffChange: (Boolean) -> Unit = { v ->
        onlyScreenOff = v
        WakeWordSettings.setOnlyScreenOff(v)
        if (running) { WakeWordService.stop(ctx); WakeWordService.start(ctx) }
    }

    FeatureScaffold(title = stringResource(R.string.wakeword_feature_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            // ── 状态卡(总开关 + 运行状态)──
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (running) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = null,
                        tint = if (running) FSuccess else FMuted,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (running) "正在监听" else "已关闭",
                            color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (running) "说「$keyword」唤起助手" else "开启后可常驻监听唤醒词",
                            color = FMuted, fontSize = 11.sp,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onToggleEnabled)
                }
            }

            // ── 引擎说明 ──
            FCard {
                Text("引擎", color = FMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("响声触发(演示引擎)", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前为演示引擎,基于音量能量检测,任何超过阈值的响声(拍手 / 大声说话 / 撞击)都会触发。\n" +
                        "后续将接入 OpenWakeWord / Porcupine 等真正的关键词识别引擎。",
                    color = FSub, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }

            // ── 唤醒词 ──
            FCard {
                Text("唤醒词", color = FMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                KeywordChips(
                    current = keyword,
                    onChange = onKeywordChange,
                )
            }

            // ── 灵敏度 ──
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, contentDescription = null, tint = FSub, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("灵敏度", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                SensibilitySelector(current = sensitivity, onChange = onSensitivityChange)
            }

            // ── 省电策略 ──
            FCard {
                Text("省电策略", color = FMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "仅充电时监听",
                    subtitle = "未充电时停止监听,避免耗电",
                    checked = onlyCharging,
                    onChange = onOnlyChargingChange,
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    icon = Icons.Filled.VisibilityOff,
                    title = "仅屏幕熄灭时监听",
                    subtitle = "屏幕点亮时停止监听(避免与 App 内语音冲突)",
                    checked = onlyScreenOff,
                    onChange = onOnlyScreenOffChange,
                )
            }

            // ── 隐私 ──
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = FWarning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("隐私", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "· 音频在本地处理,不会上传\n" +
                        "· 关闭后麦克风立即停止采集\n" +
                        "· 服务挂掉不会自动拉回(避免偷偷监听)\n" +
                        "· 可在通知栏随时停止",
                    color = FSub, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
        }
    }

    if (showPrivacyDialog) {
        PrivacyConsentDialog(
            onAgree = {
                showPrivacyDialog = false
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDisagree = {
                showPrivacyDialog = false
                // 不勾选开关
            },
        )
    }
}

@Composable
private fun KeywordChips(current: String, onChange: (String) -> Unit) {
    val presets = listOf("章鱼章鱼", "你好章鱼", "小章鱼", "OK 章鱼")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { kw ->
            val selected = kw == current
            Surface(
                shape = OctopusShape.capsule,
                color = if (selected) OctopusColors.Primary.copy(alpha = 0.15f) else OctopusColors.SurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clickable { onChange(kw) },
            ) {
                Text(
                    kw,
                    color = if (selected) OctopusColors.Primary else FSub,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // 自定义关键词输入(简化:用预设即可,自定义需要额外对话框,这里省略)
    if (current !in presets) {
        Text("当前: $current", color = FMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SensibilitySelector(
    current: WakeWordSettings.Sensitivity,
    onChange: (WakeWordSettings.Sensitivity) -> Unit,
) {
    val options = listOf(
        WakeWordSettings.Sensitivity.LOW to ("低" to "需较大声响(防误触)"),
        WakeWordSettings.Sensitivity.MEDIUM to ("中" to "正常说话音量"),
        WakeWordSettings.Sensitivity.HIGH to ("高" to "小声也能触发(易误触)"),
    )
    options.forEach { (s, label) ->
        val (title, desc) = label
        val selected = s == current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onChange(s) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = { onChange(s) })
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = FText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(desc, color = FMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = FSub, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = FMuted, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PrivacyConsentDialog(onAgree: () -> Unit, onDisagree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDisagree,
        title = { Text(stringResource(R.string.wakeword_privacy_title)) },
        text = {
            Text(
                stringResource(R.string.wakeword_privacy_body),
                color = FSub,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(R.string.protocol_agree_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDisagree) { Text(stringResource(R.string.protocol_disagree)) }
        },
    )
}
