package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.skill.PromptSkillStore
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.utils.KVUtils

/**
 * 技能 / 能力 —— 本地管理 Agent 可调用的工具:列出全部,按需启停。
 * 核心控制/感知工具(点击/滑动/截屏/看屏…)锁定不可关,以免关掉就没法自动化。
 * 停用通过 [KVUtils] 持久化,Agent 的工具规格在 LangChain4jToolBridge 据此过滤。
 */
class SkillsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { SkillsScreen(onBack = { finish() }) }
    }
}

/** 核心工具:Agent 自动化的地基,不允许停用。 */
private val CORE = setOf(
    "tap", "long_press", "swipe", "input_text", "find_node_info",
    "scroll_to_find", "open_app", "take_screenshot", "get_screen_info", "system_key",
    "look_at_screen", "wait", "finish", "finish_task",
)

private val TILE_SIZE = 44.dp

/** 图标格子底色:ECHO 不用颜色区分类别,统一中性 ink-muted(保留 index 参数以兼容调用点)。 */
@Composable
private fun accentAt(index: Int): Color {
    val palette = listOf(FMuted)
    return palette[(index % palette.size + palette.size) % palette.size]
}

/** 提示词技能配图:按关键词猜一个贴切的 emoji。 */
@Suppress("CyclomaticComplexMethod")
private fun emojiForSkill(name: String, desc: String): String {
    val s = (name + " " + desc).lowercase()
    return when {
        "shizuku" in s || "adb" in s || "配对" in s || "调试" in s -> "⚡"
        "自动化" in s || "编排" in s -> "🤖"
        "风格" in s || "style" in s -> "✨"
        "海报" in s || "视觉" in s || "design" in s || "设计" in s -> "🎨"
        "抓取" in s || "爬" in s || "scrape" in s || "crawl" in s || "网页" in s -> "🕸️"
        "批量" in s -> "🤖"
        else -> "✨"
    }
}

/** 工具配图:按工具名猜一个 emoji,认不出就给通用积木。 */
@Suppress("CyclomaticComplexMethod")
private fun emojiForTool(name: String): String = when (name) {
    "tap", "long_press", "swipe", "scroll_to_find" -> "👆"
    "input_text" -> "⌨️"
    "take_screenshot", "look_at_screen" -> "📸"
    "get_screen_info", "find_node_info" -> "👁️"
    "open_app" -> "📱"
    "system_key" -> "🎛️"
    "wait" -> "⏳"
    "finish", "finish_task" -> "✅"
    else -> when {
        "file" in name || "read" in name || "write" in name -> "📄"
        "http" in name || "web" in name || "url" in name || "browser" in name -> "🌐"
        "search" in name -> "🔎"
        "image" in name || "photo" in name || "video" in name || "gen" in name -> "🖼️"
        "sms" in name || "call" in name || "phone" in name || "contact" in name -> "📞"
        "notify" in name || "notification" in name -> "🔔"
        "app" in name -> "📱"
        else -> "🧩"
    }
}

@Composable
private fun SkillIconTile(emoji: String, accent: Color) {
    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(OctopusShape.large)
            .background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 21.sp)
    }
}

@Composable
private fun SkillSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onToggle,
        colors = SwitchDefaults.colors(
            checkedThumbColor = FOnPrimary,
            checkedTrackColor = FPrimary,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = FMuted.copy(alpha = 0.35f),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SkillGroupHeader(emoji: String, title: String, count: Int, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 15.sp)
        Spacer(Modifier.width(7.dp))
        Text(title, color = FText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        FPill(count.toString(), FPrimary)
    }
    Text(
        subtitle,
        color = FMuted, fontSize = 11.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 2.dp),
    )
}

@Composable
private fun SkillsScreen(onBack: () -> Unit) {
    FeatureScaffold(title = stringResource(R.string.skills_screen_title), onBack = onBack) {
        SkillsContent()
    }
}

/** 技能列表(提示词技能 + 可调用工具)。独立页和 Hub 的「技能」标签共用。 */
@Composable
internal fun ColumnScope.SkillsContent() {
    val tools = remember {
        ToolRegistry.getInstance().getAllTools().sortedWith(
            compareBy({ it.getName() !in CORE }, { it.getName() })   // 核心在前
        )
    }
    var rev by remember { mutableStateOf(0) }

    // 提示词技能(generate_skill / import_skill 产物):可开关、可删。
    var skillRev by remember { mutableStateOf(0) }
    val promptSkills = remember(skillRev) { PromptSkillStore.all() }

    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
        if (promptSkills.isNotEmpty()) {
            item(key = "__skills_header__") {
                SkillGroupHeader("✨", "提示词技能", promptSkills.size, "相关任务时自动注入,让 Agent 更懂这件事")
            }
            itemsIndexed(promptSkills, key = { _, sk -> sk.id }) { index, s ->
                PromptSkillCard(
                    emoji = emojiForSkill(s.name, s.description),
                    accent = accentAt(index),
                    name = s.name,
                    desc = s.description,
                    enabled = s.enabled,
                    onToggle = { PromptSkillStore.setEnabled(s.id, it); skillRev++ },
                    onDelete = { PromptSkillStore.delete(s.id); skillRev++ },
                )
            }
        }
        item(key = "__tools_header__") {
            SkillGroupHeader(
                "🧩", "可调用工具", tools.size,
                "核心控制/感知工具锁定;其余可按需停用,降低误操作与隐私面",
            )
        }
        itemsIndexed(tools, key = { _, tl -> tl.getName() }) { index, t ->
            val name = t.getName()
            val core = name in CORE
            val enabled = remember(rev, name) { ToolRegistry.getInstance().isToolEnabled(name) }
            ToolCard(
                emoji = emojiForTool(name),
                accent = accentAt(index),
                display = runCatching { t.getDisplayName() }.getOrDefault(name),
                rawName = name,
                desc = runCatching { t.getDescription() }.getOrDefault(""),
                core = core,
                enabled = enabled,
                onToggle = { KVUtils.setToolDisabled(name, !it); rev++ },
            )
        }
    }
}

@Composable
private fun PromptSkillCard(
    emoji: String,
    accent: Color,
    name: String,
    desc: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkillIconTile(emoji, accent)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, color = FSub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
                }
            }
            SkillSwitch(enabled, onToggle)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            Text(
                "删除",
                color = FError, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolCard(
    emoji: String,
    accent: Color,
    display: String,
    rawName: String,
    desc: String,
    core: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkillIconTile(emoji, accent)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(display, color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(rawName, color = FMuted, fontSize = 10.sp)
                }
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, color = FSub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (core) {
                FPill("🔒 ${stringResource(R.string.skills_badge_core)}", FMuted)
            } else {
                SkillSwitch(enabled, onToggle)
            }
        }
    }
}
