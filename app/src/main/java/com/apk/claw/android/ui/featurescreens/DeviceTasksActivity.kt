// A Compose screen is many small private composables (composer, device picker, stages, task card, actions);
// splitting the file would only scatter one screen.
@file:Suppress("TooManyFunctions")

package com.apk.claw.android.ui.featurescreens

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.ui.compose.component.EchoButtonBusyLine
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.EchoProgressLineHeight
import com.apk.claw.android.ui.compose.component.EchoProgressLineInlineWidth
import com.apk.claw.android.ui.compose.theme.EchoFieldColors
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
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
private const val MAX_STAGES = 8
private const val MIN_STAGES = 2
private const val MAX_STAGE_TASK_LENGTH = 1024
private const val TASK_FIELD_MIN_LINES = 2

/** Line height for the 12 sp captions on this screen. */
private val CaptionLineHeight = 17.sp

private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
private fun JsonObject.flag(name: String): Boolean = get(name).asBoolean
private fun JsonObject.inFlightStep(): Int? = get("in_flight_step")?.takeUnless { it.isJsonNull }?.asInt
private fun JsonObject.resultReview(): JsonObject? = get("result_review")?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.revisionArgs(): Map<String, Any?> = mapOf("id" to string("id"), "revision" to string("revision"))

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
private val endedStatuses = setOf("succeeded", "failed", "cancelled", "emergency_stopped")
private val resumableStatuses = setOf("awaiting_approval", "paused", "interrupted")
private val reviewableStatuses = setOf("succeeded", "awaiting_handoff")
private val reviewChoices = listOf(
    "achieved" to R.string.devicetasks_review_achieved,
    "not_achieved" to R.string.devicetasks_review_not_achieved,
)

/**
 * Echo Core light language (BRAND.md): working = `core`, waiting on the user = `warning`, broken = `breach`,
 * settled = neutral. No green: a finished task is simply calm.
 */
private fun statusColor(status: String): Color = when (status) {
    "planning", "running" -> OctopusColors.Accent
    "awaiting_approval", "awaiting_handoff" -> FWarning
    "failed", "emergency_stopped", "interrupted" -> FError
    else -> FMuted
}

private typealias TaskCommand = (String, Map<String, Any?>) -> Unit

private data class StageDraft(val deviceId: String = "", val task: String = "")

private class TaskWorkspaceState(private val context: Context) {
    var stages by mutableStateOf<List<StageDraft>>(emptyList())
    var tasks by mutableStateOf<List<JsonObject>>(emptyList())
    var devices by mutableStateOf<List<JsonObject>>(emptyList())
    var target by mutableStateOf("")
    var text by mutableStateOf("")
    var error by mutableStateOf("")
    var loadError by mutableStateOf("")
    var busy by mutableStateOf(false)

    /** The command [perform] is waiting on, so the button that sent it can show the busy line. */
    var busyCommand by mutableStateOf("")

    /** False until the first fetch has answered (tasks or an error); until then the page shows the loading line. */
    var loaded by mutableStateOf(false)
    var retrying by mutableStateOf(false)
    var submissionId = UUID.randomUUID().toString()

    /** A changed draft is a different submission: it gets a new idempotency id. */
    fun newSubmission() {
        submissionId = UUID.randomUUID().toString()
    }

