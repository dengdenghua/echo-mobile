package com.apk.claw.android.ui.featurescreens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.tool.mcp.McpManager
import com.apk.claw.android.tool.mcp.McpServerConfig
import com.apk.claw.android.tool.mcp.McpServerConfigStore
import com.apk.claw.android.ui.compose.theme.OctopusShape

/**
 * MCP Server 配置页 —— 管理外部 MCP(Model Context Protocol)工具服务器。
 *
 * 列表展示已配置的 server,支持:
 *  - 添加 / 编辑 / 删除
 *  - 手动连接 / 断开
 *  - 查看连接状态与发现的工具数
 *
 * 配置通过 [McpServerConfigStore] 持久化;运行时通过 [McpManager] 管理。
 */
class McpServersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { McpServersScreen(onBack = { finish() }) }
    }
}

@Composable
private fun McpServersScreen(onBack: () -> Unit) {
    var rev by remember { mutableStateOf(0) }
    val servers = remember(rev) { McpServerConfigStore.all() }
    val running = remember(rev) { McpManager.listServers().associateBy { it.id } }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var adding by remember { mutableStateOf(false) }

    FeatureScaffold(
        title = "MCP Server",
        onBack = onBack,
        action = {
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加", tint = FText)
            }
        },
    ) {
        if (servers.isEmpty()) {
            FEmpty("还没有 MCP server\n点右上角 + 添加\n\nMCP 是 Model Context Protocol,\n可接入外部工具服务器(如文件系统、GitHub 等)")
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(servers, key = { _, s -> s.id }) { _, cfg ->
                val info = running[cfg.id]
                McpServerCard(
                    cfg = cfg,
                    connected = info?.connected == true,
                    toolCount = info?.toolCount ?: 0,
                    onToggleConnect = {
                        if (info?.connected == true) {
                            McpManager.disconnectServer(cfg.id)
                        } else {
                            runCatching { McpManager.connectServer(cfg.id, cfg.toTransport()) }
                        }
                        rev++
                    },
                    onEdit = { editing = cfg },
                    onDelete = {
                        McpServerConfigStore.delete(cfg.id)
                        rev++
                    },
                )
            }
        }
    }

    if (adding) {
        McpServerEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { newCfg ->
                val err = McpServerConfigStore.add(newCfg)
                if (err == null) {
                    // 添加成功后自动连接一次,让用户立即看到工具发现结果
                    runCatching { McpManager.connectServer(newCfg.id, newCfg.toTransport()) }
                    adding = false
                    rev++
                } else {
                    // 错误信息通过 err 传出,Dialog 内部已显示
                }
            },
        )
    }

    editing?.let { cfg ->
        McpServerEditDialog(
            initial = cfg,
            onDismiss = { editing = null },
            onSave = { updated ->
                // id 变化或 transport 变化都需要重连
                val needReconnect = updated.id != cfg.id ||
                    updated.transportType != cfg.transportType ||
                    updated.command != cfg.command ||
                    updated.url != cfg.url ||
                    updated.env != cfg.env ||
                    updated.headers != cfg.headers
                if (needReconnect) McpManager.disconnectServer(cfg.id)
                McpServerConfigStore.update(updated.copy(id = if (cfg.id == updated.id) updated.id else updated.id))
                if (needReconnect && updated.autoConnect) {
                    runCatching { McpManager.connectServer(updated.id, updated.toTransport()) }
                }
                editing = null
                rev++
            },
        )
    }
}

