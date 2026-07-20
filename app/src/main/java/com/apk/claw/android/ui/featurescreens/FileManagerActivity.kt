package com.apk.claw.android.ui.featurescreens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.octopus_mobile.files.LsParser
import com.apk.claw.android.octopus_mobile.files.LsParser.FileEntry
import com.apk.claw.android.octopus_mobile.files.LsParser.FileType
import com.apk.claw.android.octopus_mobile.files.LsParser.SortMode
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.widget.AdvancedPermissionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件管理器 Activity —— UI 入口.
 *
 * 挂在 SettingsScreen "高级功能" 区,与 MCP/角色卡/工作流 同级。
 * 通过 Shizuku shell UID 2000 视角访问 /sdcard/,可读写其他 App 的 /sdcard/Android/data。
 *
 * 安全:
 *  - 读操作直调 [ShizukuShellService](快,无审计开销)
 *  - 写操作(复制/移动/删除/建目录)走 [ToolRegistry.executeTool] 触发审计 + 撤销窗口
 *  - 路径越界由 [ShizukuShellService.isValidPath] + PathGuard 双重拦截
 */
class FileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { FileManagerScreen(onBack = { finish() }) }
    }
}

// ── 主屏 ──

@Composable
private fun FileManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 路径栈:从 /sdcard 开始
    var currentPath by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // UI 状态
    var showHidden by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var shizukuAvailable by remember { mutableStateOf(checkShizuku()) }

    // 操作状态
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var clipboard by remember { mutableStateOf<Pair<String, String>?>(null) }  // (path, "copy"|"move")
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var textPreview by remember { mutableStateOf<String?>(null) }
    var infoPreview by remember { mutableStateOf<String?>(null) }

    // 加载目录内容
    fun loadDir(path: String) {
        scope.launch {
            loading = true
            errorMsg = null
            val result = withContext(Dispatchers.IO) {
                runCatching { ShizukuShellService.listFiles(path, showHidden) }
            }
            result
                .onSuccess { raw ->
                    if (raw == null) {
                        shizukuAvailable = false
                        errorMsg = "Shizuku 不可用"
                        entries = emptyList()
                    } else if (raw.startsWith("Error:")) {
                        errorMsg = raw.removePrefix("Error:").trim()
                        entries = emptyList()
                    } else {
                        entries = LsParser.parse(raw, path)
                    }
                }
                .onFailure { errorMsg = it.message ?: "加载失败"; entries = emptyList() }
            loading = false
        }
    }

    // 首次进入 + 路径变化时加载
    LaunchedEffect(currentPath, showHidden) {
        if (shizukuAvailable) loadDir(currentPath)
    }

    // Shizuku 重新检测(用户从授权页回来后)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val now = checkShizuku()
                if (now && !shizukuAvailable) {
                    shizukuAvailable = true
                    loadDir(currentPath)
                } else {
                    shizukuAvailable = now
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().background(OctopusBackground.pageBrush())) {
        // 顶栏:返回 + 路径面包屑 + 操作
        TopBar(
            currentPath = currentPath,
            onBack = {
                val parent = parentOf(currentPath)
                if (parent == currentPath) onBack()  // 已到根,关闭页
                else currentPath = parent
            },
            onClose = onBack,
        )

        if (!shizukuAvailable) {
            ShizukuEmptyState(onAuthorize = { AdvancedPermissionDialog.show(ctx) })
            return@Column
        }

        // 当前路径条 + 排序/隐藏开关
        PathActionBar(
            path = currentPath,
            sortMode = sortMode,
            showHidden = showHidden,
            onSortChange = { sortMode = it },
            onToggleHidden = { showHidden = !showHidden },
            onNewFolder = { showNewFolderDialog = true },
            onPaste = clipboard?.let {
                {
                    val (srcPath, mode) = it
                    clipboard = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            ToolRegistry.executeTool(
                                "file_ops",
                                mapOf(
                                    "action" to mode,
                                    "source" to srcPath,
                                    "destination" to "$currentPath/${srcPath.substringAfterLast('/')}",
                                ),
                            )
                        }
                        toast(ctx, if (r.isSuccess) "已${if (mode == "copy") "复制" else "移动"}到当前目录" else (r.error ?: "失败"))
                        loadDir(currentPath)
                    }
                }
            },
        )

        // 文件列表
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = OctopusColors.Primary)
            }
            errorMsg != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(errorMsg!!, color = FWarning, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("(空目录)", color = FMuted, fontSize = 13.sp)
            }
            else -> {
                val filtered = remember(entries, showHidden, sortMode) {
                    LsParser.sortEntries(LsParser.filterHidden(entries, showHidden), sortMode)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDir) {
                                    currentPath = entry.path
                                } else {
                                    selectedEntry = entry
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 操作菜单(文件/文件夹)
    selectedEntry?.let { entry ->
        FileActionSheet(
            entry = entry,
            hasClipboard = clipboard != null,
            onDismiss = { selectedEntry = null },
            onCopy = {
                clipboard = entry.path to "copy"
                toast(ctx, "已复制: ${entry.name}")
                selectedEntry = null
            },
            onMove = {
                clipboard = entry.path to "move"
                toast(ctx, "已剪切: ${entry.name}")
                selectedEntry = null
            },
            onRename = {
                // 简化:用 mv 工具,目标 = 同目录新名。先用 AlertDialog 收集新名
                selectedEntry = entry.copy()  // 标记进入 rename 模式
                showNewFolderDialog = false
                infoPreview = "RENAME:${entry.path}"
                selectedEntry = null
            },
            onDelete = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        ToolRegistry.executeTool(
                            "file_ops",
                            mapOf("action" to "delete", "source" to entry.path),
                        )
                    }
                    toast(ctx, if (r.isSuccess) "已删除: ${entry.name}" else (r.error ?: "删除失败"))
                    selectedEntry = null
                    loadDir(currentPath)
                }
            },
            onInfo = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        ShizukuShellService.getFileInfo(entry.path)
                    }
                    infoPreview = r ?: "无法获取信息"
                    selectedEntry = null
                }
            },
            onRead = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        ToolRegistry.executeTool(
                            "file_ops",
                            mapOf("action" to "read", "source" to entry.path, "max_lines" to 500),
                        )
                    }
                    textPreview = if (r.isSuccess) r.data else r.error
                    selectedEntry = null
                }
            },
        )
    }

    // 新建目录对话框
    if (showNewFolderDialog) {
        TextInputDialog(
            title = "新建目录",
            hint = "目录名",
            onCancel = { showNewFolderDialog = false },
            onConfirm = { name ->
                showNewFolderDialog = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        ToolRegistry.executeTool(
                            "file_ops",
                            mapOf("action" to "mkdir", "source" to "$currentPath/$name"),
                        )
                    }
                    toast(ctx, if (r.isSuccess) "已创建: $name" else (r.error ?: "创建失败"))
                    loadDir(currentPath)
                }
            },
        )
    }

    // 重命名对话框
    infoPreview?.let { content ->
        if (content.startsWith("RENAME:")) {
            val oldPath = content.removePrefix("RENAME:")
            val oldName = oldPath.substringAfterLast('/')
            TextInputDialog(
                title = "重命名",
                initial = oldName,
                hint = "新名称",
                onCancel = { infoPreview = null },
                onConfirm = { newName ->
                    infoPreview = null
                    val newPath = "${oldPath.substringBeforeLast('/')}/$newName"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            ToolRegistry.executeTool(
                                "file_ops",
                                mapOf("action" to "move", "source" to oldPath, "destination" to newPath),
                            )
                        }
                        toast(ctx, if (r.isSuccess) "已重命名" else (r.error ?: "失败"))
                        loadDir(currentPath)
                    }
                },
            )
        } else {
            // 文件信息对话框
            InfoDialog(
                title = "文件信息",
                content = content,
                onDismiss = { infoPreview = null },
            )
        }
    }

    // 文本预览
    textPreview?.let { content ->
        InfoDialog(
            title = "文件内容",
            content = content,
            onDismiss = { textPreview = null },
        )
    }
}

