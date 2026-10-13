package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.media.MediaScanner
import com.apk.claw.android.media.PlayerActivity
import com.apk.claw.android.media.WebDAVEntry
import com.apk.claw.android.media.WebDAVScanner
import com.apk.claw.android.media.WebDavMounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudDriveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { CloudDriveScreen(onBack = { finish() }) }
    }
}

private fun isMedia(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in MediaScanner.VIDEO_EXTENSIONS || ext in MediaScanner.AUDIO_EXTENSIONS
}

@Composable
fun CloudDriveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mounts by remember { mutableStateOf(WebDavMounts.all()) }
    var selected by remember { mutableStateOf<WebDavMounts.Mount?>(null) }
    var pathStack by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<WebDAVEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val emptyOrAuthFailedMsg = stringResource(R.string.clouddrive_empty_or_auth_failed)

    fun load(m: WebDavMounts.Mount, path: String) {
        scope.launch {
            busy = true; error = null; entries = null
            val list = withContext(Dispatchers.IO) {
                runCatching { WebDAVScanner.listDirectory(m.baseUrl.trimEnd('/'), path, m.username, m.password) }
                    .getOrElse { emptyList() }
            }
            entries = list
            if (list.isEmpty()) error = emptyOrAuthFailedMsg
            busy = false
        }
    }
    fun open(m: WebDavMounts.Mount) {
        selected = m; pathStack = listOf(m.rootPath.ifBlank { "/" }); load(m, pathStack.last())
    }
    fun enter(href: String) {
        val m = selected ?: return
        pathStack = pathStack + href; load(m, href)
    }
    fun up() {
        val m = selected
        if (pathStack.size > 1 && m != null) { pathStack = pathStack.dropLast(1); load(m, pathStack.last()) }
        else { selected = null; entries = null }
    }

    FeatureScaffold(
        title = selected?.name ?: stringResource(R.string.clouddrive_title),
        onBack = { if (selected != null) up() else onBack() },
        action = {
            if (selected == null) Text(stringResource(R.string.clouddrive_add_button), color = FPrimary, fontSize = 14.sp,
                modifier = Modifier.clickable { showAdd = true }.padding(8.dp))
        },
    ) {
        if (selected == null) {
            if (mounts.isEmpty()) {
                FEmpty(stringResource(R.string.clouddrive_empty_state_hint))
            } else {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(mounts, key = { it.id }) { m ->
                        FCard {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open(m) }) {
                                Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = FPrimary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(m.name, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(m.baseUrl, color = FMuted, fontSize = 10.sp, maxLines = 1)
                                }
                                Text("✕", color = FMuted, fontSize = 14.sp,
                                    modifier = Modifier.clickable { WebDavMounts.remove(m.id); mounts = WebDavMounts.all() }.padding(6.dp))
                            }
                        }
                    }
                }
            }
        } else {
            // 路径面包屑
            Text(pathStack.last(), color = FMuted, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val list = entries
            when {
                busy || list == null -> FEmpty(stringResource(R.string.browser_loading_text))
                list.isEmpty() -> FEmpty(error ?: stringResource(R.string.clouddrive_empty_directory))
                else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    val folders = list.filter { it.isDirectory }
                    val files = list.filter { !it.isDirectory }
                    items(folders, key = { "d" + it.href }) { e ->
                        FCard {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { enter(e.href) }) {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = FPrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(e.name, color = FText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("›", color = FMuted, fontSize = 18.sp)
                            }
                        }
                    }
                    items(files, key = { "f" + it.href }) { e ->
                        val playable = isMedia(e.name)
                        FCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().let {
                                    if (playable) it.clickable {
                                        val m = selected ?: return@clickable
                                        val url = WebDavMounts.playUrl(m, e.href)
                                        runCatching { ctx.startActivity(PlayerActivity.intent(ctx, url, null, 0, e.name)) }
                                    } else it
                                },
                            ) {
                                Icon(
                                    if (playable) Icons.Filled.Movie else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = if (playable) FPrimary else FMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(e.name, color = if (playable) FText else FMuted, fontSize = 14.sp, maxLines = 1)
                                    Text(MediaScanner.formatSize(e.size), color = FMuted, fontSize = 10.sp)
                                }
                                if (playable) Text("▶", color = FPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddMountDialog(
            onDismiss = { showAdd = false },
            onSave = { name, url, path, user, pass ->
                if (url.isNotBlank()) {
                    WebDavMounts.add(
                        WebDavMounts.Mount(
                            id = "wd_" + System.currentTimeMillis(),
                            name = name.ifBlank { url },
                            baseUrl = url.trim(),
                            rootPath = path.ifBlank { "/" }.trim(),
                            username = user.trim(),
                            password = pass,
                        )
                    )
                    mounts = WebDavMounts.all()
                }
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddMountDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = OctopusColors.Accent, unfocusedBorderColor = OctopusColors.BorderHigh,
        focusedTextColor = FText, unfocusedTextColor = FText, cursorColor = OctopusColors.Accent,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FSurface,
        title = { Text(stringResource(R.string.clouddrive_add_mount_dialog_title), color = FText, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.device_detail_name_label), color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("http://192.168.1.10:5005", color = FMuted) }, label = { Text(stringResource(R.string.clouddrive_label_server_address), color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.clouddrive_label_start_path), color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.clouddrive_label_username), color = FMuted) }, colors = colors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pass, { pass = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.clouddrive_label_password), color = FMuted) }, colors = colors)
            }
        },
        confirmButton = { Text(stringResource(R.string.common_save), color = FPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(name, url, path, user, pass) }.padding(8.dp)) },
        dismissButton = { Text(stringResource(R.string.common_cancel), color = FMuted, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)) },
    )
}
