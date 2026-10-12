package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style

/** Viewing a device never changes the conversation's execution environment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DotDeviceSheet(member: DotMember, initialEnvironment: DotEnvironment,
    onDismiss: () -> Unit, onChangeRole: () -> Unit, onOpenTask: (DeviceExecutionState) -> Unit) {
    var screen by remember { mutableStateOf<DotEnvironment?>(null) }
    var configure by remember { mutableStateOf<DotEnvironment?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val viewing = screen
    if (viewing != null) {
        DotScreenSheet(member, viewing, onDismiss = { screen = null }, onChangeRole = onChangeRole)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Style.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(member.avatar), null, Modifier.size(56.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(member.name, color = Style.text, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.dot_devices), color = Style.muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onChangeRole) { Text(stringResource(R.string.dot_screen_switch_role)) }
            }
            Text(stringResource(R.string.dot_devices_hint), color = Style.muted, style = MaterialTheme.typography.bodySmall)
            val devices = listOf(initialEnvironment.takeUnless { it == DotEnvironment.PHONE } ?: DotEnvironment.COMPUTER,
                DotEnvironment.COMPUTER, DotEnvironment.HOME).distinct()
            devices.forEach { environment ->
                val address = remember(environment, revision) { DotEnvironmentStore.address(environment) }
                val configured = DotRepository.validBaseUrl(address)
                var health by remember(environment, revision) { mutableStateOf<DotPairingRepository.Health?>(null) }
                var issue by remember(environment, revision) { mutableStateOf<Int?>(null) }
                var checking by remember(environment, revision) { mutableStateOf(false) }
                var refresh by remember(environment) { mutableIntStateOf(0) }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(environment, revision, refresh, foreground, configure) {
                    if (!configured || !foreground || configure != null) return@LaunchedEffect
                    do {
                        checking = true
                        try { health = DotPairingRepository.health(environment); issue = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            health = null
                            issue = when ((error as? DotRepository.TaskHttpException)?.code) {
                                401, 403 -> if (DotPairingRepository.paired(environment)) R.string.dot_pair_revoked else R.string.dot_auth_needed
                                404 -> R.string.dot_screen_update_required
                                else -> R.string.dot_device_offline
                            }
                        } finally { checking = false }
                        delay(20000)
                    } while (true)
                }
                val tasks = DeviceExecutionStore.active().filter { it.environment == environment && it.host == address.trimEnd('/') }
                Surface(shape = RoundedCornerShape(20.dp), color = Style.surface,
                    border = BorderStroke(1.dp, Style.border)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(environment.icon, null, Modifier.size(24.dp), tint = Style.muted)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(health?.hostName ?: DotPairingRepository.hostName(environment) ?: stringResource(environment.title), color = Style.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(stringResource(when {
                                    !configured -> R.string.dot_device_unconfigured
                                    checking && health == null -> R.string.dot_device_checking
                                    issue != null -> issue!!
                                    health != null -> if (DotPairingRepository.paired(environment)) R.string.dot_device_paired else R.string.dot_device_online
                                    else -> R.string.dot_device_configured
                                }),
                                    color = Style.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            if (configured) IconButton(onClick = { configure = environment }) {
                                Icon(Icons.Filled.Settings, stringResource(R.string.dot_env_configure), tint = Style.muted)
                            }
                        }
                        if (configured) Text(stringResource(environment.tabLabel) + " · " + address, color = Style.muted, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (health != null) Text(stringResource(if (health!!.desktop) R.string.dot_device_desktop_capability
                            else R.string.dot_device_background_capability), color = Style.muted, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                when {
                                    !configured || issue in listOf(R.string.dot_pair_revoked, R.string.dot_auth_needed, R.string.dot_screen_update_required) -> configure = environment
                                    health?.desktop == true -> screen = environment
                                    health != null -> configure = environment
                                    else -> refresh++
                                }
                            }, enabled = !checking,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                                Text(stringResource(when {
                                    !configured -> R.string.dot_pair_bind
                                    issue == R.string.dot_pair_revoked -> R.string.dot_pair_bind
                                    issue in listOf(R.string.dot_auth_needed, R.string.dot_screen_update_required) -> R.string.dot_env_configure
                                    checking -> R.string.dot_device_checking
                                    health?.desktop == true -> R.string.dot_screen_open
                                    health != null -> R.string.dot_env_configure
                                    else -> R.string.dot_device_reconnect
                                }))
                            }
                            if (configured && health != null) OutlinedIconButton(onClick = { refresh++ }, enabled = !checking) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.browser_refresh_page), Modifier.size(18.dp))
                            }
                        }
                        tasks.forEach { execution ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onOpenTask(execution) }, modifier = Modifier.weight(1f)) {
                                    Column {
                                        Text(SessionStore.metaOf(execution.sessionId)?.title ?: member.name,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(execution.connectionIssue.value ?: stringResource(when (execution.taskStatus.value) {
                                            "queued" -> R.string.dot_job_queued
                                            "running" -> R.string.dot_job_running
                                            "cancelling" -> R.string.echo_task_stopping
                                            else -> R.string.dot_device_checking
                                        }), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                TextButton(onClick = { DeviceExecutionStore.cancel(execution) },
                                    enabled = execution.taskStatus.value != "cancelling") { Text(stringResource(R.string.dot_job_stop)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    configure?.let { environment ->
        DotConnectionDialog(environment, onDismiss = { configure = null },
            onSaved = { configure = null; revision++ })
    }
}
