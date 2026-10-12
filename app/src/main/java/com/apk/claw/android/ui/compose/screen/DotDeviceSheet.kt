package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style

/** Viewing a device never changes the conversation's execution environment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DotDeviceSheet(member: DotMember, initialEnvironment: DotEnvironment,
    onDismiss: () -> Unit, onChangeRole: () -> Unit) {
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(member.avatar), null, Modifier.size(56.dp))
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
                Surface(shape = RoundedCornerShape(20.dp), color = Style.surface,
                    border = BorderStroke(1.dp, Style.border)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(environment.icon, null, Modifier.size(24.dp), tint = Style.muted)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(environment.title), color = Style.text)
                                Text(stringResource(if (configured) R.string.dot_device_configured else R.string.dot_device_unconfigured),
                                    color = Style.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            if (configured) IconButton(onClick = { configure = environment }) {
                                Icon(Icons.Filled.Settings, stringResource(R.string.dot_env_configure), tint = Style.muted)
                            }
                        }
                        if (configured) Text(address, color = Style.muted, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Button(onClick = { if (configured) screen = environment else configure = environment },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text(stringResource(if (configured) R.string.dot_screen_open else R.string.dot_connect))
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
