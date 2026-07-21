package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import org.json.JSONArray

/**
 * refine-chat-interaction Task 4 —— Plan 详情面板。
 *
 * 渲染 Plan 模式生成的步骤列表(JSONArray,每项含 action / description),
 * 底部提供「批准并退出 Plan 模式」按钮,触发 [onExitPlanMode] 回调。
 *
 * 极简 flat UI(INV-U5):单色等宽字体、OctopusShape.small 圆角、无阴影、无渐变。
 *
 * @param planJson Plan 模式产物(JSONArray 字符串,每项:{action, description})
 * @param onExitPlanMode 用户批准计划后调用(由 ChatScreen 触发 exit_plan_mode 工具)
 */
class PlanDetailPane(
    private val planJson: String,
    private val onExitPlanMode: () -> Unit,
) : DetailPane {

    override val title: String = "计划详情"

    @Composable
    override fun Render() {
        val steps = remember(planJson) { parsePlanSteps(planJson) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (steps.isEmpty()) {
                Text(
                    "（计划为空）",
                    fontSize = OctopusType.caption,
                    color = OctopusColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                steps.forEachIndexed { idx, step ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.xs)) {
                        Text(
                            "步骤 ${idx + 1}",
                            fontSize = OctopusType.caption,
                            color = OctopusColors.TextMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                        Text(
                            "· ${step.action} · ${step.description}",
                            fontSize = OctopusType.caption,
                            color = OctopusColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(OctopusSpacing.md))
            Button(
                onClick = onExitPlanMode,
                shape = OctopusShape.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OctopusColors.Primary,
                    contentColor = OctopusColors.OnPrimary,
                ),
                contentPadding = PaddingValues(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
            ) {
                Text("批准并退出 Plan 模式", fontSize = OctopusType.body)
            }
        }
    }

    private data class PlanStep(val action: String, val description: String)

    private fun parsePlanSteps(json: String): List<PlanStep> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val action = obj.optString("action", "")
                    val desc = obj.optString("description", "")
                    add(PlanStep(action, desc))
                }
            }
        }.getOrDefault(emptyList())
    }
}