    fun updateStage(index: Int, stage: StageDraft) {
        stages = stages.mapIndexed { i, old -> if (index == i) stage else old }
        newSubmission()
    }

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
        loaded = true
    }

    /** The error state's Retry: fetch now instead of waiting for the next poll. */
    suspend fun retry() {
        if (retrying) return
        retrying = true
        try {
            refresh()
        } finally {
            retrying = false
        }
    }

    @Suppress("TooGenericExceptionCaught") // Surface protocol/transport errors; do not swallow activity cancellation.
    suspend fun perform(command: String, args: Map<String, Any?>) {
        if (busy) return
        busy = true
        busyCommand = command
        try {
            val client = requireNotNull(appViewModel.octopusClient) {
                context.getString(R.string.devicetasks_error_hub_not_connected)
            }
            client.taskWorkspace(command, args)
            error = ""
            if (command == "submit") {
                text = ""
                stages = emptyList()
                newSubmission()
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
            busyCommand = ""
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
    val perform: TaskCommand = { command, args ->
        scope.launch { state.perform(command, args) }
    }
    FeatureScaffold(title = stringResource(R.string.devicetasks_title), onBack = onBack) {
        if (state.loaded) {
            DeviceTasksContent(state, perform, onRetry = { scope.launch { state.retry() } })
        } else {
            // First fetch in flight: the thin core line (no spinner, and no "no tasks" before we know)
            EchoLoadingPanel(caption = stringResource(R.string.devicetasks_loading))
        }
    }
}

@Composable
private fun ColumnScope.DeviceTasksContent(state: TaskWorkspaceState, perform: TaskCommand, onRetry: () -> Unit) {
    // An action in flight: a thin core line under the top bar. The slot keeps its height so nothing jumps.
    Box(Modifier.fillMaxWidth().height(EchoProgressLineHeight)) {
        if (state.busy) EchoProgressLine()
    }
    // Pinned above the list, so a failed approve on a card far down the page is still seen
    if (state.error.isNotBlank()) {
        FWarningNote(
            state.error,
            color = FError,
            modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        )
    }
    Column(
        Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(bottom = OctopusSpacing.xxl),
    ) {
        Text(
            stringResource(R.string.devicetasks_intro),
            color = FSub, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
            modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
        )
        FSectionTitle(stringResource(R.string.devicetasks_section_new))
        TaskComposer(state) { perform("submit", it) }
        FSectionTitle(stringResource(R.string.devicetasks_section_tasks))
        TaskList(state, perform, onRetry)
    }
}

@Composable
private fun TaskList(state: TaskWorkspaceState, perform: TaskCommand, onRetry: () -> Unit) {
    when {
        // The hub could not be reached: say what happened and offer a retry, never an empty list
        state.loadError.isNotBlank() -> TaskLoadError(state.loadError, state.retrying, onRetry)
        state.tasks.isEmpty() -> FEmpty(stringResource(R.string.devicetasks_empty))
        else -> state.tasks.forEach { task ->
            key(task.string("id")) { DeviceTaskCard(task, state.busy, perform) }
        }
    }
}

@Composable
private fun TaskLoadError(message: String, retrying: Boolean, onRetry: () -> Unit) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CloudOff, contentDescription = null, tint = FError,
                modifier = Modifier.size(OctopusIconSize.small),
            )
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(
                stringResource(R.string.devicetasks_load_failed),
                color = FText, fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(message, color = FError, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight)
        Spacer(Modifier.height(OctopusSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.common_retry), enabled = !retrying, onClick = onRetry)
            if (retrying) {
                Spacer(Modifier.width(OctopusSpacing.sm))
                EchoProgressLine(Modifier.width(EchoProgressLineInlineWidth))
            }
        }
    }
}

// ── Composer ──

@Composable
private fun TaskComposer(state: TaskWorkspaceState, submit: (Map<String, Any?>) -> Unit) {
    FCard {
        MultiDeviceSwitch(state)
        Spacer(Modifier.height(OctopusSpacing.md))
        if (state.stages.isNotEmpty()) StageSubmission(state) else TargetDevicePicker(state)
        Spacer(Modifier.height(OctopusSpacing.md))
        OutlinedTextField(
            value = state.text,
            enabled = !state.busy,
            onValueChange = {
                if (it.length <= MAX_TASK_LENGTH) {
                    state.text = it
                    state.newSubmission()
                }
            },
            label = { Text(stringResource(R.string.devicetasks_task_label)) },
            minLines = TASK_FIELD_MIN_LINES,
            textStyle = TextStyle(fontSize = OctopusType.body),
            shape = OctopusShape.small,
            colors = EchoFieldColors.outlined(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(OctopusSpacing.md))
        SubmitButton(state, submit)
    }
}

/** The whole row toggles (a bigger target, one TalkBack node); the switch only shows the state. */
@Composable
private fun MultiDeviceSwitch(state: TaskWorkspaceState) {
    val multi = state.stages.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = multi,
            enabled = !state.busy,
            role = Role.Switch,
            onValueChange = {
                state.stages = if (it) listOf(StageDraft(state.target), StageDraft()) else emptyList()
                state.newSubmission()
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.devicetasks_multi_device),
            color = FText, fontSize = OctopusType.body, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(OctopusSpacing.md))
        Switch(checked = multi, onCheckedChange = null, enabled = !state.busy)
    }
}

