package com.apk.claw.android.ui.compose.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apk.claw.android.ui.compose.screen.ChatMessage
import com.apk.claw.android.ui.compose.theme.OctopusColors

/**
 * A tool call's state as an outlined icon (done = core, failed = breach, running = core, stopped =
 * muted), replacing the ✓ ✗ ⏳ ■ glyphs in tool rows. The row's text still says what happened.
 */
@Composable
fun ToolStatusIcon(status: ChatMessage.ToolCallStatus, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val (icon, tint) = when (status) {
        ChatMessage.ToolCallStatus.SUCCESS -> Icons.Outlined.CheckCircle to OctopusColors.Success
        ChatMessage.ToolCallStatus.FAILURE -> Icons.Outlined.ErrorOutline to OctopusColors.Error
        ChatMessage.ToolCallStatus.RUNNING -> Icons.Outlined.HourglassTop to OctopusColors.Primary
        ChatMessage.ToolCallStatus.CANCELLED -> Icons.Outlined.StopCircle to OctopusColors.TextMuted
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
