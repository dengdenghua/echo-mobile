package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style

/** Show recorded steps; no estimated percentage or fabricated live tool activity. */
@Composable
internal fun RemoteTaskProgress(progress: DotRepository.TaskProgress?) {
    if (progress == null) return
    var expanded by remember { mutableStateOf(false) }
    val phase = stringResource(when (progress.phase) {
        "planning" -> R.string.dot_progress_planning
        "summarizing" -> R.string.dot_progress_summarizing
        "delivering" -> R.string.dot_progress_delivering
        "executing" -> R.string.dot_progress_executing
        else -> R.string.dot_progress_preparing
    })
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(if (progress.executedSteps > 0) phase + " · " + pluralStringResource(R.plurals.dot_progress_step_count, progress.executedSteps, progress.executedSteps) else phase,
            modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = progress.recentSteps.isNotEmpty()) { expanded = !expanded },
            color = Style.accent, fontSize = 11.sp)
        if (expanded) progress.recentSteps.forEach { step ->
            Text(stringResource(if (step.success) R.string.dot_progress_step_ok else R.string.dot_progress_step_failed, step.tool),
                color = Style.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