@Composable
private fun TargetDevicePicker(state: TaskWorkspaceState) {
    FieldLabel(stringResource(R.string.devicetasks_target_device))
    DeviceOptions(state.devices, selectedId = state.target, enabled = { !state.busy && it.flag("online") }) { id ->
        state.target = id
        state.newSubmission()
    }
}

@Composable
private fun SubmitButton(state: TaskWorkspaceState, submit: (Map<String, Any?>) -> Unit) {
    val firstTarget = state.stages.firstOrNull()?.deviceId ?: state.target
    val online = state.devices.any { it.string("id") == firstTarget && it.flag("online") }
    val complete = state.stages.all { it.deviceId.isNotBlank() && it.task.isNotBlank() }
    val ready = state.text.isNotBlank() && online && complete
    PrimaryButton(
        stringResource(R.string.devicetasks_generate_plan),
        enabled = !state.busy && ready,
        busy = state.busy && state.busyCommand == "submit",
    ) {
        val target = if (state.stages.isEmpty()) {
            mapOf("device_id" to state.target)
        } else {
            mapOf("stages" to state.stages.map { mapOf("device_id" to it.deviceId, "task" to it.task) })
        }
        submit(mapOf("id" to state.submissionId, "task" to state.text) + target)
    }
}

@Composable
private fun DeviceOptions(
    devices: List<JsonObject>,
    selectedId: String,
    enabled: (JsonObject) -> Boolean,
    onSelect: (String) -> Unit,
) {
    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.devicetasks_no_devices),
            color = FMuted, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.xs)) {
        devices.forEach { device ->
            val id = device.string("id")
            DeviceOption(device, selected = selectedId == id, enabled = enabled(device)) { onSelect(id) }
        }
    }
}

/** One device as a radio row: a `core` ring and outline when selected; an offline device says so. */
@Composable
private fun DeviceOption(device: JsonObject, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val id = device.string("id")
    val name = if (device.flag("online")) id else stringResource(R.string.devicetasks_device_offline, id)
    val platform = device.string("platform")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.small)
            .border(1.dp, if (selected) OctopusColors.Accent else OctopusColors.BorderHigh, OctopusShape.small)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(end = OctopusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = OctopusColors.Accent, unselectedColor = FMuted),
            modifier = Modifier.padding(OctopusSpacing.md),
        )
        Column(Modifier.weight(1f).padding(vertical = OctopusSpacing.sm)) {
            Text(
                name,
                color = if (enabled) FText else FMuted,
                fontSize = OctopusType.label,
                fontFamily = FontFamily.Monospace,
            )
            if (platform.isNotBlank()) Text(platform, color = FMuted, fontSize = OctopusType.caption)
        }
    }
}

@Composable
private fun StageSubmission(state: TaskWorkspaceState) {
    Text(
        stringResource(R.string.devicetasks_stages_hint),
        color = FSub, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
    )
    state.stages.forEachIndexed { index, stage ->
        HorizontalDivider(color = FBorder, modifier = Modifier.padding(vertical = OctopusSpacing.md))
        StageDraftEditor(state, index, stage)
    }
    Spacer(Modifier.height(OctopusSpacing.sm))
    TextButton(
        enabled = !state.busy && state.stages.size < MAX_STAGES,
        onClick = {
            state.stages = state.stages + StageDraft()
            state.newSubmission()
        },
        shape = OctopusShape.small,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(Modifier.width(OctopusSpacing.xs))
        Text(stringResource(R.string.devicetasks_add_stage), fontSize = OctopusType.label)
    }
}

