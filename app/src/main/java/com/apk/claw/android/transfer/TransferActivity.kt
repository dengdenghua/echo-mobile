package com.apk.claw.android.transfer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.featurescreens.FCard
import com.apk.claw.android.ui.featurescreens.FEmpty
import com.apk.claw.android.ui.featurescreens.FMuted
import com.apk.claw.android.ui.featurescreens.FOnPrimary
import com.apk.claw.android.ui.featurescreens.FPrimary
import com.apk.claw.android.ui.featurescreens.FSectionTitle
import com.apk.claw.android.ui.featurescreens.FSub
import com.apk.claw.android.ui.featurescreens.FText
import com.apk.claw.android.ui.featurescreens.FeatureScaffold
import com.apk.claw.android.ui.featurescreens.setFeatureContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** User-selected imports and exports use SAF; no broad storage permission is needed. */
class TransferActivity : ComponentActivity() {
    private lateinit var store: TransferStore
    private var exporting: String? = null
    private var entries by mutableStateOf<List<TransferStore.Entry>>(emptyList())
    private var status by mutableStateOf("")
    private var working = false
    private var refreshing = false
    private var shareHandled = false

    private val choose = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            "已加入，电脑文件面板会自动更新：${importFile(uri)}"
        }
    }
    private val save = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val name = exporting
        if (uri != null && name != null) work {
            contentResolver.openOutputStream(uri)!!.use { output ->
                store.file(name).inputStream().use { it.copyTo(output) }
            }
            "已保存：$name"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporting = savedInstanceState?.getString("exporting")
        shareHandled = savedInstanceState?.getBoolean("shareHandled") ?: false
        store = TransferStore(File(filesDir, "exchange"))
        // ECHO 二级页脚手架：顶栏让开状态栏，主按钮是单色 action，每个文件一行、操作收成图标按钮
        setFeatureContent {
            TransferScreen(
                entries = entries,
                status = status,
                actions = TransferActions(
                    onAdd = { choose.launch(arrayOf("*/*")) },
                    onRefresh = { refresh() },
                    onOpen = { openFile(it) },
                    onShare = { shareFile(it) },
                    onSave = { name -> exporting = name; save.launch(name) },
                    onBack = { finish() },
                ),
            )
        }
        refresh()
        receiveShare()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { refresh(); delay(REFRESH_INTERVAL_MS) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exporting", exporting)
        outState.putBoolean("shareHandled", shareHandled)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { store.list() }
                if (list != entries) entries = list
            } finally { refreshing = false }
        }
    }

    private fun work(action: () -> String) {
        if (working) return
        working = true
        status = "正在处理…"
        lifecycleScope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching(action).getOrElse { "失败：${it.message}" }
            }
            refresh()
            working = false
        }
    }

    private fun importFile(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority != "$packageName.fileprovider") { "请选择其他应用共享的文件" }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "phone-file-${System.currentTimeMillis()}"
        contentResolver.openInputStream(uri)?.use { store.importFile(name, it) } ?: error("无法读取文件")
        return name
    }

    private fun receiveShare() {
        if (shareHandled) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java,
            ).orEmpty()
            else -> return
        }.distinct()
        shareHandled = true
        if (uris.isEmpty() || uris.size > MAX_SHARED_FILES) {
            status = "请选择 1–20 个文件分享；纯文本请先保存为文件。"
        } else work {
            val results = uris.map { uri -> runCatching { importFile(uri) } }
            val failed = results.mapNotNull { it.exceptionOrNull()?.message }
            "已加入 ${results.count { it.isSuccess }} 个文件，电脑文件面板会自动更新。" +
                if (failed.isEmpty()) "" else "\n失败：${failed.joinToString("；")}"
        }
    }

    private fun shareFile(name: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", store.file(name))
            val share = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "打开或分享 $name"))
        }.onFailure { status = "无法打开：${it.message}" }
    }

    private fun openFile(name: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", store.file(name))
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { status = "没有可打开此文件的应用，请使用分享或保存到手机。" }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 2000L
        const val MAX_SHARED_FILES = 20
    }
}

private class TransferActions(
    val onAdd: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpen: (String) -> Unit,
    val onShare: (String) -> Unit,
    val onSave: (String) -> Unit,
    val onBack: () -> Unit,
)

@Composable
private fun TransferScreen(entries: List<TransferStore.Entry>, status: String, actions: TransferActions) {
    FeatureScaffold(
        title = stringResource(R.string.transfer_title),
        onBack = actions.onBack,
        action = {
            IconButton(onClick = actions.onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.transfer_refresh), tint = FText)
            }
        },
    ) {
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    stringResource(R.string.transfer_intro),
                    color = FMuted, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Button(
                    onClick = actions.onAdd,
                    shape = OctopusShape.small,
                    colors = ButtonDefaults.buttonColors(containerColor = FPrimary, contentColor = FOnPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.transfer_add), fontWeight = FontWeight.SemiBold)
                }
            }
            if (status.isNotBlank()) {
                item {
                    Text(
                        status,
                        color = FSub, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item { FSectionTitle(stringResource(R.string.transfer_files_section)) }
            if (entries.isEmpty()) {
                item { FEmpty(stringResource(R.string.transfer_empty)) }
            }
            items(entries, key = { it.name }) { entry -> TransferFileRow(entry, actions) }
        }
    }
}

@Composable
private fun TransferFileRow(entry: TransferStore.Entry, actions: TransferActions) {
    val context = LocalContext.current
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = FSub,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.name,
                    color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    // 长文件名从中间省略，保留扩展名
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    Formatter.formatShortFileSize(context, entry.size),
                    color = FMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
            FileAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.transfer_open)) {
                actions.onOpen(entry.name)
            }
            FileAction(Icons.Filled.Share, stringResource(R.string.transfer_share)) { actions.onShare(entry.name) }
            FileAction(Icons.Filled.Download, stringResource(R.string.transfer_save)) { actions.onSave(entry.name) }
        }
    }
}

@Composable
private fun FileAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = FText, modifier = Modifier.size(20.dp))
    }
}
