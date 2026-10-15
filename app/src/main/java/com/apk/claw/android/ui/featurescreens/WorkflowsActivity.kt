// A Compose screen is many small private composables (list, editor, dialogs, rows); splitting the file
// would only scatter one screen.
@file:Suppress("TooManyFunctions")

package com.apk.claw.android.ui.featurescreens

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.workflow.Workflow
import com.apk.claw.android.octopus_mobile.workflow.WorkflowContext
import com.apk.claw.android.octopus_mobile.workflow.WorkflowEngine
import com.apk.claw.android.octopus_mobile.workflow.WorkflowResult
import com.apk.claw.android.octopus_mobile.workflow.WorkflowStep
import com.apk.claw.android.octopus_mobile.workflow.WorkflowStore
import com.apk.claw.android.service.WorkflowScheduler
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.apk.claw.android.ui.compose.theme.EchoFieldColors

/**
 * 工作流页 —— 列出所有工作流，支持新建/编辑/删除/导入/导出/运行。
 *
 * 对标 Operit 工作流编排：把多工具/Prompt/条件分支组合成可复用流程。
 * MVP 编辑器采用列表式（iOS 快捷指令风格），不做画布拖拽。
 */
class WorkflowsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { WorkflowsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun WorkflowsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableStateOf(0) }
    val items = remember(rev) { WorkflowStore.all(ctx) }
    var editing by remember { mutableStateOf<Workflow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Workflow?>(null) }
    var runResult by remember { mutableStateOf<WorkflowResult?>(null) }
    var exporting: Workflow? by remember { mutableStateOf(null) }
    var scheduleEditing by remember { mutableStateOf<Workflow?>(null) }
    fun refresh() { rev++ }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    WorkflowStore.import(ctx, text)
                }
            }
            refresh()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && exporting != null) {
            val toExport = exporting!!
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(WorkflowStore.export(toExport).toByteArray())
                }
            }
            exporting = null
        }
    }

    FeatureScaffold(
        title = stringResource(R.string.workflows_title),
        onBack = onBack,
        action = {
            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.workflows_import), tint = FText)
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.workflows_new), tint = FText)
            }
        },
    ) {
        if (items.isEmpty()) {
            FEmpty(stringResource(R.string.workflows_empty))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    FCard {
                        Text(
                            stringResource(R.string.workflows_header_title),
                            color = FText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.workflows_header_desc),
                            color = FSub, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.workflows_stats, items.size, items.sumOf { it.runCount }),
                            color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(items, key = { it.id }) { wf ->
                    WorkflowCard(
                        workflow = wf,
                        onRun = {
                            running = wf
                            scope.launch {
                                val engine = WorkflowEngine(ctx)
                                val result = withContext(Dispatchers.IO) { engine.execute(wf) }
                                runResult = result
                                running = null
                                // 更新运行次数
                                WorkflowStore.save(ctx, wf.copy(
                                    lastRunAt = System.currentTimeMillis(),
                                    runCount = wf.runCount + 1,
                                ))
                                refresh()
                            }
                        },
                        onEdit = { editing = wf },
                        onDelete = {
                            // 删除前先取消可能存在的定时闹钟，避免幽灵闹钟
                            if (wf.isScheduled) WorkflowScheduler.cancel(ctx, wf.id)
                            WorkflowStore.delete(ctx, wf.id); refresh()
                        },
                        onExport = {
                            exporting = wf
                            exportLauncher.launch(wf.name + ".json")
                        },
                        onSchedule = { scheduleEditing = wf },
                    )
                }
            }
        }
    }

    // 编辑
    if (editing != null) {
        WorkflowEditorDialog(
            initial = editing!!,
            onCancel = { editing = null },
            onSave = { w ->
                WorkflowStore.save(ctx, w)
                editing = null
                refresh()
            },
        )
    }
    if (adding) {
        WorkflowEditorDialog(
            initial = Workflow(
                id = "", name = "", description = "",
                steps = emptyList(),
            ),
            onCancel = { adding = false },
            onSave = { w ->
                WorkflowStore.save(ctx, w)
                adding = false
                refresh()
            },
        )
    }
    // 运行结果
    if (runResult != null) {
        RunResultDialog(
            result = runResult!!,
            onDismiss = { runResult = null },
        )
    }
    // 运行中
    if (running != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.workflows_running_title), color = FText) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.workflows_running_message, running!!.name),
                        color = FText, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    EchoProgressLine()
                }
            },
        )
    }
    // 定时编辑
    if (scheduleEditing != null) {
        WorkflowScheduleDialog(
            workflow = scheduleEditing!!,
            onDismiss = { scheduleEditing = null },
            onSave = { updated ->
                WorkflowStore.save(ctx, updated)
                if (updated.isScheduled) {
                    WorkflowScheduler.schedule(ctx, updated)
                } else {
                    WorkflowScheduler.cancel(ctx, updated.id)
                }
                scheduleEditing = null
                refresh()
            },
        )
    }
}

