package com.apk.claw.android.ui.featurescreens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.screen.DotMember
import com.apk.claw.android.ui.compose.screen.DotRoster
import com.apk.claw.android.ui.compose.screen.dotSpecialty
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * Hub:与桌面端 HUB 对齐的三个标签 —— 智能体(Dot 小队 + 本地角色)、应用(手机小程序)、技能。
 *
 * 选中一位 Dot 即写入 [DotRoster](对话页监听它并切换),再关闭本页回到对话。
 */
class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_TAB, TAB_AGENTS).coerceIn(TAB_AGENTS, TAB_SKILLS)
        setFeatureContent {
            HubScreen(
                initialTab = initialTab,
                onBack = { finish() },
                onChatWith = { id ->
                    DotRoster.select(id)
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_TAB = "hub_tab"
        const val TAB_AGENTS = 0
        const val TAB_APPS = 1
        const val TAB_SKILLS = 2

        fun start(context: Context, tab: Int = TAB_AGENTS) {
            context.startActivity(Intent(context, HubActivity::class.java).putExtra(EXTRA_TAB, tab))
        }
    }
}

@Composable
private fun HubScreen(initialTab: Int, onBack: () -> Unit, onChatWith: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(initialTab) }
    FeatureScaffold(
        title = stringResource(R.string.hub_title),
        onBack = onBack,
        action = { if (tab == HubActivity.TAB_APPS) MiniAppMarketAction() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(
                HubActivity.TAB_AGENTS to R.string.hub_tab_agents,
                HubActivity.TAB_APPS to R.string.hub_tab_apps,
                HubActivity.TAB_SKILLS to R.string.hub_tab_skills,
            ).forEach { (index, label) ->
                HubTab(stringResource(label), tab == index) { tab = index }
            }
        }
        when (tab) {
            HubActivity.TAB_AGENTS -> HubAgents(onChatWith)
            HubActivity.TAB_APPS -> Column(Modifier.weight(1f)) { MiniAppListContent() }
            else -> SkillsContent()
        }
    }
}

/** 标签:选中 = 加粗 + 单色下划线,未选 = 灰色常规(与「广场」页的标签一致)。 */
@Composable
private fun HubTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text,
            color = if (selected) OctopusColors.TextPrimary else OctopusColors.TextMuted,
            fontSize = OctopusType.bodyLg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.height(2.dp).width(if (selected) 20.dp else 0.dp)
                .background(OctopusColors.Primary, OctopusShape.small),
        )
    }
}

/** 智能体:Dot 小队(点选即回到对话并切换)+ 本地角色与 Persona。 */
@Composable
private fun ColumnScope.HubAgents(onChatWith: (String) -> Unit) {
    val ctx = LocalContext.current
    val active = DotRoster.activeCharacter
    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { FSectionTitle(stringResource(R.string.dot_header)) }
        items(DotRoster.members, key = { it.agentId }) { member ->
            HubDotRow(member, selected = member.agentId == active) { onChatWith(member.agentId) }
        }
        item { FSectionTitle(stringResource(R.string.hub_local_characters)) }
        item {
            HubLinkRow(Icons.Filled.Face, stringResource(R.string.dot_local_personas)) {
                ctx.startActivity(Intent(ctx, PersonasActivity::class.java))
            }
        }
    }
}

@Composable
private fun HubDotRow(member: DotMember, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = OctopusShape.large,
        color = if (selected) OctopusColors.ContainerHigh else OctopusBackground.cardSurface,
        border = BorderStroke(
            1.dp,
            if (selected) OctopusColors.Accent.copy(alpha = SELECTED_BORDER_ALPHA) else OctopusBackground.cardBorder,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(member.avatar),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(member.name, color = FText, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.SemiBold)
                Text(
                    dotSpecialty(member),
                    color = FMuted,
                    fontSize = OctopusType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                // 当前选中 = core 标记(唯一用到强调色的地方)
                Icon(
                    Icons.Filled.Check,
                    stringResource(R.string.dot_selected),
                    tint = OctopusColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    stringResource(R.string.dot_chat_with, member.name),
                    tint = FMuted,
                )
            }
        }
    }
}

private const val SELECTED_BORDER_ALPHA = .5f

/** 去广场挑选更多应用的入口按钮(顶栏右侧)。 */
@Composable
internal fun MiniAppMarketAction() {
    val ctx = LocalContext.current
    IconButton(onClick = {
        ctx.startActivity(android.content.Intent(ctx, MiniAppMarketplaceActivity::class.java))
    }) {
        Icon(Icons.Filled.Public, contentDescription = "浏览广场小程序", tint = FText)
    }
}

@Composable
private fun HubLinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = OctopusShape.large,
        color = OctopusBackground.cardSurface,
        border = BorderStroke(1.dp, OctopusBackground.cardBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = FSub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = FText, fontSize = OctopusType.body, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = FMuted)
        }
    }
}