// ── 组件 ──

@Composable
private fun TopBar(currentPath: String, onBack: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(54.dp).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回上级", tint = FText)
        }
        Text(
            currentPath,
            color = FText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Info, contentDescription = "关闭", tint = FMuted)
        }
    }
}

@Composable
private fun PathActionBar(
    path: String,
    sortMode: SortMode,
    showHidden: Boolean,
    onSortChange: (SortMode) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFolder: () -> Unit,
    onPaste: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = OctopusShape.capsule,
            color = OctopusColors.SurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.clickable {
                onSortChange(
                    when (sortMode) {
                        SortMode.NAME -> SortMode.SIZE
                        SortMode.SIZE -> SortMode.MTIME
                        SortMode.MTIME -> SortMode.NAME
                    }
                )
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Sort, contentDescription = null, tint = FSub, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    when (sortMode) {
                        SortMode.NAME -> "名称"
                        SortMode.SIZE -> "大小"
                        SortMode.MTIME -> "时间"
                    },
                    color = FSub, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        IconToggle(
            icon = if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            active = showHidden,
            onClick = onToggleHidden,
        )
        Spacer(Modifier.weight(1f))
        IconToggle(
            icon = Icons.Filled.CreateNewFolder,
            active = false,
            onClick = onNewFolder,
        )
        if (onPaste != null) {
            IconToggle(
                icon = Icons.Filled.ContentPaste,
                active = true,
                onClick = onPaste,
            )
        }
    }
}

