package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.appViewModel
import com.apk.claw.android.utils.XLog
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Uses the existing paired socket; no operator password or web token is copied to the phone. */
class DeviceTasksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { DeviceTasksScreen { finish() } }
    }
}

private const val POLL_INTERVAL_MS = 3000L
private const val MAX_TASK_LENGTH = 4096
private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
private val taskLabels = mapOf(
    "planning" to "正在生成计划", "awaiting_handoff" to "等待阶段交接", "awaiting_approval" to "等待确认", "running" to "正在执行",
    "paused" to "已暂停", "interrupted" to "已中断", "succeeded" to "步骤已执行", "failed" to "执行失败",
    "cancelled" to "已取消", "emergency_stopped" to "已停止",
)

private data class StageDraft(val deviceId: String = "", val task: String = "")
private const val MAX_STAGES = 8
private const val MAX_STAGE_TASK_LENGTH = 1024

private class TaskWorkspaceState {
    var stages by mutableStateOf<List<StageDraft>>(emptyList())
    var tasks by mutableStateOf<List<JsonObject>>(emptyList())
    var devices by mutableStateOf<List<JsonObject>>(emptyList())
    var target by mutableStateOf("")
    var text by mutableStateOf("")
    var error by mutableStateOf("")
    var loadError by mutableStateOf("")
    var busy by mutableStateOf(false)
    var submissionId = UUID.randomUUID().toString()

    @Suppress("TooGenericExceptionCaught") // UI network boundary; lifecycle cancellation must still propagate.
    suspend fun refresh() {
        try {
            val client = requireNotNull(appViewModel.octopusClient) { "请先在设置中连接设备中心" }
            tasks = client.taskWorkspace("list").getAsJsonArray("tasks").map { it.asJsonObject }
            devices = client.taskWorkspace("devices").getAsJsonArray("devices").map { it.asJsonObject }
            loadError = ""
        } catch (timeout: TimeoutCancellationException) {
            XLog.e("DeviceTasks", "Task workspace polling timed out", timeout)
            tasks = emptyList()
            devices = emptyList()
            loadError = "连接超时，正在等待设备中心恢复"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tasks = emptyList()
            devices = emptyList()
            loadError = failure.message ?: "设备中心暂不可用"
        }
    }

    @Suppress("TooGenericExceptionCaught") // Surface protocol/transport errors; do not swallow activity cancellation.
    suspend fun perform(command: String, args: Map<String, Any?>) {
        if (busy) return
        busy = true
        try {
            val client = requireNotNull(appViewModel.octopusClient) { "设备中心未连接" }
            client.taskWorkspace(command, args)
            error = ""
            if (command == "submit") {
                text = ""
                stages = emptyList()
                submissionId = UUID.randomUUID().toString()
            }
            refresh()
        } catch (timeout: TimeoutCancellationException) {
            XLog.e("DeviceTasks", "Task workspace action response timed out", timeout)
            error = "响应超时，请刷新任务状态后重试"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "操作失败"
        } finally {
            busy = false
        }
    }
}

