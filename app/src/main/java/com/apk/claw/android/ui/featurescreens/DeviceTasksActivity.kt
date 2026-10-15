package com.apk.claw.android.ui.featurescreens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.utils.XLog
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import com.apk.claw.android.ui.compose.theme.EchoFieldColors

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
    "planning" to R.string.devicetasks_status_planning,
    "awaiting_handoff" to R.string.devicetasks_status_awaiting_handoff,
    "awaiting_approval" to R.string.devicetasks_status_awaiting_approval,
    "running" to R.string.devicetasks_status_running,
    "paused" to R.string.devicetasks_status_paused,
    "interrupted" to R.string.devicetasks_status_interrupted,
    "succeeded" to R.string.devicetasks_status_succeeded,
    "failed" to R.string.devicetasks_status_failed,
    "cancelled" to R.string.devicetasks_status_cancelled,
    "emergency_stopped" to R.string.devicetasks_status_emergency_stopped,
)

private data class StageDraft(val deviceId: String = "", val task: String = "")
private const val MAX_STAGES = 8
private const val MAX_STAGE_TASK_LENGTH = 1024

private class TaskWorkspaceState(private val context: Context) {
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
            val client = requireNotNull(appViewModel.octopusClient) {
                context.getString(R.string.devicetasks_error_connect_in_settings)
            }
            tasks = client.taskWorkspace("list").getAsJsonArray("tasks").map { it.asJsonObject }
            devices = client.taskWorkspace("devices").getAsJsonArray("devices").map { it.asJsonObject }
            loadError = ""
        } catch (timeout: TimeoutCancellationException) {
            XLog.e("DeviceTasks", "Task workspace polling timed out", timeout)
            tasks = emptyList()
            devices = emptyList()
            loadError = context.getString(R.string.devicetasks_error_load_timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tasks = emptyList()
            devices = emptyList()
            loadError = failure.message ?: context.getString(R.string.devicetasks_error_hub_unavailable)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Surface protocol/transport errors; do not swallow activity cancellation.
    suspend fun perform(command: String, args: Map<String, Any?>) {
        if (busy) return
        busy = true
        try {
            val client = requireNotNull(appViewModel.octopusClient) {
                context.getString(R.string.devicetasks_error_hub_not_connected)
            }
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
            error = context.getString(R.string.devicetasks_error_action_timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: context.getString(R.string.devicetasks_error_action_failed)
        } finally {
            busy = false
        }
    }
}

@Composable
private fun DeviceTasksScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val state = remember { TaskWorkspaceState(context) }
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
    // Surface 提供页面底色和内容色:没有它 Text 默认是黑色,Graphite 下标题和说明几乎看不见
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            // safeDrawingPadding:标题让开状态栏(之前压在时间/电量上),底部和横屏侧边让开导航栏
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.devicetasks_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.devicetasks_intro))
            TaskSubmission(state) { perform("submit", it) }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            if (state.loadError.isNotBlank()) Text(state.loadError, color = MaterialTheme.colorScheme.error)
            if (state.tasks.isEmpty()) Text(stringResource(R.string.devicetasks_empty))
            state.tasks.forEach { task -> DeviceTaskCard(task, state.busy, perform) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
        }
    }
}

@Composable
private fun TaskSubmission(state: TaskWorkspaceState, submit: (Map<String, Any?>) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = state.stages.isNotEmpty(), enabled = !state.busy, onCheckedChange = {
            state.stages = if (it) listOf(StageDraft(state.target), StageDraft()) else emptyList()
            state.submissionId = UUID.randomUUID().toString()
        })
        Text(stringResource(R.string.devicetasks_multi_device))
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
        label = { Text(stringResource(R.string.devicetasks_task_label)) }, modifier = Modifier.fillMaxWidth(),
        colors = EchoFieldColors.outlined(),
    )
    val firstTarget = state.stages.firstOrNull()?.deviceId ?: state.target
    val online = state.devices.any { it.string("id") == firstTarget && it.get("online").asBoolean }
    val complete = state.stages.all { it.deviceId.isNotBlank() && it.task.isNotBlank() }
    Button(enabled = !state.busy && state.text.isNotBlank() && online && complete, onClick = {
        val target = if (state.stages.isEmpty()) mapOf("device_id" to state.target)
        else mapOf("stages" to state.stages.map { mapOf("device_id" to it.deviceId, "task" to it.task) })
        submit(mapOf("id" to state.submissionId, "task" to state.text) + target)
    }) { Text(stringResource(R.string.devicetasks_generate_plan)) }
}