@Composable
private fun IconToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = OctopusShape.capsule,
        color = if (active) OctopusColors.Primary.copy(alpha = 0.15f) else OctopusColors.SurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable { onClick() },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) OctopusColors.Primary else FSub,
            modifier = Modifier.padding(8.dp).size(16.dp),
        )
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标
        val (icon, tint) = iconForEntry(entry)
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(7.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        // 名字 + 副信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                color = FText,
                fontSize = 14.sp,
                fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val sub = buildString {
                if (!entry.isDir) append(entry.size)
                if (entry.isSymlink) {
                    if (isNotEmpty()) append(" · ")
                    append("→ ${entry.symlinkTarget ?: "?"}")
                }
                if (isNotEmpty()) append(" · ")
                append(entry.mtime)
            }
            Text(sub, color = FMuted, fontSize = 10.sp, maxLines = 1)
        }
        if (entry.isDir) {
            Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = FMuted, modifier = Modifier.size(14.dp).rotate(180f))
        }
    }
}

@Composable
private fun FileActionSheet(
    entry: FileEntry,
    hasClipboard: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onRead: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                ActionItem("复制", Icons.Filled.ContentCopy, onCopy)
                ActionItem("剪切", Icons.Filled.ContentPaste, onMove)
                ActionItem("重命名", Icons.Filled.Edit, onRename)
                if (!entry.isDir) ActionItem("查看内容", Icons.Filled.Description, onRead)
                ActionItem("详细信息", Icons.Filled.Info, onInfo)
                ActionItem("删除", Icons.Filled.Delete, onDelete, danger = true)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ActionItem(text: String, icon: ImageVector, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) FWarning else FSub, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = if (danger) FWarning else FText, fontSize = 14.sp)
    }
}

@Composable
private fun ShizukuEmptyState(onAuthorize: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = FMuted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("需要 Shizuku 才能访问文件系统", color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Shizuku 提供 shell 级权限,可访问 /sdcard/ 和 /sdcard/Android/data/。\n本应用不会上传你的文件。",
            color = FMuted, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAuthorize, colors = ButtonDefaults.buttonColors(containerColor = OctopusColors.Primary)) {
            Text("去授权", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    hint: String = "",
    initial: String = "",
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun InfoDialog(title: String, content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                content,
                color = FSub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ── 工具函数 ──

private fun checkShizuku(): Boolean = runCatching { ShizukuManager.isAvailable() }.getOrDefault(false)

private fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    if (idx <= 0) return "/"  // 已到根
    return trimmed.substring(0, idx)
}

private fun toast(ctx: android.content.Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

private fun iconForEntry(entry: FileEntry): Pair<ImageVector, Color> {
    if (entry.isDir) return Icons.Filled.Folder to OctopusColors.Primary
    if (entry.isSymlink) return Icons.Filled.InsertDriveFile to FMuted
    return when (LsParser.fileTypeOf(entry.name)) {
        FileType.IMAGE -> Icons.Filled.Image to Color(0xFF42C893)
        FileType.VIDEO -> Icons.Filled.Movie to Color(0xFFFF6B6B)
        FileType.AUDIO -> Icons.Filled.AudioFile to Color(0xFF9B8CFF)
        FileType.APK -> Icons.Filled.SportsEsports to Color(0xFF5DBCD8)
        FileType.ARCHIVE -> Icons.Filled.FolderZip to Color(0xFFE37318)
        FileType.PDF -> Icons.Filled.PictureAsPdf to Color(0xFFF6685D)
        FileType.TEXT -> Icons.Filled.Description to FSub
        FileType.DOCUMENT -> Icons.Filled.Description to Color(0xFF5DBCD8)
        FileType.SPREADSHEET -> Icons.Filled.TableChart to Color(0xFF56C08D)
        FileType.PRESENTATION -> Icons.Filled.Description to Color(0xFF8A88F4)
        FileType.CODE -> Icons.Filled.Code to Color(0xFF8A88F4)
        FileType.OTHER -> Icons.Filled.InsertDriveFile to FMuted
    }
}