@Composable
private fun DeviceTasksScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { TaskWorkspaceState() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    val perform: (String, Map<String, Any?>) -> Unit = { command, args ->
        scope.launch { state.perform(command, args) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("跨端任务", style = MaterialTheme.typography.headlineSmall)
        Text("与电脑共享任务进度和结果。手机可操作本机，以及设备中心明确授权的其他设备。")
        TaskSubmission(state) { perform("submit", it) }
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.loadError.isNotBlank()) Text(state.loadError, color = MaterialTheme.colorScheme.error)
        if (state.tasks.isEmpty()) Text("暂无可显示的任务")
        state.tasks.forEach { task -> DeviceTaskCard(task, state.busy, perform) }
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun TaskSubmission(state: TaskWorkspaceState, submit: (Map<String, Any?>) -> Unit) {
    Row {
        Checkbox(checked = state.stages.isNotEmpty(), enabled = !state.busy, onCheckedChange = {
            state.stages = if (it) listOf(StageDraft(state.target), StageDraft()) else emptyList()
            state.submissionId = UUID.randomUUID().toString()
        })
        Text("多设备接续")
    }
    if (state.stages.isNotEmpty()) StageSubmission(state)
    else state.devices.forEach { device ->
        TextButton(enabled = !state.busy && device.get("online").asBoolean, onClick = {
            state.target = device.string("id")
            state.submissionId = UUID.randomUUID().toString()
        }) {
            val selected = if (state.target == device.string("id")) "✓ " else ""
            Text("$selected${device.string("id")} · ${device.string("platform")}")
        }
    }
    OutlinedTextField(
        value = state.text, enabled = !state.busy,
        onValueChange = {
            if (it.length <= MAX_TASK_LENGTH) {
                state.text = it
                state.submissionId = UUID.randomUUID().toString()
            }
        },
        label = { Text("任务内容") }, modifier = Modifier.fillMaxWidth(),
    )
    val firstTarget = state.stages.firstOrNull()?.deviceId ?: state.target
    val online = state.devices.any { it.string("id") == firstTarget && it.get("online").asBoolean }
    val complete = state.stages.all { it.deviceId.isNotBlank() && it.task.isNotBlank() }
    Button(enabled = !state.busy && state.text.isNotBlank() && online && complete, onClick = {
        val target = if (state.stages.isEmpty()) mapOf("device_id" to state.target)
        else mapOf("stages" to state.stages.map { mapOf("device_id" to it.deviceId, "task" to it.task) })
        submit(mapOf("id" to state.submissionId, "task" to state.text) + target)
    }) { Text("生成执行计划") }
}

@Composable
private fun DeviceTaskCard(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    val state = task.string("status")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.string("task"), style = MaterialTheme.typography.titleMedium)
            Text("${task.string("source_device").ifBlank { "电脑" }} → ${task.string("device_id")}")
            val progress = "${task.get("current_step").asInt} / ${task.getAsJsonArray("steps").size()}"
            Text("${taskLabels[state] ?: state} · 已确认 $progress 步")
            if (task.string("error").isNotBlank()) Text(task.string("error"), color = MaterialTheme.colorScheme.error)
            StageProgress(task)
            TaskOutcomeReview(task, busy, perform)
            TaskPlanAndResults(task)
            TaskActions(task, busy, perform)
        }
    }
}

@Composable
private fun TaskPlanAndResults(task: JsonObject) {
    val steps = task.getAsJsonArray("steps")
    var expanded by remember(task.string("id"), task.string("status")) {
        mutableStateOf(task.string("status") == "awaiting_approval")
    }
    if (steps.size() > 0) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起执行计划" else "查看执行计划") }
        if (expanded) steps.forEachIndexed { index, step ->
            Text("${index + 1}. ${step.asJsonObject.string("action")}\n${step.asJsonObject.get("arguments")}")
        }
    }
    task.getAsJsonArray("results").forEach { entry ->
        val result = entry.asJsonObject
        Text("第 ${result.get("step").asInt + 1} 步：${result.string("error").ifBlank { result.string("summary") }}")
    }
}

@Composable
private fun TaskActions(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    val state = task.string("status")
    val executing = task.get("busy").asBoolean
    val uncertain = task.get("in_flight_step")?.takeUnless { it.isJsonNull }?.asInt
    val args = mapOf("id" to task.string("id"), "revision" to task.string("revision"))
    val ended = state in setOf("succeeded", "failed", "cancelled", "emergency_stopped")
    if (uncertain != null && !executing) Text("第 ${uncertain + 1} 步结果不明。请到目标设备核对；未完成可取消后重新规划。")
    if (state == "awaiting_handoff") {
        val reviewed = task.get("result_review")?.takeIf { it.isJsonObject }?.asJsonObject
        Button(enabled = !busy && !executing && reviewed?.string("outcome") == "achieved",
            onClick = { perform("advance", args) }) { Text("交给下一台设备规划") }
    }
    TaskContinue(task, busy, perform)
    if (state == "running") TextButton(enabled = !busy, onClick = { perform("pause", args) }) { Text("暂停后续步骤") }
    if (!ended) TextButton(enabled = !busy, onClick = { perform("cancel", args) }) { Text("取消任务") }
    if (ended && !executing) TextButton(enabled = !busy, onClick = { perform("remove", args) }) { Text("移除记录") }
}

