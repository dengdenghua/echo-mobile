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
import com.apk.claw.android.tool.localmodel.MnnJni
import com.apk.claw.android.tool.localmodel.MnnModelManager
import com.apk.claw.android.tool.localmodel.MnnWhisperEngine
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
    val llamaReady = remember(rev) { LlamaJni.ensureLoaded() }
    val mnnReady = remember(rev) { MnnJni.ensureLoaded() }
    val offlineMode = remember(rev) { KVUtils.isLlmOfflineMode() }
    val activePath = remember(rev) { KVUtils.getActiveLocalModel() }
    val loaded = remember(rev) { LocalModelManager.listLoaded() }
    val mnnModels = remember(rev) { MnnModelManager.listModels(ctx) }
    val whisperPath = remember(rev) { MnnModelManager.getActiveWhisperPath() }
    val localAsrOn = remember(rev) { MnnWhisperEngine.isLocalAsrEnabled() }
    val whisperLoaded = remember(rev) { MnnWhisperEngine.isModelLoaded() }
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

    // MNN 模型导入 launcher(.zip / .mnn)
    val mnnPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                loadingPath = "MNN:正在导入模型..."
                errorMsg = null
                val result = withContext(Dispatchers.IO) {
                    MnnModelManager.importFromUri(ctx, uri)
                }
                val (dir, err) = result
                if (dir != null) {
                    // 自动识别类型并提示
                    val type = MnnModelManager.detectType(dir)
                    errorMsg = "导入成功:${dir.name}(${type.name.lowercase()})"
                    loadingPath = null
                } else {
                    errorMsg = err ?: "导入失败"
                    loadingPath = null
                }
                refresh()
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
                    icon = if (llamaReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    iconColor = if (llamaReady) FSuccess else FWarning,
                    label = "llama.cpp (libllama-jni.so)",
                    value = if (llamaReady) "已加载 ✓" else "未编译 ✗",
                )
                Spacer(Modifier.height(4.dp))
                StatusRow(
                    icon = if (mnnReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    iconColor = if (mnnReady) FSuccess else FWarning,
                    label = "MNN (libmnn-jni.so)",
                    value = if (mnnReady) "已加载 ✓" else "未编译 ✗",
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
                            if (on && !llamaReady && !mnnReady) {
                                errorMsg = "llama.cpp 与 MNN native 库均未编译,无法开启离线模式。请先运行 build-native.sh 或 build-mnn.sh。"
                                refresh()
                                return@Switch
                            }
                            if (on && activePath.isBlank()) {
                                errorMsg = "请先选择并加载一个 .gguf 或 .mnn 模型。"
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

            // ── 卡片 3.5:MNN 模型管理(目录导入)──
            if (mnnReady || mnnModels.isNotEmpty()) {
                FCard {
                    Text("MNN 模型目录", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (mnnModels.isEmpty()) {
                        Text(
                            "未导入任何 MNN 模型。\n" +
                                "MNN 模型为目录格式,包含 .mnn 权重 + tokenizer + config。\n" +
                                "可打包成 .zip 通过 SAF 选择导入,自动解压到 app 私有目录。",
                            color = FSub, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    } else {
                        mnnModels.forEach { info ->
                            MnnModelRow(
                                info = info,
                                isActiveLlm = info.dir.absolutePath == activePath,
                                isActiveWhisper = info.dir.absolutePath == whisperPath,
                                onSetActiveLlm = {
                                    MnnModelManager.setActiveLlm(info.dir)
                                    refresh()
                                },
                                onSetActiveWhisper = {
                                    MnnModelManager.setActiveWhisper(info.dir)
                                    val ok = MnnWhisperEngine.loadModel(info.dir.absolutePath)
                                    if (!ok) errorMsg = "Whisper 模型加载失败(native 返回 0)"
                                    refresh()
                                },
                                onDelete = {
                                    if (info.dir.absolutePath == whisperPath) MnnWhisperEngine.unloadModel()
                                    if (info.dir.absolutePath == activePath) {
                                        KVUtils.setActiveLocalModel("")
                                        if (KVUtils.isLlmOfflineMode()) {
                                            KVUtils.setLlmOfflineMode(false)
                                            runCatching {
                                                ClawApplication.appViewModelInstance.updateAgentConfig()
                                            }
                                        }
                                    }
                                    MnnModelManager.deleteModel(info.dir)
                                    refresh()
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { mnnPickLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("导入 .zip / .mnn 模型")
                    }
                    if (loadingPath != null && loadingPath!!.startsWith("MNN:")) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(loadingPath!!, color = FSub, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── 卡片 3.6:端侧 ASR(Whisper)开关 ──
            if (mnnReady) {
                FCard {
                    Text("端侧 ASR(Whisper)", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("使用端侧 Whisper 识别语音", color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (whisperLoaded) "已加载: ${whisperPath.substringAfterLast('/')}"
                                else if (whisperPath.isNotBlank()) "已配置但未加载(点下方按钮加载)"
                                else "未配置 Whisper 模型(需先在上方导入 whisper-tiny 目录)",
                                color = FMuted, fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = localAsrOn,
                            onCheckedChange = { on ->
                                if (on && !whisperLoaded) {
                                    errorMsg = "Whisper 模型未加载,请先导入并加载 whisper 模型目录。"
                                    refresh()
                                    return@Switch
                                }
                                MnnWhisperEngine.setLocalAsrEnabled(on)
                                refresh()
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "开启后「按住说话」和「桌面语音输入」会改用 MNN Whisper 离线识别,\n" +
                            "不依赖 Google 服务,隐私音频不出设备。\n" +
                            "Whisper-tiny 中文识别率约 80%,弱于云端,适合无网场景。",
                        color = FSub, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
            }

            // ── 卡片 4:说明 ──
            FCard {
                Text("使用说明", color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                BulletLine("llama.cpp 推荐:Qwen2.5-3B-Q4_K_M (~2GB,中文好)")
                BulletLine("MNN 推荐:Qwen-1.8B-INT8 (~700MB,GPU 加速 30+ tok/s)")
                BulletLine("Whisper ASR:whisper-tiny (~150MB,中文识别 80%)")
                BulletLine("性能参考:骁龙 8 Gen 2,llama.cpp ~15 tok/s,MNN ~30 tok/s")
                BulletLine("需要 6GB+ RAM 设备(3B 模型),4GB+ (1.8B MNN)")
                BulletLine("首次编译:./build-native.sh (llama) 或 ./build-mnn.sh (MNN)")
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

@Composable
private fun MnnModelRow(
    info: MnnModelManager.ModelInfo,
    isActiveLlm: Boolean,
    isActiveWhisper: Boolean,
    onSetActiveLlm: () -> Unit,
    onSetActiveWhisper: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActiveLlm || isActiveWhisper) FPrimary else FBorder,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.name,
                    color = FText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isActiveLlm) {
                    FPill("LLM", FPrimary)
                    Spacer(Modifier.width(4.dp))
                }
                if (isActiveWhisper) {
                    FPill("Whisper", FSuccess)
                    Spacer(Modifier.width(4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatSize(info.sizeBytes)} · ${info.fileCount} 文件 · ${info.type.name.lowercase()}",
                color = FMuted, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (info.type == MnnModelManager.ModelType.LLM && !isActiveLlm) {
                    OutlinedButton(
                        onClick = onSetActiveLlm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("设为 LLM", fontSize = 11.sp) }
                }
                if (info.type == MnnModelManager.ModelType.WHISPER && !isActiveWhisper) {
                    OutlinedButton(
                        onClick = onSetActiveWhisper,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("加载 Whisper", fontSize = 11.sp) }
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text("删除", fontSize = 11.sp) }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024)
    else String.format("%.0f MB", mb)
}
