package com.apk.claw.android.ui.featurescreens

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.workflow.Workflow
import com.apk.claw.android.octopus_mobile.workflow.WorkflowEngine
import com.apk.claw.android.octopus_mobile.workflow.WorkflowResult
import com.apk.claw.android.octopus_mobile.workflow.WorkflowStep
import com.apk.claw.android.octopus_mobile.workflow.WorkflowStore
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        title = "工作流",
        onBack = onBack,
        action = {
            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Icon(Icons.Filled.Upload, contentDescription = "导入", tint = FText)
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建", tint = FText)
            }
        },
    ) {
        if (items.isEmpty()) {
            FEmpty("还没有工作流\n点右上角 + 新建一个\n\n工作流可把多个工具/Prompt/条件\n串联成可复用的自动化流程")
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    FCard {
                        Text("工作流编排", color = FText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "把多个步骤（工具调用 / 自然语言 Prompt / 条件分支）" +
                                "串联成可复用流程，支持变量传递 ${'$'}{var} 和步骤间结果引用。",
                            color = FSub, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("总数：${items.size} · 累计运行：${items.sumOf { it.runCount }}",
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
                        onDelete = { WorkflowStore.delete(ctx, wf.id); refresh() },
                        onExport = {
                            exporting = wf
                            exportLauncher.launch(wf.name + ".json")
                        },
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
            title = { Text("运行中", color = FText) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在执行：${running!!.name}", color = FText, fontSize = 13.sp)
                }
            },
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
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
                    Text(
                        if (workflow.description.isNotBlank()) workflow.description
                        else "${workflow.steps.size} 步",
                        color = FSub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "运行 ${workflow.runCount} 次 · " +
                        if (workflow.lastRunAt > 0) "上次 ${df.format(Date(workflow.lastRunAt))}" else "未运行过",
                    color = FMuted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onRun, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp)); Text("运行", fontSize = 12.sp)
                }
                TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp)); Text("编辑", fontSize = 12.sp)
                }
                TextButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp)); Text("导出", fontSize = 12.sp)
                }
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = FWarning)
                    Spacer(Modifier.width(4.dp)); Text("删除", fontSize = 12.sp, color = FWarning)
                }
            }
        }
    }
}

@Composable
private fun WorkflowEditorDialog(
    initial: Workflow,
    onCancel: () -> Unit,
    onSave: (Workflow) -> Unit,
) {
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
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
        title = { Text(if (initial.id.isBlank()) "新建工作流" else "编辑工作流", color = FText) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                FLabel("名字")
                FTextField(name) { name = it }
                Spacer(Modifier.height(6.dp))
                FLabel("描述")
                FTextField(description) { description = it }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("步骤", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        steps.add(WorkflowStep(
                            type = WorkflowStep.StepType.TOOL,
                            name = "步骤 ${steps.size + 1}",
                            content = "",
                        ))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp)); Text("添加", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                steps.forEachIndexed { idx, step ->
                    StepEditor(
                        step = step,
                        index = idx,
                        onChange = { newStep -> steps[idx] = newStep },
                        onRemove = { steps.removeAt(idx) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (steps.isEmpty()) {
                    Text("还没有步骤，点上方「添加」", color = FMuted, fontSize = 12.sp,
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
    onChange: (WorkflowStep) -> Unit,
    onRemove: () -> Unit,
) {
    val toolNames = remember {
        ToolRegistry.getInstance().getAllTools().map { it.getName() }.sorted()
    }

    Surface(
        shape = OctopusShape.small,
        color = FSurface2.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, FBorder.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${index + 1}", color = FPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                FTextField(step.name, modifier = Modifier.weight(1f)) { onChange(step.copy(name = it)) }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = FWarning, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            // Type 选择
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("类型", color = FSub, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                Spacer(Modifier.width(4.dp))
                WorkflowStep.StepType.values().forEach { t ->
                    FilterChip(
                        selected = step.type == t,
                        onClick = { onChange(step.copy(type = t)) },
                        label = { Text(t.name, fontSize = 10.sp) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (step.type) {
                WorkflowStep.StepType.TOOL -> {
                    FLabel("工具名")
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
                }
                WorkflowStep.StepType.PROMPT -> {
                    FLabel("自然语言指令")
                    FTextField(step.content, minLines = 2) { onChange(step.copy(content = it)) }
                }
                WorkflowStep.StepType.CONDITION -> {
                    FLabel("条件表达式（如 \${var} == \"value\"）")
                    FTextField(step.content, minLines = 1) { onChange(step.copy(content = it)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            FLabel("输出变量名（可选，后续步骤可用 \${名称} 引用）")
            FTextField(step.outputVar ?: "") { onChange(step.copy(outputVar = it.ifBlank { null })) }
        }
    }
}

@Composable
private fun RunResultDialog(
    result: WorkflowResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.success) Icons.Filled.PlayArrow else Icons.Filled.Delete,
                    contentDescription = null,
                    tint = if (result.success) FSuccess else FWarning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (result.success) "运行成功" else "运行失败", color = FText)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("耗时：${result.durationMs} ms · 步骤数：${result.logs.size}",
                    color = FSub, fontSize = 12.sp)
                if (result.error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("错误：${result.error}", color = FWarning, fontSize = 12.sp)
                }
                if (result.finalOutput != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("最终输出：", color = FSub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(result.finalOutput.take(500), color = FText, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("步骤日志：", color = FSub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                result.logs.forEach { log ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            if (log.success) "✓" else "✗",
                            color = if (log.success) FSuccess else FWarning,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(log.stepName, color = FText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            if (log.error != null) {
                                Text(log.error, color = FWarning, fontSize = 10.sp, lineHeight = 13.sp)
                            } else if (log.output.isNotBlank()) {
                                Text(log.output.take(200), color = FSub, fontSize = 10.sp, lineHeight = 13.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${log.durationMs}ms", color = FMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun FLabel(text: String) {
    Text(text, color = FSub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
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
    )
}
