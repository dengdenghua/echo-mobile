package com.apk.claw.android.ui.compose.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * 保活体检 —— 无障碍老掉线的自助排查。
 *
 * 代码侧 moveTaskToBack 保活已验证有效;真机掉线的真凶是国产 ROM(尤其 ColorOS/OriginOS/MIUI)
 * 的后台绞杀,唯一解是让用户把这几项系统开关打开,任何 App 代码都替代不了 —— 这里把它们
 * 收成一张体检单,能检测的检测 + 一键深链系统设置。
 *
 * 四项:无障碍 / 常驻通知(前台服务可见)/ 电池不受限 / 自启动·后台冻结。
 * 前三项可检测并深链;自启动各家 ROM 检测不到,尝试已知 OEM 自启动页,失败回退应用详情页。
 */
@Composable
fun KeepAliveCheckDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }

    val a11yOk = remember(tick) { runCatching { ClawAccessibilityService.isRunning() }.getOrDefault(false) }
    val notifOk = remember(tick) {
        runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false)
    }
    val fgsOk = remember(tick) { runCatching { ForegroundService.isRunning() }.getOrDefault(false) }
    val batteryOk = remember(tick) { isBatteryUnrestricted(ctx) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保活体检", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
            ) {
                Text(
                    "无障碍老掉线?逐条点亮下面 4 项,ROM 就不会再杀后台。",
                    color = OctopusColors.TextMuted, fontSize = OctopusType.caption,
                )
                KeepAliveRow("无障碍服务已开启", a11yOk, "去开启") {
                    ctx.launchSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                KeepAliveRow("常驻通知可见(前台服务)", notifOk && fgsOk, "开通知") {
                    ctx.launchSafe(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                }
                KeepAliveRow("电池不受限(允许后台运行)", batteryOk, "去设置") {
                    ctx.launchSafe(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${ctx.packageName}")),
                    )
                }
                KeepAliveManualRow("自启动 + 关后台冻结(ColorOS/OriginOS 必做)", "打开") {
                    openAutoStartSettings(ctx)
                }
                Text(
                    "再把 App 在「最近任务」里下拉锁定,保活最稳。",
                    color = OctopusColors.TextMuted, fontSize = OctopusType.tag,
                )
            }
        },
        confirmButton = { TextButton(onClick = { tick++ }) { Text("重新检测") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun KeepAliveRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (ok) OctopusColors.Success else OctopusColors.Error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(label, color = OctopusColors.TextPrimary, fontSize = OctopusType.caption, modifier = Modifier.weight(1f))
        if (ok) {
            Text("已就绪", color = OctopusColors.Success, fontSize = OctopusType.tag)
        } else {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
            ) {
                Text(action, fontSize = OctopusType.caption)
            }
        }
    }
}

@Composable
private fun KeepAliveManualRow(label: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("!", color = OctopusColors.Warning, fontWeight = FontWeight.Bold, fontSize = OctopusType.body)
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(label, color = OctopusColors.TextPrimary, fontSize = OctopusType.caption, modifier = Modifier.weight(1f))
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
        ) {
            Text(action, fontSize = OctopusType.caption)
        }
    }
}

private fun isBatteryUnrestricted(ctx: Context): Boolean = runCatching {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
}.getOrDefault(false)

/** 打开目标 Intent;失败(设备无此页)回退到本应用详情页,不让按钮点了没反应。 */
private fun Context.launchSafe(intent: Intent) {
    val ok = runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** 尝试各家 ROM 已知的自启动/后台白名单页;都打不开则回退应用详情页。 */
private fun openAutoStartSettings(ctx: Context) {
    val candidates = listOf(
        // ColorOS / OPPO / 一加
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // OriginOS / Funtouch (vivo)
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // MIUI / HyperOS
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // EMUI / 鸿蒙
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
    )
    for (cn in candidates) {
        val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx.packageManager.resolveActivity(intent, 0) != null &&
            runCatching { ctx.startActivity(intent) }.isSuccess
        ) {
            return
        }
    }
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
