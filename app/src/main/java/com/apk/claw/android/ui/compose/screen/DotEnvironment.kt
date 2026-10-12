package com.apk.claw.android.ui.compose.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style
import com.apk.claw.android.utils.KVUtils

internal enum class DotEnvironment(
    val id: String, @StringRes val tabLabel: Int, @StringRes val title: Int,
    @StringRes val description: Int, val icon: ImageVector,
) {
    HOME("home", R.string.dot_env_home_tab, R.string.dot_env_home, R.string.dot_env_home_hint, Icons.Filled.Storage),
    COMPUTER("computer", R.string.dot_env_computer_tab, R.string.dot_env_computer, R.string.dot_env_computer_hint, Icons.Filled.DesktopWindows),
    PHONE("phone", R.string.dot_env_phone_tab, R.string.dot_env_phone, R.string.dot_env_phone_hint, Icons.Filled.PhoneAndroid);

    fun adjacent(offset: Int): DotEnvironment = entries[(ordinal + offset).coerceIn(0, entries.lastIndex)]
}

/** The two remote environments have independent addresses and credentials. */
internal object DotEnvironmentStore {
    private const val SELECTED_KEY = "dot_execution_environment"
    var selected by mutableStateOf(DotEnvironment.entries.firstOrNull {
        it.id == KVUtils.getString(SELECTED_KEY, "home")
    } ?: DotEnvironment.HOME)
        private set

    fun select(environment: DotEnvironment) {
        selected = environment
        KVUtils.putString(SELECTED_KEY, environment.id)
    }

    fun address(environment: DotEnvironment): String = when (environment) {
        DotEnvironment.HOME -> AccountConfig.octopusRuntimeBaseUrl
        DotEnvironment.COMPUTER -> KVUtils.getString("dot_computer_address", "")
        DotEnvironment.PHONE -> ""
    }

    fun token(environment: DotEnvironment): String = when (environment) {
        DotEnvironment.HOME -> KVUtils.getOctopusAuthToken()
        DotEnvironment.COMPUTER -> KVUtils.getString("dot_computer_token", "")
        DotEnvironment.PHONE -> ""
    }

    fun saveConnection(environment: DotEnvironment, address: String, token: String) {
        require(environment != DotEnvironment.PHONE)
        if (environment == DotEnvironment.HOME) {
            AccountConfig.octopusRuntimeBaseUrl = address.trim().trimEnd('/')
            KVUtils.setOctopusAuthToken(token.trim())
        } else {
            KVUtils.putString("dot_computer_address", address.trim().trimEnd('/'))
            KVUtils.putString("dot_computer_token", token.trim())
        }
    }
}

/** Environment controls in the unified role header. */
@Composable
internal fun DotEnvironmentSelector(
    selected: DotEnvironment,
    enabled: Boolean,
    onSelect: (DotEnvironment) -> Unit,
    modifier: Modifier = Modifier,
    runningEnvironments: Set<DotEnvironment> = emptySet(),
    onConfigure: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) menuOpen = false }
    val selectLatest by rememberUpdatedState(onSelect)
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    val title = stringResource(selected.title)
    val previous = selected.adjacent(-1)
    val next = selected.adjacent(1)
    val previousTitle = stringResource(previous.title)
    val nextTitle = stringResource(next.title)
    Box(
        modifier.width(40.dp).height(48.dp)
            .clickable(enabled = enabled) { menuOpen = true }
            .semantics {
                stateDescription = title
                customActions = buildList {
                    if (enabled && previous != selected) add(CustomAccessibilityAction(previousTitle) {
                        selectLatest(previous); true
                    })
                    if (enabled && next != selected) add(CustomAccessibilityAction(nextTitle) {
                        selectLatest(next); true
                    })
                }
            }
            .pointerInput(selected, enabled, threshold) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { change, amount -> if (enabled) { change.consume(); drag += amount } },
                    onDragEnd = {
                        if (enabled && kotlin.math.abs(drag) >= threshold)
                            selectLatest(selected.adjacent(if (drag < 0) 1 else -1))
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Icon(selected.icon, title, Modifier.size(22.dp),
                tint = Style.muted.copy(alpha = if (enabled) 1f else .5f))
            if (selected in runningEnvironments) Box(Modifier.align(Alignment.BottomEnd)
                .size(6.dp).background(Style.accent, CircleShape))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(stringResource(R.string.dot_env_next_task),
                style = MaterialTheme.typography.labelMedium, color = Style.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            DotEnvironment.entries.forEach { environment ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(environment.title))
                            Text(stringResource(environment.description),
                                style = MaterialTheme.typography.bodySmall, color = Style.muted)
                        }
                    },
                    leadingIcon = { Icon(environment.icon, null, tint = Style.muted) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (environment in runningEnvironments) Box(Modifier.size(6.dp).background(Style.accent, CircleShape))
                            if (environment == selected) Icon(Icons.Filled.Check, null, tint = Style.accent)
                        }
                    },
                    onClick = { menuOpen = false; selectLatest(environment) },
                    enabled = enabled,
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dot_env_configure),
                    style = MaterialTheme.typography.bodySmall) },
                onClick = { menuOpen = false; onConfigure() },
            )
            Text(stringResource(R.string.dot_env_existing_continue),
                style = MaterialTheme.typography.labelSmall, color = Style.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
