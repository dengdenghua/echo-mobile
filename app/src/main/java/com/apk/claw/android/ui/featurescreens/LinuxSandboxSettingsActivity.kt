package com.apk.claw.android.ui.featurescreens

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.tool.impl.LinuxSandbox
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
        title = "Linux 容器",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ── 卡片 1:说明 ──
            FCard {
                Text("什么是 Linux 容器", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "App 内置 PRoot 用户态 Linux 沙箱,Agent 调 run_shell 工具可在容器内执行真实 Linux 命令" +
                        "(apt/pip 装包、git、ffmpeg、python 等),无需 root。\n\n" +
                        "两个发行版:\n" +
                        "  • Alpine — 3MB,已打包进 APK,首调自动解压\n" +
                        "  • Ubuntu — 28MB,需在此页主动下载后才能用\n\n" +
                        "安全:容器根目录隔离 + bind mount 白名单(只挂 Download/Documents)+" +
                        "UID 隔离 + HIGH 风险闸门 + 超时强杀 + 64KB 输出上限。",
                    color = FSub, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }

            // ── 卡片 2:Alpine 状态 ──
            FCard {
                Text("Alpine (内置)", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    icon = if (alpineReady) Icons.Filled.CheckCircle else Icons.Filled.Terminal,
                    iconColor = if (alpineReady) FSuccess else FPrimary,
                    label = "状态",
                    value = if (alpineReady) "已就绪 ✓" else "未初始化(首调 run_shell 自动解压)",
                )
                if (alpinePath != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        alpinePath,
                        color = FMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    val size = runCatching { dirSize(File(alpinePath)) }.getOrDefault(0L)
                    Text(
                        "占用: ${formatSize(size)}",
                        color = FMuted, fontSize = 10.sp,
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
                                msg = if (r.isSuccess) "Alpine 容器已重置" else "重置失败: ${r.error ?: r.errorCode ?: "未知错误"}"
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
                        Text("重置 Alpine")
                    }
                }
                if (resetting == "Alpine") {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在重置...", color = FSub, fontSize = 11.sp)
                    }
                }
            }

            // ── 卡片 3:Ubuntu rootfs 下载 ──
            FCard {
                Text("Ubuntu (需下载)", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    label = "状态",
                    value = when {
                        ubuntuReady -> "已就绪 ✓"
                        ubuntuRootfsDownloaded -> "rootfs 已下载,首调 run_shell 时自动解压"
                        else -> "未下载(28MB,从 cdimage.ubuntu.com)"
                    },
                )
                if (ubuntuPath != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ubuntuPath,
                        color = FMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (downloading) {
                    // 下载进度
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("下载中... $progress%", color = FSub, fontSize = 11.sp)
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
                                    msg = if (r.isSuccess) r.data else "下载失败: ${r.error ?: r.errorCode ?: "未知错误"}"
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
                            Text(if (ubuntuRootfsDownloaded) "重新下载" else "下载 Ubuntu rootfs")
                        }
                        if (ubuntuRootfsDownloaded) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            LinuxSandbox.deleteUbuntuRootfsDownload()
                                        }
                                        msg = if (r.isSuccess) "Ubuntu rootfs 下载缓存已删除" else "删除失败"
                                        refresh()
                                    }
                                },
                                enabled = resetting == null,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除缓存")
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
                                    msg = if (r.isSuccess) "Ubuntu 容器已重置" else "重置失败: ${r.error ?: r.errorCode ?: "未知错误"}"
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
                            Text("重置 Ubuntu 容器(删 rootfs + home)")
                        }
                    }
                }
                if (resetting == "Ubuntu") {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在重置...", color = FSub, fontSize = 11.sp)
                    }
                }
            }

            // ── 卡片 4:使用提示 ──
            FCard {
                Text("使用方式", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Agent 调用 run_shell 工具时自动使用此容器:\n" +
                        "  • run_shell command=\"ls -la /\"\n" +
                        "  • run_shell command=\"apt install -y jq\" distro=ubuntu\n" +
                        "  • run_shell_session(交互式会话)\n\n" +
                        "默认 Alpine(轻量快速)。Ubuntu 适合需要 glibc / apt 完整生态的场景。\n" +
                        "Bind mount 白名单:/sdcard/Download + /sdcard/Documents(容器内可读写)。",
                    color = FSub, fontSize = 11.sp, lineHeight = 16.sp,
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