/**
 * 工作流定时编辑器：设定 HH:MM 触发 + 每天/一次。
 * 留空小时 = 取消定时。
 */
@Composable
private fun WorkflowScheduleDialog(
    workflow: Workflow,
    onDismiss: () -> Unit,
    onSave: (Workflow) -> Unit,
) {
    var hour by remember { mutableStateOf(workflow.scheduleHour?.toString() ?: "") }
    var minute by remember { mutableStateOf(workflow.scheduleMinute?.toString() ?: "") }
    var daily by remember { mutableStateOf(workflow.scheduleDaily) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val h = hour.trim().toIntOrNull()
                val m = minute.trim().toIntOrNull()
                if (h != null && m != null && h in 0..23 && m in 0..59) {
                    onSave(workflow.copy(scheduleHour = h, scheduleMinute = m, scheduleDaily = daily))
                } else if (h == null && m == null) {
                    // 两个都空 = 取消定时
                    onSave(workflow.copy(scheduleHour = null, scheduleMinute = null, scheduleDaily = false))
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(R.string.workflows_schedule_title, workflow.name), color = FText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.workflows_schedule_desc),
                    color = FSub, fontSize = 12.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { s -> if (s.all { it.isDigit() } && s.length <= 2) hour = s },
                        label = { Text(stringResource(R.string.workflows_schedule_hour)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = EchoFieldColors.outlined(),
                    )
                    Text(":", color = FText, fontSize = 18.sp)
                    OutlinedTextField(
                        value = minute,
                        onValueChange = { s -> if (s.all { it.isDigit() } && s.length <= 2) minute = s },
                        label = { Text(stringResource(R.string.workflows_schedule_minute)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = EchoFieldColors.outlined(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = daily, onCheckedChange = { daily = it })
                    Spacer(Modifier.width(8.dp))
                    val repeat = if (daily) R.string.workflows_schedule_daily else R.string.workflows_schedule_once
                    Text(stringResource(repeat), color = FText, fontSize = 13.sp)
                }
            }
        },
    )
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onSchedule: () -> Unit,
) {
    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.cardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OctopusBackground.cardBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = FPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = FPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(workflow.name, color = FText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    val stepCount = stringResource(R.string.workflows_step_count, workflow.steps.size)
                    Text(
                        workflow.description.ifBlank { stepCount },
                        color = FSub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val lastRun = if (workflow.lastRunAt > 0) {
                    stringResource(R.string.workflows_last_run, df.format(Date(workflow.lastRunAt)))
                } else {
                    stringResource(R.string.workflows_never_run)
                }
                Text(
                    stringResource(R.string.workflows_run_summary, workflow.runCount, lastRun),
                    color = FMuted, fontSize = OctopusType.caption, modifier = Modifier.weight(1f),
                )
                // 定时状态：已定时时显示 ⏰ 每天/一次 HH:MM，点击进入定时编辑
                if (workflow.isScheduled) {
                    val hh = String.format("%02d", workflow.scheduleHour!!)
                    val mm = String.format("%02d", workflow.scheduleMinute!!)
                    val freq =
                        if (workflow.scheduleDaily) R.string.workflows_badge_daily else R.string.workflows_badge_once
                    Row(
                        modifier = Modifier
                            .clickable { onSchedule() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = FPrimary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(freq, "$hh:$mm"),
                            color = FPrimary, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            WorkflowCardActions(onRun, onEdit, onSchedule, onExport, onDelete)
        }
    }
}

/**
 * The card's actions in two rows (run and edit, then schedule, export and delete), so translated labels
 * stay on one line on a phone; five buttons in one row only left room for two CJK characters.
 */
@Composable
private fun WorkflowCardActions(
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onSchedule: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val compact = PaddingValues(horizontal = 8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = onRun, modifier = Modifier.weight(1f)) {
            WorkflowActionLabel(Icons.Filled.PlayArrow, stringResource(R.string.workflows_run))
        }
        TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
            WorkflowActionLabel(Icons.Filled.Edit, stringResource(R.string.workflows_edit))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onSchedule, modifier = Modifier.weight(1f), contentPadding = compact) {
            WorkflowActionLabel(Icons.Filled.Schedule, stringResource(R.string.workflows_schedule))
        }
        TextButton(onClick = onExport, modifier = Modifier.weight(1f), contentPadding = compact) {
            WorkflowActionLabel(Icons.Filled.Download, stringResource(R.string.workflows_export))
        }
        TextButton(onClick = onDelete, modifier = Modifier.weight(1f), contentPadding = compact) {
            WorkflowActionLabel(Icons.Filled.Delete, stringResource(R.string.common_delete), FError)
        }
    }
}

/** Icon plus a one-line label inside a card action button; [tint] null keeps the button's content colour. */
@Composable
private fun WorkflowActionLabel(icon: ImageVector, text: String, tint: Color? = null) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint ?: LocalContentColor.current)
    Spacer(Modifier.width(4.dp))
    Text(
        text, fontSize = OctopusType.caption, color = tint ?: Color.Unspecified,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WorkflowEditorDialog(
    initial: Workflow,
    onCancel: () -> Unit,
    onSave: (Workflow) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    val steps = remember { mutableStateListOf<WorkflowStep>().apply { addAll(initial.steps) } }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(initial.copy(
                        id = initial.id.ifBlank { UUID.randomUUID().toString() },
                        name = name.trim(),
                        description = description.trim(),
                        steps = steps.toList(),
                    ))
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
        title = {
            val title = if (initial.id.isBlank()) R.string.workflows_editor_new else R.string.workflows_editor_edit
            Text(stringResource(title), color = FText)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                FLabel(stringResource(R.string.workflows_field_name))
                FTextField(name) { name = it }
                Spacer(Modifier.height(6.dp))
                FLabel(stringResource(R.string.workflows_field_description))
                FTextField(description) { description = it }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.workflows_steps),
                        color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        steps.add(WorkflowStep(
                            type = WorkflowStep.StepType.TOOL,
                            name = ctx.getString(R.string.workflows_step_default_name, steps.size + 1),
                            content = "",
                        ))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.workflows_add_step), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                steps.forEachIndexed { idx, step ->
                    StepEditor(
                        step = step,
                        index = idx,
                        allStepNames = steps.map { it.name },
                        onChange = { newStep -> steps[idx] = newStep },
                        onRemove = { steps.removeAt(idx) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (steps.isEmpty()) {
                    Text(stringResource(R.string.workflows_no_steps), color = FMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        },
    )
}

@Composable
private fun StepEditor(
    step: WorkflowStep,
    index: Int,
    allStepNames: List<String>,
    onChange: (WorkflowStep) -> Unit,
    onRemove: () -> Unit,
) {
    val toolNames = remember {
        ToolRegistry.getInstance().getAllTools().map { it.getName() }.sorted()
    }

    Surface(
        shape = OctopusShape.small,
        color = FSurface2.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, FBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${index + 1}", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                FTextField(step.name, modifier = Modifier.weight(1f)) { onChange(step.copy(name = it)) }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete),
                        tint = FError, modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Type 选择：标签放在芯片上方，译文较长时三个芯片仍能排在一行
            FLabel(stringResource(R.string.workflows_step_type))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkflowStep.StepType.values().forEach { t ->
                    FilterChip(
                        selected = step.type == t,
                        onClick = { onChange(step.copy(type = t)) },
                        label = { Text(stepTypeLabel(t), fontSize = OctopusType.caption) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (step.type) {
                WorkflowStep.StepType.TOOL -> {
                    FLabel(stringResource(R.string.workflows_tool_name))
                    // 下拉选择工具
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(
                            value = step.content,
                            onValueChange = { onChange(step.copy(content = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = FText),
                            shape = OctopusShape.small,
                            trailingIcon = {
                                IconButton(onClick = { expanded = true }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = FSub)
                                }
                            },
                            colors = EchoFieldColors.outlined(),
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            toolNames.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text(n, fontSize = 12.sp) },
                                    onClick = { onChange(step.copy(content = n)); expanded = false },
                                )
                            }
                        }
                    }
                    // 工具参数编辑：选中工具后显示该工具声明的参数 schema，
                    // 让用户填值（支持 ${var} 模板引用前序步骤输出）。
                    if (step.content.isNotBlank() && toolNames.contains(step.content)) {
                        Spacer(Modifier.height(6.dp))
                        val tool = remember(step.content) {
                            runCatching { ToolRegistry.getInstance().getTool(step.content) }.getOrNull()
                        }
                        val params = tool?.getParameters() ?: emptyList()
                        if (params.isNotEmpty()) {
                            FLabel(stringResource(R.string.workflows_params_label))
                            params.forEach { p ->
                                val current = step.params[p.name] ?: ""
                                FParamField(
                                    label = "${p.name} (${p.type})" + if (p.isRequired) " *" else "",
                                    value = current,
                                    onChange = { v ->
                                        val newMap = step.params.toMutableMap()
                                        if (v.isBlank()) newMap.remove(p.name) else newMap[p.name] = v
                                        onChange(step.copy(params = newMap))
                                    },
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
                WorkflowStep.StepType.PROMPT -> {
                    FLabel(stringResource(R.string.workflows_prompt_label))
                    FTextField(step.content, minLines = 2) { onChange(step.copy(content = it)) }
                }
                WorkflowStep.StepType.CONDITION -> {
                    FLabel(stringResource(R.string.workflows_condition_label))
                    Text(
                        stringResource(R.string.workflows_condition_example),
                        color = FMuted, fontSize = OctopusType.caption, lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                    // 把 branches Map 转成可编辑的多行文本
                    val branchesText = remember(step.branches) {
                        step.branches.entries.joinToString("\n") { (expr, target) -> "$expr => $target" }
                    }
                    var textState by remember(branchesText) { mutableStateOf(branchesText) }
                    OutlinedTextField(
                        value = textState,
                        onValueChange = { newText ->
                            textState = newText
                            val parsed = newText.lines()
                                .mapNotNull { line ->
                                    val arrow = line.indexOf("=>")
                                    if (arrow < 0) null
                                    else line.substring(0, arrow).trim() to line.substring(arrow + 2).trim()
                                }
                                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                                .toMap()
                            if (parsed != step.branches) {
                                onChange(step.copy(branches = parsed))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = OctopusType.caption, color = FText),
                        shape = OctopusShape.small,
                        colors = EchoFieldColors.outlined(),
                    )
                    // 显示所有可选 stepId（同 workflow 内的其他 step）作为提示
                    Text(
                        stringResource(R.string.workflows_condition_step_ids, allStepNames.take(8).joinToString(" · ")),
                        color = FMuted, fontSize = OctopusType.caption, lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            FLabel(stringResource(R.string.workflows_output_var_label))
            FTextField(step.outputVar ?: "") { onChange(step.copy(outputVar = it.ifBlank { null })) }
        }
    }
}

/** Display name of a step type; the stored/exported value stays the enum name. */
@Composable
private fun stepTypeLabel(type: WorkflowStep.StepType): String = stringResource(
    when (type) {
        WorkflowStep.StepType.TOOL -> R.string.workflows_type_tool
        WorkflowStep.StepType.PROMPT -> R.string.workflows_type_prompt
        WorkflowStep.StepType.CONDITION -> R.string.workflows_type_condition
    },
)

/** 把 step 列表的 name 转成可选 stepId 提示（用于 CONDITION branches 编辑） */
private fun List<WorkflowStep>.allIdsHint(): String =
    take(8).joinToString(" · ") { it.name }

@Composable
private fun FParamField(label: String, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = FSub, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = OctopusType.caption, color = FText),
            shape = OctopusShape.small,
            colors = EchoFieldColors.outlined(),
        )
    }
}

@Composable
private fun RunResultDialog(
    result: WorkflowResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (result.success) FSuccess else FError,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                val title = if (result.success) R.string.workflows_result_success else R.string.workflows_result_failed
                Text(stringResource(title), color = FText)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.workflows_result_summary, result.durationMs, result.logs.size),
                    color = FSub, fontSize = 12.sp)
                if (result.error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.workflows_result_error, result.error),
                        color = FError, fontSize = 12.sp,
                    )
                }
                if (result.finalOutput != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.workflows_result_output),
                        color = FSub, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        result.finalOutput.take(500),
                        color = FText, fontSize = OctopusType.caption, lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.workflows_result_logs),
                    color = FSub, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                result.logs.forEach { log -> RunStepLogRow(log) }
            }
        },
    )
}

@Composable
private fun RunStepLogRow(log: WorkflowContext.StepLog) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // 状态用图标,不用 ✓ / ✗ 字符
        Icon(
            if (log.success) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (log.success) FSuccess else FError,
            modifier = Modifier.size(14.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(log.stepName, color = FText, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold)
            if (log.error != null) {
                Text(log.error, color = FError, fontSize = OctopusType.caption, lineHeight = 16.sp)
            } else if (log.output.isNotBlank()) {
                Text(
                    log.output.take(200), color = FSub, fontSize = OctopusType.caption,
                    lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${log.durationMs}ms", color = FMuted, fontSize = OctopusType.caption)
        }
    }
}

@Composable
private fun FLabel(text: String) {
    Text(text, color = FSub, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
}

@Composable
private fun FTextField(value: String, minLines: Int = 1, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        minLines = minLines,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = FText),
        shape = OctopusShape.small,
        colors = EchoFieldColors.outlined(),
    )
}
