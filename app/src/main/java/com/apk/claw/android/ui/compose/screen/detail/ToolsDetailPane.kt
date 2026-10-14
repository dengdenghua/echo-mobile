package com.apk.claw.android.ui.compose.screen.detail

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.ToolStatusIcon
import com.apk.claw.android.ui.compose.screen.ChatMessage
import com.apk.claw.android.ui.compose.screen.toolCallSummary
import com.apk.claw.android.ui.compose.screen.toolExecutionSummary
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.launch

private const val PANE_HEIGHT_FRACTION = 0.65f
private val LOG_HEIGHT = 240.dp

/** Live execution snapshots, with independent scroll and selection for each tool's output. */
class ToolsDetailPane(
    calls: List<ChatMessage.ToolCall> = emptyList(),
    private val callsProvider: () -> List<ChatMessage.ToolCall> = { calls },
) : DetailPane {
    override val title: String = "工具执行详情"

    @Composable
    override fun Render() {
        val calls = callsProvider()
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val maxHeight = with(LocalDensity.current) { windowHeight.toDp() * PANE_HEIGHT_FRACTION }
        Column(Modifier.fillMaxWidth()) {
            Text(
                if (calls.isEmpty()) stringResource(R.string.tool_detail_empty) else toolExecutionSummary(calls),
                fontSize = OctopusType.tag, color = OctopusColors.TextMuted,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                items(calls, key = { it.id }) { call -> ToolCallRow(call) }
            }
        }
    }

    @Composable
    private fun ToolCallRow(call: ChatMessage.ToolCall) {
        var expanded by remember(call.id) { mutableStateOf(true) }
        val arguments = remember(call.args) { toolArguments(call.args) }
        Surface(
            shape = OctopusShape.small,
            color = OctopusColors.SurfaceDeep,
            modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.xs),
        ) {
            Column(Modifier.padding(OctopusSpacing.sm)) {
                ToolCallHeader(call, expanded) { expanded = !expanded }
                if (expanded) {
                    arguments.command?.let { ArgumentSection(R.string.tool_detail_command, it) }
                    arguments.code?.let { ArgumentSection(R.string.tool_detail_code, it) }
                    arguments.directory?.let { ArgumentSection(R.string.tool_detail_directory, it) }
                    arguments.remaining?.let { ArgumentSection(R.string.tool_detail_arguments, it) }
                    ToolOutput(call)
                } else {
                    Text(toolCallSummary(call), fontSize = OctopusType.tag,
                        color = OctopusColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun ToolCallHeader(call: ChatMessage.ToolCall, expanded: Boolean, onToggle: () -> Unit) {
        val toggleLabel = stringResource(if (expanded) R.string.tool_detail_collapse else R.string.tool_detail_expand)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(
                onClickLabel = toggleLabel,
                onClick = onToggle,
            ).padding(vertical = OctopusSpacing.sm),
        ) {
            ToolStatusIcon(call.status)
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(call.toolName, fontSize = OctopusType.caption, color = OctopusColors.TextSecondary,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            call.durationMs?.let {
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text("${it}ms", fontSize = OctopusType.tag, color = OctopusColors.TextMuted)
            }
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(if (expanded) "⌃" else "⌄", color = OctopusColors.TextMuted)
        }
    }
}

@Composable
private fun ArgumentSection(label: Int, value: String) {
    SectionHeader(stringResource(label), value)
    SelectionContainer {
        Text(value, fontSize = OctopusType.tag, color = OctopusColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().heightIn(max = LOG_HEIGHT).verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun ToolOutput(call: ChatMessage.ToolCall) {
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val running = call.status == ChatMessage.ToolCallStatus.RUNNING
    var following by remember { mutableStateOf(running) }
    // User gestures pause before the next snapshot; returning to the bottom resumes following.
    LaunchedEffect(dragged, scroll.isScrollInProgress) {
        if (dragged) following = false
        else if (!scroll.isScrollInProgress && !scroll.canScrollForward) following = running
    }
    LaunchedEffect(call.result, scroll.maxValue, following, dragged) {
        if (following && !dragged) scroll.scrollTo(scroll.maxValue)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(stringResource(R.string.tool_detail_output), call.result, Modifier.weight(1f))
        if (running || scroll.canScrollForward) {
            TextButton(onClick = { following = !following }) {
                Text(stringResource(if (following) R.string.tool_detail_pause else R.string.tool_detail_latest),
                    fontSize = OctopusType.tag)
            }
        }
    }
    SelectionContainer {
        Text(call.result ?: stringResource(
            if (running) R.string.tool_detail_waiting else R.string.tool_detail_no_output),
            fontSize = OctopusType.tag, color = OctopusColors.TextSecondary, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().heightIn(max = LOG_HEIGHT).verticalScroll(scroll))
    }
}

@Composable
private fun SectionHeader(label: String, copyValue: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = OctopusType.tag, color = OctopusColors.TextMuted, modifier = Modifier.weight(1f))
        if (!copyValue.isNullOrBlank()) {
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, copyValue))) }
            }) {
                Text(stringResource(R.string.tool_detail_copy), fontSize = OctopusType.tag)
            }
        }
    }
}
