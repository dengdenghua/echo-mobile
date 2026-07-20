package com.apk.claw.android.ui.featurescreens

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
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
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.tool.localmodel.LocalModelManager
import com.apk.claw.android.tool.localmodel.LlamaJni
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地大模型页 —— 离线模式开关 + .gguf 模型加载/卸载/设为活跃。
 *
 * 离线模式开启后,AppViewModel.getAgentConfig() 会切到 LlmProvider.LOCAL,
 * 走 LocalLlmClient → LocalModelManager → llama.cpp 在设备端推理,完全不联网。
 *
 * 已知限制(在 UI 中明确告知用户):
 *  - 不支持 function calling:离线模式只能纯对话,无法控制设备/调工具
 *  - 需要 6GB+ RAM 设备(3B 模型)
 *  - 需要预先编译 libllama-jni.so(详见 build-native.sh)
 */
class LocalModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { LocalModelScreen(onBack = { finish() }) }
    }
}

@Composable
private fun LocalModelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableStateOf(0) }
    val nativeReady = remember(rev) { LlamaJni.ensureLoaded() }
    val offlineMode = remember(rev) { KVUtils.isLlmOfflineMode() }
    val activePath = remember(rev) { KVUtils.getActiveLocalModel() }
    val loaded = remember(rev) { LocalModelManager.listLoaded() }
    var loadingPath by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    fun refresh() { rev++ }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 把所选 .gguf 复制到 app 私有目录,拿到绝对路径,方便 JNI 读取
            scope.launch {
                loadingPath = "正在导入模型..."
                errorMsg = null
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
                        val dest = java.io.File(ctx.filesDir, "gguf_${System.currentTimeMillis()}_$name")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选文件")
                        dest.absolutePath
                    }
                }
                result.fold(
                    onSuccess = { path ->
                        // 立即设为活跃 + 加载
                        KVUtils.setActiveLocalModel(path)
                        val loadRes = LocalModelManager.loadModel(path)
                        if (loadRes.isFailure) {
                            errorMsg = "模型加载失败: ${loadRes.exceptionOrNull()?.message}"
                        }
                        loadingPath = null
                        refresh()
                    },
                    onFailure = { e ->
                        errorMsg = "导入失败: ${e.message}"
                        loadingPath = null
                        refresh()
                    },
                )
            }
        }
    }

    FeatureScaffold(
        title = "本地大模型",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ── 卡片 1:引擎状态 + 离线模式开关 ──
            FCard {
                Text("引擎状态", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    icon = if (nativeReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    iconColor = if (nativeReady) FSuccess else FWarning,
                    label = "libllama-jni.so",
                    value = if (nativeReady) "已加载 ✓" else "未编译 ✗",
                )
                Spacer(Modifier.height(4.dp))
                StatusRow(
                    icon = Icons.Filled.Folder,
                    iconColor = FPrimary,
                    label = "已加载模型数",
                    value = "${loaded.size}",
                )
                Spacer(Modifier.height(12.dp))
                Divider(color = FBorder, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("离线模式", color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "开启后主对话走本地模型,不联网。仅支持纯聊天,无法控制设备。",
                            color = FSub, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = { on ->
                            if (on && !nativeReady) {
                                errorMsg = "libllama-jni.so 未编译,无法开启离线模式。请先运行 build-native.sh。"
                                refresh()
                                return@Switch
                            }
                            if (on && activePath.isBlank()) {
                                errorMsg = "请先选择并加载一个 .gguf 模型文件。"
                                refresh()
                                return@Switch
                            }
                            KVUtils.setLlmOfflineMode(on)
                            // 通知 AppViewModel 重载 AgentConfig
                            runCatching {
                                ClawApplication.appViewModelInstance.updateAgentConfig()
                                ClawApplication.appViewModelInstance.initAgent()
                            }
                            refresh()
                        },
                    )
                }
            }

            // ── 卡片 2:活跃模型 + 选择文件 ──
            FCard {
                Text("活跃模型", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (activePath.isBlank()) {
                    Text("未设置 — 请选择 .gguf 模型文件", color = FSub, fontSize = 12.sp)
                } else {
                    Text(
                        activePath.substringAfterLast('/'),
                        color = FText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        activePath,
                        color = FMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pickLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("选择 .gguf 文件")
                    }
                }
                if (loadingPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(loadingPath!!, color = FSub, fontSize = 11.sp)
                    }
                }
            }

            // ── 卡片 3:已加载模型列表 ──
            if (loaded.isNotEmpty()) {
                FCard {
                    Text("已加载模型", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    loaded.forEach { handle ->
                        LoadedModelRow(
                            handle = handle,
                            isActive = handle.path == activePath,
                            onSetActive = {
                                KVUtils.setActiveLocalModel(handle.path)
                                refresh()
                            },
                            onUnload = {
                                LocalModelManager.unloadModel(handle.path)
                                if (handle.path == activePath && loaded.size == 1) {
                                    // 卸载了唯一活跃模型,关闭离线模式避免空转
                                    if (KVUtils.isLlmOfflineMode()) {
                                        KVUtils.setLlmOfflineMode(false)
                                        runCatching {
                                            ClawApplication.appViewModelInstance.updateAgentConfig()
                                        }
                                    }
                                }
                                refresh()
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ── 卡片 4:说明 ──
            FCard {
                Text("使用说明", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                BulletLine("推荐模型:Qwen2.5-3B-Q4_K_M (~2GB,中文好)")
                BulletLine("性能参考:骁龙 8 Gen 2,~15 tokens/s")
                BulletLine("需要 6GB+ RAM 设备 (3B 模型)")
                BulletLine("首次编译 native: 项目根目录运行 ./build-native.sh")
                Spacer(Modifier.height(8.dp))
                Divider(color = FBorder, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠️ 已知限制:\n" +
                        "• 不支持 function calling — 离线模式仅纯对话,无法控制设备\n" +
                        "• 不支持视觉 — 截图/图片输入会被忽略\n" +
                        "• 模型质量低于 GPT-4/Claude — 适合隐私敏感/离线场景",
                    color = FWarning, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }

            // ── 错误提示 ──
            errorMsg?.let { msg ->
                Spacer(Modifier.height(8.dp))
                FCard {
                    Text(msg, color = FWarning, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { errorMsg = null }) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, iconColor: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = FSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = FText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LoadedModelRow(
    handle: LocalModelManager.ModelHandle,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onUnload: () -> Unit,
) {
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) FPrimary else FBorder,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    handle.path.substringAfterLast('/'),
                    color = FText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    FPill("活跃", FPrimary)
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatSize(handle.fileSizeBytes)} · ctx ${handle.contextSize} · 加载于 ${df.format(Date(handle.loadedAt))}",
                color = FMuted, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isActive) {
                    OutlinedButton(
                        onClick = onSetActive,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("设为活跃", fontSize = 11.sp) }
                }
                OutlinedButton(
                    onClick = onUnload,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("卸载", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("•", color = FPrimary, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = FSub, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024)
    else String.format("%.0f MB", mb)
}