@Composable
private fun TaskContinue(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    val state = task.string("status")
    val uncertain = task.get("in_flight_step")?.takeUnless { it.isJsonNull }?.asInt
    val args = mapOf("id" to task.string("id"), "revision" to task.string("revision"))
    if (state in setOf("awaiting_approval", "paused", "interrupted")) {
        val label = when {
            uncertain != null -> "已核对完成，继续"
            state == "awaiting_approval" -> "确认计划并执行"
            task.getAsJsonArray("steps").size() == 0 -> "重新生成计划"
            else -> "继续任务"
        }
        Button(enabled = !busy && !task.get("busy").asBoolean, onClick = {
            val resolution = if (uncertain != null) mapOf("resolution" to "completed") else emptyMap()
            perform(if (state == "awaiting_approval") "approve" else "resume", args + resolution)
        }) { Text(label) }
    }
}

@Composable
private fun TaskOutcomeReview(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    if (task.string("status") !in setOf("succeeded", "awaiting_handoff")) return
    val review = task.get("result_review")?.takeIf { it.isJsonObject }?.asJsonObject
    val outcome = review?.string("outcome").orEmpty()
    Text(when (outcome) {
        "achieved" -> "已由用户确认完成"
        "not_achieved" -> "用户核对：目标尚未完成"
        else -> "结果待确认：步骤已执行，请到目标设备检查实际结果。"
    })
    if (review != null) Text("核对人：${review.string("reviewed_by")}")
    Text("核对只更新结果记录，不会再次执行操作。")
    listOf("achieved" to "我已核对，确认完成", "not_achieved" to "我已核对，尚未完成").forEach { (value, label) ->
        TextButton(enabled = !busy && !task.get("busy").asBoolean && outcome != value, onClick = {
            perform("review_result", mapOf(
                "id" to task.string("id"), "revision" to task.string("revision"), "outcome" to value,
            ))
        }) { Text(label) }
    }
}


@Composable
private fun StageSubmission(state: TaskWorkspaceState) {
    fun update(index: Int, stage: StageDraft) {
        state.stages = state.stages.mapIndexed { i, old -> if (index == i) stage else old }
        state.submissionId = UUID.randomUUID().toString()
    }
    Text("按顺序填写每台设备的目标。核对当前阶段后，再为下一阶段生成计划。")
    state.stages.forEachIndexed { index, stage ->
        Text("第 ${index + 1} 阶段")
        state.devices.forEach { device ->
            TextButton(enabled = !state.busy, onClick = { update(index, stage.copy(deviceId = device.string("id"))) }) {
                val selected = if (stage.deviceId == device.string("id")) "✓ " else ""
                val offline = if (device.get("online").asBoolean) "" else "（离线）"
                Text("$selected${device.string("id")}$offline")
            }
        }
        OutlinedTextField(value = stage.task, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            label = { Text("第 ${index + 1} 阶段任务") }, onValueChange = {
                if (it.length <= MAX_STAGE_TASK_LENGTH) update(index, stage.copy(task = it))
            })
        if (state.stages.size > 2) TextButton(enabled = !state.busy, onClick = {
            state.stages = state.stages.filterIndexed { i, _ -> i != index }
            state.submissionId = UUID.randomUUID().toString()
        }) { Text("移除此阶段") }
    }
    TextButton(enabled = !state.busy && state.stages.size < MAX_STAGES, onClick = {
        state.stages = state.stages + StageDraft()
        state.submissionId = UUID.randomUUID().toString()
    }) { Text("添加阶段") }
}

@Composable
private fun StageProgress(task: JsonObject) {
    val stages = task.getAsJsonArray("stages") ?: return
    val current = task.get("stage_index").asInt
    Text("阶段 ${current + 1} / ${stages.size()} · 当前阶段：${stages[current].asJsonObject.string("task")}")
    stages.forEachIndexed { index, value ->
        val stage = value.asJsonObject
        val label = if (index < current) "已核对" else if (index == current) "当前阶段" else "等待接续"
        Text("${index + 1}. ${stage.string("device_id")} · ${stage.string("task")} · $label")
    }
    var expanded by remember(task.string("id")) { mutableStateOf(false) }
    val history = task.getAsJsonArray("stage_history") ?: return
    if (history.size() > 0) TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起前序结果" else "前序阶段结果")
    }
    if (expanded) history.forEach { value ->
        val stage = value.asJsonObject
        Text("${stage.string("device_id")} · ${stage.string("task")}")
        stage.getAsJsonArray("results").forEach { result ->
            Text(result.asJsonObject.string("error").ifBlank { result.asJsonObject.string("summary") })
        }
    }
}