@Composable
private fun StageDraftEditor(state: TaskWorkspaceState, index: Int, stage: StageDraft) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.devicetasks_stage_title, index + 1),
            color = FText, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (state.stages.size > MIN_STAGES) {
            // Removing a draft stage deletes what was typed into it: breach
            TextButton(
                enabled = !state.busy,
                onClick = {
                    state.stages = state.stages.filterIndexed { i, _ -> i != index }
                    state.newSubmission()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = FError),
                shape = OctopusShape.small,
            ) { Text(stringResource(R.string.devicetasks_remove_stage), fontSize = OctopusType.caption) }
        }
    }
    Spacer(Modifier.height(OctopusSpacing.xs))
    DeviceOptions(state.devices, selectedId = stage.deviceId, enabled = { !state.busy }) { id ->
        state.updateStage(index, stage.copy(deviceId = id))
    }
    Spacer(Modifier.height(OctopusSpacing.sm))
    OutlinedTextField(
        value = stage.task,
        enabled = !state.busy,
        onValueChange = { if (it.length <= MAX_STAGE_TASK_LENGTH) state.updateStage(index, stage.copy(task = it)) },
        label = { Text(stringResource(R.string.devicetasks_stage_task_label, index + 1)) },
        textStyle = TextStyle(fontSize = OctopusType.body),
        shape = OctopusShape.small,
        colors = EchoFieldColors.outlined(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Task card ──

@Composable
private fun DeviceTaskCard(task: JsonObject, busy: Boolean, perform: TaskCommand) {
    FCard {
        Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
            TaskHeader(task)
            val error = task.string("error")
            if (error.isNotBlank()) {
                Text(error, color = FError, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight)
            }
            StageProgress(task)
            TaskOutcomeReview(task, busy, perform)
            TaskPlanAndResults(task)
            TaskActions(task, busy, perform)
        }
    }
}

@Composable
private fun TaskHeader(task: JsonObject) {
    val status = task.string("status")
    Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.xs)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                task.string("task"),
                color = FText, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(OctopusSpacing.sm))
            FPill(taskLabels[status]?.let { stringResource(it) } ?: status, statusColor(status))
        }
        val computer = stringResource(R.string.devicetasks_source_computer)
        Text(
            "${task.string("source_device").ifBlank { computer }} → ${task.string("device_id")}",
            color = FSub, fontSize = OctopusType.caption,
        )
        val stepCount = task.getAsJsonArray("steps").size()
        if (stepCount > 0) {
            Text(
                stringResource(R.string.devicetasks_steps_confirmed, task.get("current_step").asInt, stepCount),
                color = FMuted, fontSize = OctopusType.caption,
            )
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
        DisclosureToggle(expanded, R.string.devicetasks_show_plan, R.string.devicetasks_hide_plan) {
            expanded = !expanded
        }
        if (expanded) steps.forEachIndexed { index, step -> PlanStep(index, step.asJsonObject) }
    }
    task.getAsJsonArray("results").forEach { entry ->
        val result = entry.asJsonObject
        val error = result.string("error")
        val outcome = error.ifBlank { result.string("summary") }
        Text(
            stringResource(R.string.devicetasks_step_result, result.get("step").asInt + 1, outcome),
            color = if (error.isNotBlank()) FError else FSub,
            fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
        )
    }
}

