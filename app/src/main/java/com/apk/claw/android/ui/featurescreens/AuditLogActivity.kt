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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        title = "操作审计日志",
        onBack = onBack,
        action = {
            if (entries.isNotEmpty()) {
                Text(
                    "清空",
                    color = FError, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { ToolAuditLog.clear(); tick++ },
                )
            }
        },
    ) {
        Text(
            "记录所有中/高危工具调用（敏感参数已脱敏），最多保留最近 300 条。",
            color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChip("全部 ${entries.size}", filter == "all") { filter = "all" }
            Spacer(Modifier.width(8.dp))
            FilterChip("高危", filter == "high") { filter = "high" }
            Spacer(Modifier.width(8.dp))
            FilterChip("中危", filter == "medium") { filter = "medium" }
        }

        if (shown.isEmpty()) {
            FEmpty("暂无审计记录。Agent 调用高危/中危工具后会记录在此。")
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
            color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AuditEntryCard(entry: ToolAuditLog.Entry) {
    FCard {
        // 签名校验失败:HMAC 不匹配,条目可能被篡改或伪造。显著标红提醒。
        if (entry.tampered) {
            FPill("⚠ 签名校验失败 · 可能被篡改/伪造", FError)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.toolName, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val riskColor = when (entry.risk) {
                "high" -> FWarning
                "medium" -> FPrimary
                else -> FMuted
            }
            FPill(riskLabel(entry.risk), riskColor)
        }
        Text(
            auditTimeFmt.format(Date(entry.ts)),
            color = FMuted, fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (entry.params.isNotBlank()) {
            Text(
                entry.params,
                color = FSub, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            val (statusText, statusColor) = when {
                entry.blockedBy != null -> "已拦截（${entry.blockedBy}）" to FWarning
                entry.success -> "成功" to FSuccess
                else -> "失败" to FError
            }
            FPill(statusText, statusColor)
            Spacer(Modifier.weight(1f))
            if (entry.durationMs > 0) {
                Text("${entry.durationMs}ms", color = FMuted, fontSize = 10.sp)
            }
        }
        if (entry.result.isNotBlank()) {
            Text(
                entry.result,
                color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun riskLabel(risk: String): String = when (risk) {
    "high" -> "高危"
    "medium" -> "中危"
    else -> "低危"
}
