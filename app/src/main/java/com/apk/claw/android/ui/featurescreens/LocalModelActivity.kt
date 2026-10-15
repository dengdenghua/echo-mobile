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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.localmodel.LocalModelManager
import com.apk.claw.android.tool.localmodel.LlamaJni
import com.apk.claw.android.tool.localmodel.MnnJni
import com.apk.claw.android.tool.localmodel.MnnModelManager
import com.apk.claw.android.tool.localmodel.MnnWhisperEngine
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.EchoProgressLineInlineWidth
import com.apk.claw.android.ui.compose.theme.OctopusType
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

/** What the notice card under the cards is saying, so it takes the right state colour. */
private enum class Notice { Done, Setup, Failed }

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
    var notice by remember { mutableStateOf(Notice.Setup) }
    fun refresh() { rev++ }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 把所选 .gguf 复制到 app 私有目录,拿到绝对路径,方便 JNI 读取
            scope.launch {
                loadingPath = ctx.getString(R.string.localmodel_importing)
                errorMsg = null
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
                        val dest = java.io.File(ctx.filesDir, "gguf_${System.currentTimeMillis()}_$name")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } ?: error(ctx.getString(R.string.localmodel_read_file_failed))
                        dest.absolutePath
                    }
                }
                result.fold(
                    onSuccess = { path ->
                        // 立即设为活跃 + 加载
                        KVUtils.setActiveLocalModel(path)
                        val loadRes = LocalModelManager.loadModel(path)
                        if (loadRes.isFailure) {
                            notice = Notice.Failed
                            errorMsg = ctx.getString(
                                R.string.localmodel_load_failed,
                                loadRes.exceptionOrNull()?.message,
                            )
                        }
                        loadingPath = null
                        refresh()
                    },
                    onFailure = { e ->
                        notice = Notice.Failed
                        errorMsg = ctx.getString(R.string.localmodel_import_failed_reason, e.message)
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
                // "MNN:" 前缀是下方 MNN 卡片判断导入中的标记,不要去掉
                loadingPath = "MNN: " + ctx.getString(R.string.localmodel_importing)
                errorMsg = null
                val result = withContext(Dispatchers.IO) {
                    MnnModelManager.importFromUri(ctx, uri)
                }
                val (dir, err) = result
                if (dir != null) {
                    // 自动识别类型并提示
                    val type = MnnModelManager.detectType(dir)
                    notice = Notice.Done
                    errorMsg = ctx.getString(R.string.localmodel_import_done, dir.name, type.name.lowercase())
                    loadingPath = null
                } else {
                    notice = Notice.Failed
                    errorMsg = err ?: ctx.getString(R.string.localmodel_import_failed)
                    loadingPath = null
                }
                refresh()
            }
        }
    }

    FeatureScaffold(
        title = stringResource(R.string.localmodel_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            val engineLoaded = stringResource(R.string.localmodel_engine_loaded)
            val engineNotBuilt = stringResource(R.string.localmodel_engine_not_built)
            // ── 卡片 1:引擎状态 + 离线模式开关 ──
            FCard {
                Text(
                    stringResource(R.string.localmodel_engine_status),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    icon = if (llamaReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    iconColor = if (llamaReady) FSuccess else FWarning,
                    label = "llama.cpp (libllama-jni.so)",
                    value = if (llamaReady) engineLoaded else engineNotBuilt,
                )
                Spacer(Modifier.height(4.dp))
                StatusRow(
                    icon = if (mnnReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    iconColor = if (mnnReady) FSuccess else FWarning,
                    label = "MNN (libmnn-jni.so)",
                    value = if (mnnReady) engineLoaded else engineNotBuilt,
                )
                Spacer(Modifier.height(4.dp))
                StatusRow(
                    icon = Icons.Filled.Folder,
                    iconColor = FPrimary,
                    label = stringResource(R.string.localmodel_loaded_count),
                    value = "${loaded.size}",
                )
                Spacer(Modifier.height(12.dp))
                Divider(color = FBorder, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.localmodel_offline_mode),
                            color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.localmodel_offline_mode_desc),
                            color = FSub, fontSize = OctopusType.caption, lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = { on ->
                            if (on && !llamaReady && !mnnReady) {
                                notice = Notice.Setup
                                errorMsg = ctx.getString(R.string.localmodel_offline_needs_native)
                                refresh()
                                return@Switch
                            }
                            if (on && activePath.isBlank()) {
                                notice = Notice.Setup
                                errorMsg = ctx.getString(R.string.localmodel_offline_needs_model)
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
                Text(
                    stringResource(R.string.localmodel_active_model),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                if (activePath.isBlank()) {
                    Text(stringResource(R.string.localmodel_active_none), color = FSub, fontSize = 12.sp)
                } else {
                    Text(
                        activePath.substringAfterLast('/'),
                        color = FText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        activePath,
                        color = FMuted, fontSize = OctopusType.caption, fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pickLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.localmodel_pick_gguf))
                    }
                }
                if (loadingPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EchoProgressLine(Modifier.width(EchoProgressLineInlineWidth))
                        Spacer(Modifier.width(8.dp))
                        Text(loadingPath!!, color = FSub, fontSize = OctopusType.caption)
                    }
                }
            }

            // ── 卡片 3:已加载模型列表 ──
            if (loaded.isNotEmpty()) {
                FCard {
                    Text(
                        stringResource(R.string.localmodel_loaded_models),
                        color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
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
                    Text(
                        stringResource(R.string.localmodel_mnn_title),
                        color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (mnnModels.isEmpty()) {
                        Text(
                            stringResource(R.string.localmodel_mnn_empty),
                            color = FSub, fontSize = OctopusType.caption, lineHeight = 16.sp,
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
                                    if (!ok) {
                                        notice = Notice.Failed
                                        errorMsg = ctx.getString(R.string.localmodel_whisper_load_failed)
                                    }
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
                        Text(stringResource(R.string.localmodel_import_mnn))
                    }
                    if (loadingPath != null && loadingPath!!.startsWith("MNN:")) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EchoProgressLine(Modifier.width(EchoProgressLineInlineWidth))
                            Spacer(Modifier.width(8.dp))
                            Text(loadingPath!!, color = FSub, fontSize = OctopusType.caption)
                        }
                    }
                }
            }

            // ── 卡片 3.6:端侧 ASR(Whisper)开关 ──
            if (mnnReady) {
                FCard {
                    Text(
                        stringResource(R.string.localmodel_asr_title),
                        color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.localmodel_asr_switch),
                                color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                when {
                                    whisperLoaded -> stringResource(
                                        R.string.localmodel_asr_loaded,
                                        whisperPath.substringAfterLast('/'),
                                    )
                                    whisperPath.isNotBlank() -> stringResource(R.string.localmodel_asr_not_loaded)
                                    else -> stringResource(R.string.localmodel_asr_none)
                                },
                                color = FMuted, fontSize = OctopusType.caption,
                            )
                        }
                        Switch(
                            checked = localAsrOn,
                            onCheckedChange = { on ->
                                if (on && !whisperLoaded) {
                                    notice = Notice.Setup
                                    errorMsg = ctx.getString(R.string.localmodel_asr_needs_model)
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
                        stringResource(R.string.localmodel_asr_desc),
                        color = FSub, fontSize = OctopusType.caption, lineHeight = 16.sp,
                    )
                }
            }

            // ── 卡片 4:说明 ──
            FCard {
                Text(
                    stringResource(R.string.localmodel_guide_title),
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                BulletLine(stringResource(R.string.localmodel_guide_llama))
                BulletLine(stringResource(R.string.localmodel_guide_mnn))
                BulletLine(stringResource(R.string.localmodel_guide_whisper))
                BulletLine(stringResource(R.string.localmodel_guide_speed))
                BulletLine(stringResource(R.string.localmodel_guide_ram))
                BulletLine(stringResource(R.string.localmodel_guide_build))
                Spacer(Modifier.height(8.dp))
                Divider(color = FBorder, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))
                FWarningNote(stringResource(R.string.localmodel_limits))
            }

            // ── 错误提示 ──
            errorMsg?.let { msg ->
                Spacer(Modifier.height(8.dp))
                FCard {
                    // 状态色:完成 = core,需要设置 = 琥珀,失败 = breach
                    val tone = when (notice) {
                        Notice.Done -> FSuccess
                        Notice.Setup -> FWarning
                        Notice.Failed -> FError
                    }
                    Text(msg, color = tone, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { errorMsg = null }) { Text(stringResource(R.string.common_close)) }
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
                    FPill(stringResource(R.string.localmodel_badge_active), FPrimary)
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.localmodel_loaded_meta,
                    formatSize(handle.fileSizeBytes),
                    handle.contextSize,
                    df.format(Date(handle.loadedAt)),
                ),
                color = FMuted, fontSize = OctopusType.caption,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isActive) {
                    OutlinedButton(
                        onClick = onSetActive,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text(stringResource(R.string.localmodel_set_active), fontSize = OctopusType.caption) }
                }
                OutlinedButton(
                    onClick = onUnload,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text(stringResource(R.string.localmodel_unload), fontSize = OctopusType.caption) }
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("•", color = FPrimary, fontSize = OctopusType.caption)
        Spacer(Modifier.width(6.dp))
        Text(text, color = FSub, fontSize = OctopusType.caption, lineHeight = 16.sp)
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
                stringResource(
                    R.string.localmodel_mnn_meta,
                    formatSize(info.sizeBytes),
                    info.fileCount,
                    info.type.name.lowercase(),
                ),
                color = FMuted, fontSize = OctopusType.caption,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (info.type == MnnModelManager.ModelType.LLM && !isActiveLlm) {
                    MnnRowButton(stringResource(R.string.localmodel_use_as_llm), onSetActiveLlm)
                }
                if (info.type == MnnModelManager.ModelType.WHISPER && !isActiveWhisper) {
                    MnnRowButton(stringResource(R.string.localmodel_load_whisper), onSetActiveWhisper)
                }
                // 删除是破坏性操作:breach
                MnnRowButton(stringResource(R.string.common_delete), onDelete, FError)
            }
        }
    }
}

@Composable
private fun RowScope.MnnRowButton(
    text: String,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = FText,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { Text(text, fontSize = OctopusType.caption, color = color) }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024)
    else String.format("%.0f MB", mb)
}
