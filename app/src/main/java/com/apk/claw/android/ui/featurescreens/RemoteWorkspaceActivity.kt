package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.workspace.FsEntry
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceCache
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remote workspace manager - mount SFTP/WebDAV/local dirs for Agent programming.
 *
 * Three screens:
 *  1. Mount list (default)
 *  2. Add mount form (dialog)
 *  3. File tree browser (click a mount to enter)
 */
class RemoteWorkspaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { RemoteWorkspaceScreen(onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteWorkspaceScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mounts by remember { mutableStateOf(RemoteWorkspaceMounts.all()) }
    var selected by remember { mutableStateOf<RemoteWorkspaceMounts.Mount?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun refresh() { mounts = RemoteWorkspaceMounts.all() }

    fun mount(m: RemoteWorkspaceMounts.Mount, password: String, privateKey: String) {
        scope.launch {
            busy = true; error = null
            val r = withContext(Dispatchers.IO) {
                RemoteWorkspaceManager.mount(m, password, privateKey)
            }
            if (r.success) toast = "Mounted: ${m.name} (${r.fileTreePreview.size} top entries)"
            else error = r.message
            busy = false; refresh()
        }
    }

    fun unmount(m: RemoteWorkspaceMounts.Mount, removeConfig: Boolean) {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) { RemoteWorkspaceManager.unmount(m.id) }
            if (removeConfig) RemoteWorkspaceMounts.remove(m.id)
            busy = false; refresh(); toast = "Unmounted: ${m.name}"
        }
    }

    fun pushDirty(m: RemoteWorkspaceMounts.Mount) {
        scope.launch {
            busy = true; error = null
            val dirty = withContext(Dispatchers.IO) { RemoteWorkspaceCache.isDirty(m.id) }
            if (dirty.isEmpty()) { toast = "No dirty files"; busy = false; return@launch }
            var ok = 0; var fail = 0
            for (path in dirty) {
                val r = withContext(Dispatchers.IO) { RemoteWorkspaceCache.pushFile(m.id, path) }
                when (r) {
                    is RemoteWorkspaceCache.PushResult.Success -> ok++
                    else -> fail++
                }
            }
            toast = "Push: $ok ok, $fail failed"
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_workspace_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) selected = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text("! $it", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp))
            }
            val m = selected
            if (m == null) {
                MountList(mounts,
                    onOpen = { selected = it },
                    onUnmount = { unmount(it, false) },
                    onDelete = { unmount(it, true) },
                    onPush = { pushDirty(it) })
            } else {
                FileBrowser(mount = m)
            }
        }

        toast?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2500)
                toast = null
            }
            Snackbar { Text(msg) }
        }
    }

    if (showAdd) {
        AddMountDialog(
            onDismiss = { showAdd = false },
            onMount = { m, pwd, key -> mount(m, pwd, key); showAdd = false }
        )
    }
}

@Composable
private fun MountList(
    mounts: List<RemoteWorkspaceMounts.Mount>,
    onOpen: (RemoteWorkspaceMounts.Mount) -> Unit,
    onUnmount: (RemoteWorkspaceMounts.Mount) -> Unit,
    onDelete: (RemoteWorkspaceMounts.Mount) -> Unit,
    onPush: (RemoteWorkspaceMounts.Mount) -> Unit,
) {
    if (mounts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.remote_workspace_empty),
                color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(mounts, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth().padding(4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(typeIcon(m.type), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { onOpen(m) }) {
                        Text(m.name, fontWeight = FontWeight.Bold)
                        Text("${m.type} ${m.host}:${m.port}${m.rootPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                        Text("Status: ${m.lastStatus.ifEmpty { "unknown" }}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onPush(m) }) {
                        Icon(Icons.Filled.Upload, contentDescription = "Push")
                    }
                    IconButton(onClick = { onUnmount(m) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Unmount")
                    }
                    IconButton(onClick = { onDelete(m) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun typeIcon(t: RemoteWorkspaceMounts.Type) = when (t) {
    RemoteWorkspaceMounts.Type.SFTP -> Icons.Filled.Computer
    RemoteWorkspaceMounts.Type.WEBDAV -> Icons.Filled.Cloud
    RemoteWorkspaceMounts.Type.LOCAL -> Icons.Filled.Storage
}

@Composable
private fun FileBrowser(mount: RemoteWorkspaceMounts.Mount) {
    val scope = rememberCoroutineScope()
    var pathStack by remember { mutableStateOf(listOf(mount.rootPath.ifBlank { "/" })) }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(path: String) {
        scope.launch {
            busy = true; error = null; entries = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    RemoteWorkspaceManager.getBackend(mount.id)?.listDir(path) ?: emptyList()
                }
            }
            entries = r.getOrNull()
            error = r.exceptionOrNull()?.message
            busy = false
        }
    }

    LaunchedEffect(mount.id, pathStack.last()) { load(pathStack.last()) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Path: ${pathStack.last()}", fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(4.dp))
        if (pathStack.size > 1) {
            TextButton(onClick = {
                pathStack = pathStack.dropLast(1); load(pathStack.last())
            }) { Text("< Parent dir") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text("! $it", color = MaterialTheme.colorScheme.error) }
        entries?.let { list ->
            if (list.isEmpty()) {
                Text("(empty dir)", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.path }) { e ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (e.isDir) {
                                pathStack = pathStack + e.path; load(e.path)
                            }
                        }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (e.isDir) Icons.Filled.Folder else Icons.Filled.Storage,
                                contentDescription = null,
                                tint = if (e.isDir) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name,
                                    fontWeight = if (e.isDir) FontWeight.Bold else FontWeight.Normal)
                                Text("${e.size} bytes mtime=${e.mtime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMountDialog(
    onDismiss: () -> Unit,
    onMount: (RemoteWorkspaceMounts.Mount, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RemoteWorkspaceMounts.Type.SFTP) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_workspace_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Protocol")
                Row {
                    RemoteWorkspaceMounts.Type.values().forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.name) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(host, { host = it }, label = { Text("Host *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(if (type == RemoteWorkspaceMounts.Type.LOCAL)
                            "/sdcard/Documents/project" else "nas.example.com")
                    })
                if (type != RemoteWorkspaceMounts.Type.LOCAL) {
                    OutlinedTextField(port, { port = it }, label = { Text("Port") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text("User") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation())
                    if (type == RemoteWorkspaceMounts.Type.SFTP) {
                        OutlinedTextField(privateKey, { privateKey = it },
                            label = { Text("SSH private key (PEM, optional)") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5)
                    }
                }
                OutlinedTextField(rootPath, { rootPath = it }, label = { Text("Root path") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && host.isNotBlank(),
                onClick = {
                    val m = RemoteWorkspaceMounts.Mount(
                        id = "rws-" + java.util.UUID.randomUUID().toString().take(8),
                        name = name, type = type, host = host,
                        port = port.toIntOrNull() ?: 22,
                        user = user, rootPath = rootPath,
                    )
                    onMount(m, password, privateKey)
                }
            ) { Text(stringResource(R.string.remote_workspace_mount)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
