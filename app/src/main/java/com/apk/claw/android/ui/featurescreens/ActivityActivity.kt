package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ActivityLog

class ActivityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { ActivityScreen(onBack = { finish() }) }
    }
}

private val cDanger = Color(0xFFFF453B)

private fun outcomeLabel(o: String) = when (o) {
    "success" -> ClawApplication.instance.getString(R.string.channel_msg_tool_success)
    "unverified", "completed" -> "结果待核验"
    "not_achieved" -> "目标尚未完成"
    "cancelled" -> ClawApplication.instance.getString(R.string.activity_outcome_cancelled)
    else -> ClawApplication.instance.getString(R.string.channel_msg_tool_failure)
}

private fun outcomeColor(o: String) = when (o) {
    "success" -> FSuccess
    "cancelled", "unverified", "completed" -> FMuted
    else -> cDanger
}

private fun relTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val d = now - ts
    return when {
        d < 60_000 -> ClawApplication.instance.getString(R.string.activity_time_just_now)
        d < 3_600_000 -> ClawApplication.instance.getString(R.string.activity_time_minutes_ago, d / 60_000)
        d < 86_400_000 -> ClawApplication.instance.getString(R.string.activity_time_hours_ago, d / 3_600_000)
        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

@Composable
fun ActivityScreen(onBack: () -> Unit) {
    var entries by remember { mutableStateOf(ActivityLog.all()) }

    FeatureScaffold(
        title = stringResource(R.string.activity_screen_title),
        onBack = onBack,
        action = {
            if (entries.isNotEmpty()) {
                Text(stringResource(R.string.activity_clear_button), color = FPrimary, fontSize = 14.sp,
                    modifier = Modifier.clickable { ActivityLog.clear(); entries = emptyList() }.padding(8.dp))
            }
        },
    ) {
        if (entries.isEmpty()) {
            FEmpty(stringResource(R.string.activity_empty_state))
            return@FeatureScaffold
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            item {
                Text(stringResource(R.string.activity_summary_count, entries.size), color = FMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            }
            items(entries, key = { it.id }) { e ->
                FCard {
                    Text(e.task, color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FPill(outcomeLabel(e.outcome), outcomeColor(e.outcome))
                        Spacer(Modifier.width(6.dp))
                        FPill(e.target, if (e.target == stringResource(R.string.control_target_local)) FPrimary else FWarning)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.activity_steps_and_time, e.steps, relTime(e.ts)), color = FMuted, fontSize = 10.sp)
                    }
                    if (e.detail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(e.detail, color = FSub, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