@Composable
private fun DeviceTaskCard(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    val state = task.string("status")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.string("task"), style = MaterialTheme.typography.titleMedium)
            val computer = stringResource(R.string.devicetasks_source_computer)
            Text("${task.string("source_device").ifBlank { computer }} → ${task.string("device_id")}")
            val progress = "${task.get("current_step").asInt} / ${task.getAsJsonArray("steps").size()}"
            val label = taskLabels[state]?.let { stringResource(it) } ?: state
            Text(stringResource(R.string.devicetasks_status_progress, label, progress))
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
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.devicetasks_hide_plan else R.string.devicetasks_show_plan))
        }
        if (expanded) steps.forEachIndexed { index, step ->
            Text("${index + 1}. ${step.asJsonObject.string("action")}\n${step.asJsonObject.get("arguments")}")
        }
    }
    task.getAsJsonArray("results").forEach { entry ->
        val result = entry.asJsonObject
        val outcome = result.string("error").ifBlank { result.string("summary") }
        Text(stringResource(R.string.devicetasks_step_result, result.get("step").asInt + 1, outcome))
    }
}

@Composable
private fun TaskActions(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    val state = task.string("status")
    val executing = task.get("busy").asBoolean
    val uncertain = task.get("in_flight_step")?.takeUnless { it.isJsonNull }?.asInt
    val args = mapOf("id" to task.string("id"), "revision" to task.string("revision"))
    val ended = state in setOf("succeeded", "failed", "cancelled", "emergency_stopped")
    if (uncertain != null && !executing) Text(stringResource(R.string.devicetasks_step_uncertain, uncertain + 1))
    if (state == "awaiting_handoff") {
        val reviewed = task.get("result_review")?.takeIf { it.isJsonObject }?.asJsonObject
        Button(enabled = !busy && !executing && reviewed?.string("outcome") == "achieved",
            onClick = { perform("advance", args) }) { Text(stringResource(R.string.devicetasks_advance)) }
    }
    TaskContinue(task, busy, perform)
    if (state == "running") TextButton(enabled = !busy, onClick = { perform("pause", args) }) {
        Text(stringResource(R.string.devicetasks_pause))
    }
    if (!ended) TextButton(enabled = !busy, onClick = { perform("cancel", args) }) {
        Text(stringResource(R.string.devicetasks_cancel))
    }
    if (ended && !executing) TextButton(enabled = !busy, onClick = { perform("remove", args) }) {
        Text(stringResource(R.string.devicetasks_remove))
    }
}

