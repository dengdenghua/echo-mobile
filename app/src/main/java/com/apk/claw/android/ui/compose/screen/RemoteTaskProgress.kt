package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style

/** Recorded execution details, with an explicit disclosure control and no estimated percentage. */
@Composable
internal fun RemoteTaskProgress(progress: DotRepository.TaskProgress?) {
    if (progress == null) return
    var expanded by remember { mutableStateOf(false) }
    val hasSteps = progress.recentSteps.isNotEmpty()
    val disclosure = stringResource(if (expanded) R.string.dot_progress_collapse else R.string.dot_progress_expand)
    val phase = stringResource(when (progress.phase) {
        "planning" -> R.string.dot_progress_planning
        "summarizing" -> R.string.dot_progress_summarizing
        "delivering" -> R.string.dot_progress_delivering
        "executing" -> R.string.dot_progress_executing
        else -> R.string.dot_progress_preparing
    })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = hasSteps, onClickLabel = disclosure) { expanded = !expanded }
                .semantics(mergeDescendants = true) { if (hasSteps) stateDescription = disclosure },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(phase, modifier = Modifier.weight(1f), color = Style.accent,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress.executedSteps > 0) Text(
                pluralStringResource(R.plurals.dot_progress_step_count, progress.executedSteps, progress.executedSteps),
                modifier = Modifier.widthIn(max = 140.dp), color = Style.muted,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (hasSteps) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null, modifier = Modifier.size(18.dp), tint = Style.muted)
        }
        if (expanded && hasSteps) progress.recentSteps.forEach { step ->
            Text(stringResource(if (step.success) R.string.dot_progress_step_ok else R.string.dot_progress_step_failed, step.tool),
                color = Style.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
