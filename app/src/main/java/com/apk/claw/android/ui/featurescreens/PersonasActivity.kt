package com.apk.claw.android.ui.featurescreens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.persona.Persona
import com.apk.claw.android.octopus_mobile.persona.PersonaPromptBuilder
import com.apk.claw.android.octopus_mobile.persona.PersonaStore
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.EchoFieldColors
import com.apk.claw.android.ui.compose.component.PersonaAvatar

/**
 * 角色卡页 —— 列出所有角色，支持切换/新建/编辑/删除/导入/导出。
 *
 * 对标 Operit 角色卡系统：自定义性格 + 切换 + 导入导出。
 * 当前激活的角色会注入到 [com.apk.claw.android.agent.DefaultAgentService] 的 system prompt。
 */
class PersonasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { PersonasScreen(onBack = { finish() }) }
    }
}

@Composable
private fun PersonasScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rev by remember { mutableStateOf(0) }
    val items = remember(rev) { PersonaStore.list(ctx) }
    val activeId = remember(rev) { PersonaStore.getActive(ctx) }
    var editing by remember { mutableStateOf<Persona?>(null) }
    var adding by remember { mutableStateOf(false) }
    var exporting: Persona? by remember { mutableStateOf(null) }
    fun refresh() { rev++ }

    // 导入文件 launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    PersonaStore.import(ctx, text)
                }
            }
            refresh()
        }
    }
    // 导出文件 launcher
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && exporting != null) {
            val toExport = exporting!!
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(PersonaStore.export(toExport).toByteArray())
                }
            }
            exporting = null
        }
    }

    FeatureScaffold(
        title = stringResource(R.string.settings_personas_title),
        onBack = onBack,
        action = {
            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.personas_import), tint = FText)
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.personas_new), tint = FText)
            }
        },
    ) {
        if (items.isEmpty()) {
            FEmpty(stringResource(R.string.personas_empty))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    FCard {
                        Text(
                            stringResource(R.string.personas_header),
                            color = FText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.personas_intro),
                            color = FSub, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        val activeName = items.firstOrNull { it.id == activeId }?.name
                            ?: stringResource(R.string.personas_none_active)
                        Text(stringResource(R.string.personas_active_now, activeName),
                            color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(items, key = { it.id }) { p ->
                    PersonaCard(
                        persona = p,
                        isActive = p.id == activeId,
                        onActivate = { PersonaStore.setActive(ctx, p.id); refresh() },
                        onEdit = { editing = p },
                        onDelete = {
                            PersonaStore.delete(ctx, p.id)
                            refresh()
                        },
                        onExport = {
                            exporting = p
                            exportLauncher.launch(p.name + ".json")
                        },
                    )
                }
            }
        }
    }

    // 编辑对话框
    if (editing != null) {
        PersonaEditorDialog(
            initial = editing!!,
            onCancel = { editing = null },
            onSave = { p ->
                PersonaStore.save(ctx, p)
                editing = null
                refresh()
            },
        )
    }
    // 新建对话框
    if (adding) {
        PersonaEditorDialog(
            initial = Persona(
                id = "", name = "", avatar = "",
                systemPrompt = "", greeting = "", styleHint = "",
                isBuiltin = false, createdAt = System.currentTimeMillis(),
            ),
            onCancel = { adding = false },
            onSave = { p ->
                PersonaStore.save(ctx, p)
                adding = false
                refresh()
            },
        )
    }
}

private const val ACTIVE_BORDER_ALPHA = .5f

/** Four buttons share a card row (user cards add Delete): tight padding keeps each label on one line. */
private val CardButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)

/** The first character (code point) of [s], or "" — a monogram is one letter. */
private fun firstCodePoint(s: String): String = if (s.isEmpty()) "" else s.substring(0, s.offsetByCodePoints(0, 1))

@Composable
private fun PersonaCard(
    persona: Persona,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    // 使用中的卡 = core 描边 + core 徽标(core 只做选中标记),底色与其他卡相同
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.cardSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) OctopusColors.Accent.copy(alpha = ACTIVE_BORDER_ALPHA) else OctopusBackground.cardBorder,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonaAvatar(persona, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(persona.name, color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (persona.isBuiltin) {
                            Spacer(Modifier.width(6.dp))
                            FPill(stringResource(R.string.personas_builtin), FSub)
                        }
                        if (isActive) {
                            Spacer(Modifier.width(6.dp))
                            FPill(stringResource(R.string.personas_active_badge), OctopusColors.Accent)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        persona.systemPrompt,
                        color = FSub, fontSize = 12.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!isActive) {
                    FilledTonalButton(
                        onClick = onActivate,
                        modifier = Modifier.weight(1f),
                        contentPadding = CardButtonPadding,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.personas_activate), fontSize = 12.sp, maxLines = 1)
                    }
                }
                TextButton(onClick = onEdit, modifier = Modifier.weight(1f), contentPadding = CardButtonPadding) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.personas_edit), fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = onExport, modifier = Modifier.weight(1f), contentPadding = CardButtonPadding) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.personas_export), fontSize = 12.sp, maxLines = 1)
                }
                if (!persona.isBuiltin) {
                    TextButton(onClick = onDelete, modifier = Modifier.weight(1f), contentPadding = CardButtonPadding) {
                        // Delete is destructive: breach, not warning amber.
                        Icon(
                            Icons.Filled.Delete, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = FError,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_delete), fontSize = 12.sp, color = FError)
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaEditorDialog(
    initial: Persona,
    onCancel: () -> Unit,
    onSave: (Persona) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var avatar by remember { mutableStateOf(initial.avatar) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var greeting by remember { mutableStateOf(initial.greeting) }
    var styleHint by remember { mutableStateOf(initial.styleHint) }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(initial.copy(
                        name = name.trim(),
                        // 头像字:只留一个字(按码点),emoji 会被 Persona.monogram() 跳过
                        avatar = firstCodePoint(avatar.trim()),
                        systemPrompt = systemPrompt.trim(),
                        greeting = greeting.trim(),
                        styleHint = styleHint.trim(),
                    ))
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
        title = {
            Text(
                stringResource(if (initial.id.isBlank()) R.string.personas_new_title else R.string.personas_edit_title),
                color = FText,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                FLabel(stringResource(R.string.personas_field_name))
                FTextField(name) { name = it }
                Spacer(Modifier.height(8.dp))
                FLabel(stringResource(R.string.personas_field_avatar))
                FTextField(avatar) { avatar = it }
                Spacer(Modifier.height(8.dp))
                FLabel(stringResource(R.string.personas_field_prompt))
                FTextField(systemPrompt, minLines = 3) { systemPrompt = it }
                Spacer(Modifier.height(8.dp))
                FLabel(stringResource(R.string.personas_field_greeting))
                FTextField(greeting, minLines = 2) { greeting = it }
                Spacer(Modifier.height(8.dp))
                FLabel(stringResource(R.string.personas_field_style))
                FTextField(styleHint, minLines = 2) { styleHint = it }
            }
        },
    )
}

@Composable
private fun FLabel(text: String) {
    Text(text, color = FSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
}

@Composable
private fun FTextField(value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = FText),
        shape = OctopusShape.small,
        colors = EchoFieldColors.outlined(),
    )
}
