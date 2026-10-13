package com.apk.claw.android.ui.compose.screen

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.account.AccountActivity
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.featurescreens.*
import com.apk.claw.android.ui.compose.theme.EchoFieldColors

/** Global destinations live here; the overflow menu belongs to the current conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EchoNavigationDrawer(
    sessions: List<SessionStore.SessionMeta>, currentId: String, isRunning: Boolean,
    onNewChat: () -> Unit, onSwitch: (String) -> Unit, onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    unreadCounts: Map<String, Int> = emptyMap(),
    onOpenDesktopConversations: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val matches = sessions.filter { it.title.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.updatedAt }
    val launch: (Class<*>) -> Unit = { destination ->
        onClose()
        context.startActivity(Intent(context, destination))
    }
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().widthIn(max = 420.dp)
            .width((LocalConfiguration.current.screenWidthDp * .84f).dp),
        drawerContainerColor = OctopusColors.Background,
        drawerContentColor = OctopusColors.TextPrimary,
        drawerShape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),  // radius-lg
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // ECHO 标识:App 图标同款石墨底 + 雪白框 + core 光点
            Image(painterResource(R.drawable.ic_echo_foreground), contentDescription = null,
                modifier = Modifier.size(32.dp).clip(OctopusShape.medium)
                    .background(colorResource(R.color.echo_icon_background)))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            IconButton(enabled = !isRunning, onClick = onNewChat) {
                Icon(Icons.Filled.Edit, stringResource(R.string.chat_new))
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.echo_search_chats)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.echo_clear_search)) } },
            shape = OctopusShape.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            colors = EchoFieldColors.outlined())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (query.isBlank()) {
                // 与桌面端侧栏对齐:Hub(智能体 · 应用 · 技能)、自动化;记忆、活动、信任中心、插件管理在设置里
                item {
                    EchoDrawerLink(
                        Icons.Filled.Hub,
                        stringResource(R.string.hub_title),
                        hint = stringResource(R.string.hub_drawer_hint),
                    ) {
                        onClose()
                        HubActivity.start(context)
                    }
                }
                item { EchoDrawerLink(Icons.Filled.Schedule, stringResource(R.string.routines_title)) { launch(RoutinesActivity::class.java) } }
                if (onOpenDesktopConversations != null) {
                    item {
                        EchoDrawerLink(
                            Icons.Filled.Sync,
                            stringResource(R.string.dot_history_title),
                            onClick = onOpenDesktopConversations,
                        )
                    }
                }
                item { HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), color = OctopusColors.Border.copy(alpha = .18f)) }
            }
            val pinned = matches.filter { it.pinned }
            val recent = matches.filterNot { it.pinned }
            if (pinned.isNotEmpty()) {
                item { EchoDrawerHeading(stringResource(R.string.echo_pinned)) }
                items(pinned, key = { it.id }) { EchoSessionLink(it, it.id == currentId, !isRunning, unreadCounts[it.id] ?: 0, onSwitch) }
            }
            if (recent.isNotEmpty()) {
                item { EchoDrawerHeading(stringResource(R.string.echo_recent)) }
                items(recent, key = { it.id }) { EchoSessionLink(it, it.id == currentId, !isRunning, unreadCounts[it.id] ?: 0, onSwitch) }
            }
            if (matches.isEmpty()) item {
                Text(stringResource(R.string.echo_no_chats), color = OctopusColors.TextMuted,
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider(color = OctopusColors.Border.copy(alpha = .18f))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 账户一行(与桌面端底部账户菜单一致):昵称 + 会员与订阅,齿轮进入设置
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { launch(AccountActivity::class.java) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountCircle, null, Modifier.size(28.dp), tint = OctopusColors.TextMuted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(AccountStore.nickname.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_title),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.echo_subscription), color = OctopusColors.TextMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { onClose(); onOpenSettings() }) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.settings_title))
                }
            }
        }
    }
}

@Composable
internal fun EchoDrawerLink(
    icon: ImageVector,
    label: String,
    hint: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .4f).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = OctopusColors.TextSecondary)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, color = OctopusColors.TextMuted, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EchoDrawerHeading(label: String) {
    Text(label, color = OctopusColors.TextMuted, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp))
}

@Composable
private fun EchoSessionLink(
    session: SessionStore.SessionMeta, selected: Boolean, enabled: Boolean, unread: Int, onSwitch: (String) -> Unit,
) {
    val unreadLabel = pluralStringResource(R.plurals.chat_new_results, unread, unread)
    Row(Modifier.fillMaxWidth().background(if (selected) OctopusColors.SurfaceVariant else Color.Transparent, OctopusShape.small)
        .clickable(enabled = enabled) { onSwitch(session.id) }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            session.workspace?.takeIf { it.isNotBlank() }?.let {
                Text(it.trimEnd('/').substringAfterLast('/'), color = OctopusColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (unread > 0) Badge(Modifier.padding(horizontal = 8.dp), containerColor = MaterialTheme.colorScheme.primary) {
            Text(unread.toString(), Modifier.semantics {
                contentDescription = unreadLabel
            })
        }
        if (session.pinned) Icon(Icons.Filled.PushPin, null, Modifier.size(14.dp), tint = OctopusColors.TextMuted)
    }
}
