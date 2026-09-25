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
    "planning" to "正在生成计划", "awaiting_approval" to "等待确认", "running" to "正在执行",
    "paused" to "已暂停", "interrupted" to "已中断", "succeeded" to "已完成", "failed" to "执行失败",
    "cancelled" to "已取消", "emergency_stopped" to "已停止",
)

private class TaskWorkspaceState {
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
    state.devices.forEach { device ->
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
    val online = state.devices.any { it.string("id") == state.target && it.get("online").asBoolean }
    Button(enabled = !state.busy && state.text.isNotBlank() && online, onClick = {
        submit(mapOf("id" to state.submissionId, "device_id" to state.target, "task" to state.text))
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
