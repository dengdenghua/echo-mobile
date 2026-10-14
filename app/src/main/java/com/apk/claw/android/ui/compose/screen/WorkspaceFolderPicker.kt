package com.apk.claw.android.ui.compose.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoFieldColors
import com.apk.claw.android.ui.compose.theme.OctopusColors
import java.io.File

/** 文件夹浏览器只在外部存储根内活动,拿到的都是沙箱能用的真实路径。 */
private const val FOLDER_ROOT = "/sdcard"

/** 把用户已有的工作区路径解析成一个存在的起始目录(不存在则回退到父目录 / 根)。 */
private fun startDir(start: String): String {
    val s = start.trim().trimEnd('/')
    val dir = when {
        s.isBlank() -> File(FOLDER_ROOT)
        File(s).isDirectory -> File(s)
        File(s).parentFile?.isDirectory == true -> File(s).parentFile!!
        else -> File(FOLDER_ROOT)
    }
    val path = dir.absolutePath
    return if (path.startsWith(FOLDER_ROOT)) path else FOLDER_ROOT
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

/**
 * 应用内文件夹浏览器:进目录 / 返回上级 / 新建文件夹 / 选定,返回真实文件系统路径。
 *
 * 为什么不用系统文件管理器(SAF):SAF 返回 content:// URI,而工作区必须是 java.io.File
 * 能直接用的绝对路径(run_code / readFile 沙箱据此定位),所以这里用 File API 自绘一个。
 * 浏览与沙箱读写都依赖「所有文件访问」权限,没有就先引导去授权。
 */
@Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun FolderBrowserDialog(
    start: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current

    if (!hasAllFilesAccess()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("需要文件访问权限") },
            text = {
                Text(
                    "浏览手机文件夹需要「所有文件访问」权限。授予后即可选择或新建工作目录,脚本也才能读写该目录。",
                    color = OctopusColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { openAllFilesAccessSettings(context); onDismiss() }) { Text("去授权") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    var current by remember { mutableStateOf(startDir(start)) }
    var reload by remember { mutableStateOf(0) }
    var newFolder by remember { mutableStateOf<String?>(null) }   // 非空 = 新建对话框开着,值 = 已输入名字

    val subDirs = remember(current, reload) {
        File(current).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }
    val canUp = current.trimEnd('/') != FOLDER_ROOT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择文件夹") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    current,
                    color = OctopusColors.Primary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (canUp) {
                            item(key = "__up__") {
                                FolderRow(icon = Icons.Outlined.DriveFolderUpload, name = "..") {
                                    current = File(current).parentFile?.absolutePath
                                        ?.takeIf { it.startsWith(FOLDER_ROOT) } ?: FOLDER_ROOT
                                }
                            }
                        }
                        if (subDirs.isEmpty()) {
                            item(key = "__empty__") {
                                Text(
                                    "（无子文件夹）",
                                    color = OctopusColors.TextMuted, fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                )
                            }
                        }
                        items(subDirs, key = { it.absolutePath }) { d ->
                            FolderRow(icon = Icons.Outlined.Folder, name = d.name) { current = d.absolutePath }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "＋ 新建文件夹",
                    color = OctopusColors.Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { newFolder = "" }.padding(vertical = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("选此文件夹") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    newFolder?.let { nameDraft ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { newFolder = it },
                    singleLine = true,
                    label = { Text("文件夹名") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = EchoFieldColors.outlined(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameDraft.isNotBlank(),
                    onClick = {
                        val safe = nameDraft.trim().replace('/', '_')
                        runCatching { File(current, safe).mkdirs() }
                        current = File(current, safe).absolutePath
                        newFolder = null
                        reload++
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FolderRow(icon: ImageVector, name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = OctopusColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, color = OctopusColors.TextPrimary, fontSize = 14.sp, maxLines = 1)
    }
}

private fun openAllFilesAccessSettings(context: android.content.Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
