package com.apk.claw.android.ui.featurescreens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.impl.LinuxSandbox
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.EchoProgressLineInlineWidth
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Linux 容器设置页 —— 沙箱状态 + Ubuntu rootfs 下载/删除 + 容器重置。
 *
 * 两大发行版:
 *  - Alpine:3MB minirootfs 打包进 assets,首调 run_shell 自动解压,无需用户干预
 *  - Ubuntu:28MB rootfs 不打包,需用户在此页主动下载(SHA256 校验)后才能用 run_shell distro=ubuntu
 *
 * 本页只做"运维"操作,不执行 shell 命令(那是 run_shell 工具的职责)。
 * 重置容器会删 rootfs + home 持久化目录,下次调 run_shell 重新 bootstrap。
 */
class LinuxSandboxSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { LinuxSandboxSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun LinuxSandboxSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableStateOf(0) }
    val alpineReady = remember(rev) { LinuxSandbox.isReady(LinuxSandbox.Distro.ALPINE) }
    val alpinePath = remember(rev) { LinuxSandbox.containerPath(LinuxSandbox.Distro.ALPINE) }
    val ubuntuRootfsDownloaded = remember(rev) { LinuxSandbox.isUbuntuRootfsDownloaded() }
    val ubuntuReady = remember(rev) { LinuxSandbox.isReady(LinuxSandbox.Distro.UBUNTU) }
    val ubuntuPath = remember(rev) { LinuxSandbox.containerPath(LinuxSandbox.Distro.UBUNTU) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf<String?>(null) }
    fun refresh() { rev++ }

    FeatureScaffold(
        title = stringResource(R.string.settings_linux_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            val statusLabel = stringResource(R.string.sandbox_status)
            val statusReady = stringResource(R.string.sandbox_ready)
            // ── 卡片 1:说明 ──
            FCard {
                Text(
                    stringResource(R.string.sandbox_about_title),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sandbox_about_body),
                    color = FSub, fontSize = OctopusType.caption, lineHeight = 16.sp,
                )
            }

            // ── 卡片 2:Alpine 状态 ──
            FCard {
                Text(
                    stringResource(R.string.sandbox_alpine_title),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    icon = if (alpineReady) Icons.Filled.CheckCircle else Icons.Filled.Terminal,
                    iconColor = if (alpineReady) FSuccess else FPrimary,
                    label = statusLabel,
                    value = if (alpineReady) statusReady else stringResource(R.string.sandbox_alpine_not_ready),
                )
                if (alpinePath != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        alpinePath,
                        color = FMuted, fontSize = OctopusType.caption, fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    val size = runCatching { dirSize(File(alpinePath)) }.getOrDefault(0L)
                    Text(
                        stringResource(R.string.sandbox_storage_used, formatSize(size)),
                        color = FMuted, fontSize = OctopusType.caption,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            resetting = "Alpine"
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    LinuxSandbox.reset(LinuxSandbox.Distro.ALPINE)
                                }
                                msg = resetMessage(ctx, "Alpine", r)
                                resetting = null
                                refresh()
                            }
                        },
                        enabled = resetting == null && !downloading,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.sandbox_reset_alpine))
                    }
                }
                if (resetting == "Alpine") {
                    Spacer(Modifier.height(8.dp))
                    ResettingRow()
                }
            }

            // ── 卡片 3:Ubuntu rootfs 下载 ──
            FCard {
                Text(
                    stringResource(R.string.sandbox_ubuntu_title),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    icon = when {
                        ubuntuReady -> Icons.Filled.CheckCircle
                        ubuntuRootfsDownloaded -> Icons.Filled.Download
                        else -> Icons.Filled.Warning
                    },
                    iconColor = when {
                        ubuntuReady -> FSuccess
                        ubuntuRootfsDownloaded -> FPrimary
                        else -> FWarning
                    },
                    label = statusLabel,
                    value = when {
                        ubuntuReady -> statusReady
                        ubuntuRootfsDownloaded -> stringResource(R.string.sandbox_ubuntu_downloaded)
                        else -> stringResource(R.string.sandbox_ubuntu_missing)
                    },
                )
                if (ubuntuPath != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ubuntuPath,
                        color = FMuted, fontSize = OctopusType.caption, fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (downloading) {
                    // 下载进度
                    EchoProgressLine(progress = { progress / 100f })
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sandbox_downloading, progress),
                        color = FSub, fontSize = OctopusType.caption,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                downloading = true
                                progress = 0
                                msg = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        LinuxSandbox.downloadUbuntuRootfs { p ->
                                            progress = p
                                        }
                                    }
                                    msg = if (r.isSuccess) {
                                        ctx.getString(R.string.sandbox_download_done)
                                    } else {
                                        ctx.getString(R.string.sandbox_download_failed, failureReason(ctx, r))
                                    }
                                    downloading = false
                                    refresh()
                                }
                            },
                            enabled = resetting == null,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(
                                    if (ubuntuRootfsDownloaded) {
                                        R.string.sandbox_download_again
                                    } else {
                                        R.string.sandbox_download_ubuntu
                                    },
                                ),
                            )
                        }
                        if (ubuntuRootfsDownloaded) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            LinuxSandbox.deleteUbuntuRootfsDownload()
                                        }
                                        msg = if (r.isSuccess) {
                                            ctx.getString(R.string.sandbox_cache_deleted)
                                        } else {
                                            ctx.getString(R.string.sandbox_delete_failed)
                                        }
                                        refresh()
                                    }
                                },
                                enabled = resetting == null,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.sandbox_delete_cache))
                            }
                        }
                    }
                    if (ubuntuRootfsDownloaded) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                resetting = "Ubuntu"
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        LinuxSandbox.reset(LinuxSandbox.Distro.UBUNTU)
                                    }
                                    msg = resetMessage(ctx, "Ubuntu", r)
                                    resetting = null
                                    refresh()
                                }
                            },
                            enabled = resetting == null,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.sandbox_reset_ubuntu))
                        }
                    }
                }
                if (resetting == "Ubuntu") {
                    Spacer(Modifier.height(8.dp))
                    ResettingRow()
                }
            }

            // ── 卡片 4:使用提示 ──
            FCard {
                Text(
                    stringResource(R.string.sandbox_usage_title),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sandbox_usage_body),
                    color = FSub, fontSize = OctopusType.caption, lineHeight = 16.sp,
                )
            }

            // ── 消息条 ──
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = FPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        it,
                        color = FPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = FSub, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Text(value, color = FText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 重置进行中:一条细线 + 「正在重置…」。 */
@Composable
private fun ResettingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EchoProgressLine(Modifier.width(EchoProgressLineInlineWidth))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.sandbox_resetting), color = FSub, fontSize = OctopusType.caption)
    }
}

/** 失败原因:工具返回的 error,其次 errorCode,都没有时用「未知错误」。 */
private fun failureReason(ctx: Context, r: ToolResult): String =
    r.error ?: r.errorCode ?: ctx.getString(R.string.sandbox_unknown_error)

/** 重置 [distro] 容器后在消息条显示的文案。 */
private fun resetMessage(ctx: Context, distro: String, r: ToolResult): String =
    if (r.isSuccess) {
        ctx.getString(R.string.sandbox_reset_done, distro)
    } else {
        ctx.getString(R.string.sandbox_reset_failed, failureReason(ctx, r))
    }

/** 递归计算目录大小(字节)。 */
private fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** 字节数格式化为人类可读。 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