@Composable
private fun McpServerCard(
    cfg: McpServerConfig,
    connected: Boolean,
    toolCount: Int,
    onToggleConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 状态指示灯
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) FSuccess else FMuted.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cfg.name.ifBlank { cfg.id }, color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(cfg.id, color = FMuted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    transportSummary(cfg),
                    color = FSub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2,
                )
                if (connected && toolCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("$toolCount 个工具", color = FPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            Switch(
                checked = connected,
                onCheckedChange = { onToggleConnect() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = FPrimary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = FMuted.copy(alpha = 0.35f),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "编辑",
                color = FSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(
                "删除",
                color = FWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun transportSummary(cfg: McpServerConfig): String = when (cfg.transportType) {
    McpServerConfig.TransportType.STDIO -> {
        val cmd = cfg.command.joinToString(" ")
        "STDIO · ${if (cmd.isNotEmpty()) cmd else "(未配置命令)"}"
    }
    McpServerConfig.TransportType.SSE -> "SSE · ${cfg.url.ifBlank { "(未配置 URL)" }}"
}

@Composable
private fun McpServerEditDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    val isEdit = initial != null
    var id by remember { mutableStateOf(initial?.id ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var transportType by remember { mutableStateOf(initial?.transportType ?: McpServerConfig.TransportType.STDIO) }
    var commandText by remember { mutableStateOf(initial?.command?.joinToString("\n") ?: "") }
    var envText by remember { mutableStateOf(mapToLines(initial?.env, "=")) }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headersText by remember { mutableStateOf(mapToLines(initial?.headers, ": ")) }
    var autoConnect by remember { mutableStateOf(initial?.autoConnect ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑 MCP Server" else "添加 MCP Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.trim() },
                    label = { Text("Server ID") },
                    singleLine = true,
                    enabled = !isEdit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "仅字母/数字/下划线/中划线,作为工具名前缀 mcp_<id>_<tool>",
                    color = FMuted, fontSize = 10.sp,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = transportType == McpServerConfig.TransportType.STDIO,
                        onClick = { transportType = McpServerConfig.TransportType.STDIO },
                        label = { Text("STDIO") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = transportType == McpServerConfig.TransportType.SSE,
                        onClick = { transportType = McpServerConfig.TransportType.SSE },
                        label = { Text("SSE") },
                    )
                }
                when (transportType) {
                    McpServerConfig.TransportType.STDIO -> {
                        OutlinedTextField(
                            value = commandText,
                            onValueChange = { commandText = it },
                            label = { Text("命令(每行一个 argv)") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                            minLines = 3,
                        )
                        Text(
                            "例:npx\n@modelcontextprotocol/server-filesystem\n/sdcard/Download",
                            color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                        )
                        OutlinedTextField(
                            value = envText,
                            onValueChange = { envText = it },
                            label = { Text("环境变量(每行 KEY=VALUE)") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            minLines = 2,
                        )
                    }
                    McpServerConfig.TransportType.SSE -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("SSE URL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = headersText,
                            onValueChange = { headersText = it },
                            label = { Text("请求头(每行 KEY: VALUE)") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            minLines = 2,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启动时自动连接", color = FText, fontSize = 13.sp)
                }
                error?.let {
                    Text(it, color = FWarning, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 校验
                if (id.isBlank()) { error = "Server ID 不能为空"; return@TextButton }
                if (!McpServerConfigStore.isValidId(id)) { error = "Server ID 仅允许 [a-zA-Z0-9_-]"; return@TextButton }
                if (name.isBlank()) { error = "显示名不能为空"; return@TextButton }
                if (transportType == McpServerConfig.TransportType.STDIO && commandText.isBlank()) {
                    error = "STDIO 模式必须填写命令"; return@TextButton
                }
                if (transportType == McpServerConfig.TransportType.SSE && url.isBlank()) {
                    error = "SSE 模式必须填写 URL"; return@TextButton
                }
                val cfg = McpServerConfig(
                    id = id,
                    name = name.trim(),
                    transportType = transportType,
                    command = if (transportType == McpServerConfig.TransportType.STDIO)
                        commandText.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
                    env = if (transportType == McpServerConfig.TransportType.STDIO)
                        parseMap(envText, "=") else emptyMap(),
                    url = if (transportType == McpServerConfig.TransportType.SSE) url.trim() else "",
                    headers = if (transportType == McpServerConfig.TransportType.SSE)
                        parseMap(headersText, ":") else emptyMap(),
                    autoConnect = autoConnect,
                    createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                )
                // 检查 id 冲突(添加时)
                if (!isEdit && McpServerConfigStore.get(id) != null) {
                    error = "ID 已存在"; return@TextButton
                }
                onSave(cfg)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Map → 多行文本,每行 `KEY<sep>VALUE`。 */
private fun mapToLines(map: Map<String, String>?, sep: String): String =
    map?.entries?.joinToString("\n") { "${it.key}$sep${it.value}" } ?: ""

/** 多行文本 → Map。每行用 sep 切第一个 sep。 */
private fun parseMap(text: String, sep: String): Map<String, String> {
    return text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.contains(sep) }
        .associate {
            val idx = it.indexOf(sep)
            it.substring(0, idx).trim() to it.substring(idx + sep.length).trim()
        }
        .filterKeys { it.isNotEmpty() }
}
