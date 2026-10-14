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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 操作审计日志查看界面。
 *
 * [ToolAuditLog] 后端早已记录所有中/高危工具调用（含拦截/确认决策），但没有任何 UI 入口。
 * 本界面展示最近记录、按风险等级筛选，并提供清空。
 */
class AuditLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { AuditLogScreen(onBack = { finish() }) }
    }
}

private val auditTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@Composable
fun AuditLogScreen(onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("all") } // all / high / medium
    val entries = remember(tick) { ToolAuditLog.all() }
    val shown = remember(tick, filter) {
        when (filter) {
            "high" -> entries.filter { it.risk == "high" }
            "medium" -> entries.filter { it.risk == "medium" }
            else -> entries
        }
    }

    FeatureScaffold(
        title = stringResource(R.string.tc_nav_audit_title),
        onBack = onBack,
        action = {
            if (entries.isNotEmpty()) {
                Text(
                    stringResource(R.string.audit_clear),
                    color = FError, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { ToolAuditLog.clear(); tick++ },
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.audit_description),
            color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChip(stringResource(R.string.audit_filter_all, entries.size), filter == "all") { filter = "all" }
            Spacer(Modifier.width(8.dp))
            FilterChip(stringResource(R.string.audit_risk_high), filter == "high") { filter = "high" }
            Spacer(Modifier.width(8.dp))
            FilterChip(stringResource(R.string.audit_risk_medium), filter == "medium") { filter = "medium" }
        }

        if (shown.isEmpty()) {
            FEmpty(stringResource(R.string.audit_empty))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shown) { entry -> AuditEntryCard(entry) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) FPrimary else FMuted
    androidx.compose.material3.Surface(
        shape = com.apk.claw.android.ui.compose.theme.OctopusShape.capsule,
        color = color.copy(alpha = if (selected) 0.18f else 0.08f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
private fun AuditEntryCard(entry: ToolAuditLog.Entry) {
    FCard {
        // 签名校验失败:HMAC 不匹配,条目可能被篡改或伪造。显著标红提醒。
        if (entry.tampered) {
            FWarningNote(stringResource(R.string.audit_tampered), color = FError)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.toolName, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val riskColor = when (entry.risk) {
                "high" -> FWarning
                "medium" -> FPrimary
                else -> FMuted
            }
            FPill(stringResource(riskLabel(entry.risk)), riskColor)
        }
        Text(
            auditTimeFmt.format(Date(entry.ts)),
            color = FMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (entry.params.isNotBlank()) {
            Text(
                entry.params,
                color = FSub, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            val (statusText, statusColor) = when {
                entry.blockedBy != null -> stringResource(R.string.audit_status_blocked, entry.blockedBy) to FWarning
                entry.success -> stringResource(R.string.audit_status_ok) to FSuccess
                else -> stringResource(R.string.audit_status_failed) to FError
            }
            FPill(statusText, statusColor)
            Spacer(Modifier.weight(1f))
            if (entry.durationMs > 0) {
                Text("${entry.durationMs}ms", color = FMuted, fontSize = 12.sp)
            }
        }
        if (entry.result.isNotBlank()) {
            Text(
                entry.result,
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun riskLabel(risk: String): Int = when (risk) {
    "high" -> R.string.audit_risk_high
    "medium" -> R.string.audit_risk_medium
    else -> R.string.audit_risk_low
}