@Composable
private fun TaskContinue(task: JsonObject, busy: Boolean, perform: (String, Map<String, Any?>) -> Unit) {
    val state = task.string("status")
    val uncertain = task.get("in_flight_step")?.takeUnless { it.isJsonNull }?.asInt
    val args = mapOf("id" to task.string("id"), "revision" to task.string("revision"))
    if (state in setOf("awaiting_approval", "paused", "interrupted")) {
        val label = stringResource(when {
            uncertain != null -> R.string.devicetasks_continue_checked
            state == "awaiting_approval" -> R.string.devicetasks_approve
            task.getAsJsonArray("steps").size() == 0 -> R.string.devicetasks_regenerate
            else -> R.string.devicetasks_resume
        })
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
    Text(stringResource(when (outcome) {
        "achieved" -> R.string.devicetasks_outcome_achieved
        "not_achieved" -> R.string.devicetasks_outcome_not_achieved
        else -> R.string.devicetasks_outcome_pending
    }))
    if (review != null) Text(stringResource(R.string.devicetasks_reviewed_by, review.string("reviewed_by")))
    Text(stringResource(R.string.devicetasks_review_note))
    listOf(
        "achieved" to R.string.devicetasks_review_achieved,
        "not_achieved" to R.string.devicetasks_review_not_achieved,
    ).forEach { (value, label) ->
        TextButton(enabled = !busy && !task.get("busy").asBoolean && outcome != value, onClick = {
            perform("review_result", mapOf(
                "id" to task.string("id"), "revision" to task.string("revision"), "outcome" to value,
            ))
        }) { Text(stringResource(label)) }
    }
}


@Composable
private fun StageSubmission(state: TaskWorkspaceState) {
    fun update(index: Int, stage: StageDraft) {
        state.stages = state.stages.mapIndexed { i, old -> if (index == i) stage else old }
        state.submissionId = UUID.randomUUID().toString()
    }
    Text(stringResource(R.string.devicetasks_stages_hint))
    state.stages.forEachIndexed { index, stage ->
        Text(stringResource(R.string.devicetasks_stage_title, index + 1))
        state.devices.forEach { device ->
            TextButton(enabled = !state.busy, onClick = { update(index, stage.copy(deviceId = device.string("id"))) }) {
                val selected = if (stage.deviceId == device.string("id")) "✓ " else ""
                val name = if (device.get("online").asBoolean) device.string("id")
                else stringResource(R.string.devicetasks_device_offline, device.string("id"))
                Text("$selected$name")
            }
        }
        OutlinedTextField(value = stage.task, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.devicetasks_stage_task_label, index + 1)) }, onValueChange = {
                if (it.length <= MAX_STAGE_TASK_LENGTH) update(index, stage.copy(task = it))
            },
            colors = EchoFieldColors.outlined())
        if (state.stages.size > 2) TextButton(enabled = !state.busy, onClick = {
            state.stages = state.stages.filterIndexed { i, _ -> i != index }
            state.submissionId = UUID.randomUUID().toString()
        }) { Text(stringResource(R.string.devicetasks_remove_stage)) }
    }
    TextButton(enabled = !state.busy && state.stages.size < MAX_STAGES, onClick = {
        state.stages = state.stages + StageDraft()
        state.submissionId = UUID.randomUUID().toString()
    }) { Text(stringResource(R.string.devicetasks_add_stage)) }
}

@Composable
private fun StageProgress(task: JsonObject) {
    val stages = task.getAsJsonArray("stages") ?: return
    val current = task.get("stage_index").asInt
    val currentTask = stages[current].asJsonObject.string("task")
    Text(stringResource(R.string.devicetasks_stage_progress, current + 1, stages.size(), currentTask))
    stages.forEachIndexed { index, value ->
        val stage = value.asJsonObject
        val label = stringResource(when {
            index < current -> R.string.devicetasks_stage_checked
            index == current -> R.string.devicetasks_stage_current
            else -> R.string.devicetasks_stage_waiting
        })
        Text("${index + 1}. ${stage.string("device_id")} · ${stage.string("task")} · $label")
    }
    var expanded by remember(task.string("id")) { mutableStateOf(false) }
    val history = task.getAsJsonArray("stage_history") ?: return
    if (history.size() > 0) TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.devicetasks_hide_history else R.string.devicetasks_show_history))
    }
    if (expanded) history.forEach { value ->
        val stage = value.asJsonObject
        Text("${stage.string("device_id")} · ${stage.string("task")}")
        stage.getAsJsonArray("results").forEach { result ->
            Text(result.asJsonObject.string("error").ifBlank { result.asJsonObject.string("summary") })
        }
    }
}
