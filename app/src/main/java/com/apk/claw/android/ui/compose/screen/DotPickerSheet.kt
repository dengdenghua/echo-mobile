package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DotPickerSheet(onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    val selected = DotRoster.find(DotRoster.activeCharacter) ?: DotRoster.members.first()
    val environment = DotEnvironmentStore.selected
    var showConnection by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var installed by remember { mutableStateOf<Set<String>?>(null) }
    var connectionIssue by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(refresh, environment) {
        loading = true
        installed = null
        connectionIssue = null
        try {
            installed = DotRepository.installedAgentIds(environment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            connectionIssue = if (error.message == "HTTP 401" || error.message == "HTTP 403") R.string.dot_auth_needed else R.string.dot_offline
        } finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Style.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .82f).dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Dot", color = Style.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.dot_squad), color = Style.muted, fontSize = 13.sp)
            }
            IconButton(onClick = { showConnection = true }) {
                Icon(Icons.Filled.Link, contentDescription = stringResource(R.string.dot_connect), tint = Style.muted)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(shape = OctopusShape.large, color = Style.surface, border = BorderStroke(1.dp, Style.border)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Image(painterResource(selected.avatar), null, Modifier.size(84.dp).clip(CircleShape))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(stringResource(R.string.dot_yours), color = Style.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(selected.name, color = Style.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                                Text(dotSpecialty(selected), color = Style.muted, fontSize = 13.sp)
                            }
                        }
                        Text(stringResource(R.string.dot_description), color = Style.muted, fontSize = 14.sp, lineHeight = 21.sp)
                        Button(
                            onClick = { DotRoster.select(selected.agentId); onOpenChat(selected.agentId) },
                            enabled = installed?.contains(selected.agentId) != false,
                            modifier = Modifier.fillMaxWidth().height(48.dp), shape = OctopusShape.small,
                            // 主按钮单色;core 青只留给选中标记
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OctopusColors.Primary,
                                contentColor = OctopusColors.OnPrimary,
                            ),
                        ) {
                            Text(stringResource(R.string.dot_chat_with, selected.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dot_members), color = Style.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.dot_member_count, DotRoster.members.size), color = Style.muted, fontSize = 12.sp)
                }
            }
            items(DotRoster.members, key = { it.agentId }) { member ->
                val active = selected.agentId == member.agentId
                Surface(
                    onClick = { DotRoster.select(member.agentId) }, shape = OctopusShape.large,
                    color = if (active) Style.accentSurface else Style.surface,
                    border = BorderStroke(1.dp, if (active) Style.accent.copy(alpha = .5f) else Style.border),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(member.avatar), null, Modifier.size(44.dp).clip(CircleShape))
                            Spacer(Modifier.weight(1f))
                            if (active) Icon(Icons.Filled.Check, stringResource(R.string.dot_selected), Modifier.size(18.dp), tint = Style.accent)
                        }
                        Text(member.name, color = Style.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(dotSpecialty(member), color = Style.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (installed?.contains(member.agentId) == false) Text(stringResource(R.string.dot_not_installed), color = Style.muted, fontSize = 10.sp)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(onClick = { if (connectionIssue != null) showConnection = true else refresh++ }, shape = OctopusShape.large, color = Style.softSurface) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Style.accent, strokeWidth = 2.dp)
                        else Icon(if (connectionIssue != null) Icons.Filled.Link else Icons.Filled.Refresh, null, Modifier.size(16.dp), tint = Style.muted)
                        Text(stringResource(if (loading) R.string.dot_connecting else connectionIssue ?: R.string.dot_connected), color = Style.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    }
    if (showConnection) DotConnectionDialog(environment, onDismiss = { showConnection = false }, onSaved = { showConnection = false; refresh++ })
}

@Composable
internal fun dotSpecialty(member: DotMember): String =
    if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh") member.specialty else member.specialtyEn

@Composable
internal fun DotConnectionDialog(environment: DotEnvironment, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (environment == DotEnvironment.PHONE) {
        AlertDialog(
            onDismissRequest = onDismiss, title = { Text(stringResource(environment.title)) },
            text = { Text(stringResource(R.string.dot_env_phone_setup)) },
            confirmButton = { TextButton(onClick = {
                context.startActivity(android.content.Intent(context, com.apk.claw.android.ui.settings.LlmConfigActivity::class.java))
                onDismiss()
            }) { Text(stringResource(R.string.dot_env_phone_settings)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
        return
    }
    DotRemoteConnectionDialog(environment, onDismiss, onSaved)
}