@Composable
private fun PlanStep(index: Int, step: JsonObject) {
    Column(Modifier.padding(start = OctopusSpacing.sm)) {
        Text(
            "${index + 1}. ${step.string("action")}",
            color = FText, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
        )
        step.get("arguments")?.takeUnless { it.isJsonNull }?.let { arguments ->
            Text(
                arguments.toString(),
                color = FMuted, fontSize = OctopusType.caption, fontFamily = FontFamily.Monospace,
                lineHeight = CaptionLineHeight,
            )
        }
    }
}

@Composable
private fun TaskActions(task: JsonObject, busy: Boolean, perform: TaskCommand) {
    val status = task.string("status")
    val executing = task.flag("busy")
    val uncertain = task.inFlightStep()
    val args = task.revisionArgs()
    // The step may or may not have happened: the user has to check, so this is a caution, not an error
    if (uncertain != null && !executing) {
        FWarningNote(stringResource(R.string.devicetasks_step_uncertain, uncertain + 1))
    }
    if (status == "awaiting_handoff") {
        val achieved = task.resultReview()?.string("outcome") == "achieved"
        PrimaryButton(stringResource(R.string.devicetasks_advance), enabled = !busy && !executing && achieved) {
            perform("advance", args)
        }
    }
    TaskContinue(task, busy, perform)
    TaskSecondaryActions(status, executing, busy) { command -> perform(command, args) }
}

@Composable
private fun TaskContinue(task: JsonObject, busy: Boolean, perform: TaskCommand) {
    val status = task.string("status")
    if (status !in resumableStatuses) return
    val uncertain = task.inFlightStep()
    val label = stringResource(
        when {
            uncertain != null -> R.string.devicetasks_continue_checked
            status == "awaiting_approval" -> R.string.devicetasks_approve
            task.getAsJsonArray("steps").size() == 0 -> R.string.devicetasks_regenerate
            else -> R.string.devicetasks_resume
        },
    )
    PrimaryButton(label, enabled = !busy && !task.flag("busy")) {
        val resolution = if (uncertain != null) mapOf("resolution" to "completed") else emptyMap()
        perform(if (status == "awaiting_approval") "approve" else "resume", task.revisionArgs() + resolution)
    }
}

/** Pause and cancel are neutral; removing the record deletes it, so it is the one breach-red action. */
@Composable
private fun TaskSecondaryActions(status: String, executing: Boolean, busy: Boolean, run: (String) -> Unit) {
    val ended = status in endedStatuses
    val actions = buildList {
        if (status == "running") add(Triple("pause", R.string.devicetasks_pause, FText))
        if (!ended) add(Triple("cancel", R.string.devicetasks_cancel, FText))
        if (ended && !executing) add(Triple("remove", R.string.devicetasks_remove, FError))
    }
    if (actions.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
        actions.forEach { (command, label, color) ->
            SecondaryButton(stringResource(label), enabled = !busy, color = color, modifier = Modifier.weight(1f)) {
                run(command)
            }
        }
    }
}

@Composable
private fun TaskOutcomeReview(task: JsonObject, busy: Boolean, perform: TaskCommand) {
    if (task.string("status") !in reviewableStatuses) return
    val review = task.resultReview()
    val outcome = review?.string("outcome").orEmpty()
    OutcomeLine(outcome)
    if (review != null) {
        Text(
            stringResource(R.string.devicetasks_reviewed_by, review.string("reviewed_by")),
            color = FMuted, fontSize = OctopusType.caption,
        )
    }
    Text(
        stringResource(R.string.devicetasks_review_note),
        color = FMuted, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
    )
    val canReview = !busy && !task.flag("busy")
    Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
        reviewChoices.forEach { (value, label) ->
            SecondaryButton(
                stringResource(label),
                enabled = canReview && outcome != value,
                modifier = Modifier.weight(1f),
            ) { perform("review_result", task.revisionArgs() + ("outcome" to value)) }
        }
    }
}

@Composable
private fun OutcomeLine(outcome: String) {
    when (outcome) {
        "achieved" -> Text(
            stringResource(R.string.devicetasks_outcome_achieved),
            color = OctopusColors.Accent, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
        )
        "not_achieved" -> Text(
            stringResource(R.string.devicetasks_outcome_not_achieved),
            color = FText, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
        )
        // Not checked yet: the user still has to confirm on the device, so amber
        else -> FWarningNote(stringResource(R.string.devicetasks_outcome_pending))
    }
}

@Composable
private fun StageProgress(task: JsonObject) {
    val stages = task.getAsJsonArray("stages") ?: return
    val current = task.get("stage_index").asInt
    val currentTask = stages[current].asJsonObject.string("task")
    Text(
        stringResource(R.string.devicetasks_stage_progress, current + 1, stages.size(), currentTask),
        color = FText, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold,
    )
    stages.forEachIndexed { index, value -> StageRow(index, current, value.asJsonObject) }
    StageHistory(task)
}

@Composable
private fun StageRow(index: Int, current: Int, stage: JsonObject) {
    val (label, color) = when {
        index < current -> R.string.devicetasks_stage_checked to FMuted
        index == current -> R.string.devicetasks_stage_current to OctopusColors.Accent
        else -> R.string.devicetasks_stage_waiting to FMuted
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            "${index + 1}. ${stage.string("device_id")} · ${stage.string("task")}",
            color = FSub, fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(stringResource(label), color = color, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StageHistory(task: JsonObject) {
    val history = task.getAsJsonArray("stage_history")
    var expanded by remember(task.string("id")) { mutableStateOf(false) }
    if (history == null || history.size() == 0) return
    DisclosureToggle(expanded, R.string.devicetasks_show_history, R.string.devicetasks_hide_history) {
        expanded = !expanded
    }
    if (expanded) history.forEach { value -> HistoryStage(value.asJsonObject) }
}

@Composable
private fun HistoryStage(stage: JsonObject) {
    Column(Modifier.padding(start = OctopusSpacing.sm)) {
        Text(
            "${stage.string("device_id")} · ${stage.string("task")}",
            color = FText, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
        )
        stage.getAsJsonArray("results").forEach { value ->
            val result = value.asJsonObject
            val error = result.string("error")
            Text(
                error.ifBlank { result.string("summary") },
                color = if (error.isNotBlank()) FError else FSub,
                fontSize = OctopusType.caption, lineHeight = CaptionLineHeight,
            )
        }
    }
}

// ── Shared controls ──

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = FSub, fontSize = OctopusType.caption, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = OctopusSpacing.xs),
    )
}

/** A show/hide text toggle with a chevron, for the plan and the earlier-stage results. */
@Composable
private fun DisclosureToggle(expanded: Boolean, @StringRes show: Int, @StringRes hide: Int, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        shape = OctopusShape.small,
        contentPadding = PaddingValues(end = OctopusSpacing.sm),
    ) {
        Text(stringResource(if (expanded) hide else show), color = FSub, fontSize = OctopusType.label)
        Spacer(Modifier.width(OctopusSpacing.xs))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null, tint = FSub, modifier = Modifier.size(OctopusIconSize.small),
        )
    }
}

/**
 * The filled main action: monochrome `action` + `on-action` (BRAND.md), never coloured. While [busy] it keeps
 * its filled look and shows the busy line over its (invisible) label, so the button does not change size.
 */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val colors = if (busy) {
        ButtonDefaults.buttonColors(disabledContainerColor = FPrimary, disabledContentColor = FOnPrimary)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = OctopusShape.small,
        colors = colors,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.alpha(if (busy) 0f else 1f),
            )
            if (busy) EchoButtonBusyLine(FOnPrimary)
        }
    }
}

/** An outlined secondary action on a `line-strong` hairline; [color] is FError only for destructive ones. */
@Composable
private fun SecondaryButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    color: Color = FText,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = OctopusShape.small,
        border = BorderStroke(1.dp, if (enabled) OctopusColors.BorderHigh else FBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        modifier = modifier,
    ) {
        Text(text, fontSize = OctopusType.label, textAlign = TextAlign.Center)
    }
}
