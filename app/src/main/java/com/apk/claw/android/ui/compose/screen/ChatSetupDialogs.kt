package com.apk.claw.android.ui.compose.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.apk.claw.android.R

/**
 * 没有配置模型时发送指令的提示：说清楚为什么没有执行，并直接给出去配置模型的入口。
 * 对话里同时留一条说明消息（见 ChatScreen 的 send），关掉弹窗后仍能看到原因。
 */
@Composable
internal fun NoModelDialog(onConfigure: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_no_model_title)) },
        text = { Text(stringResource(R.string.chat_no_model_desc)) },
        confirmButton = {
            TextButton(onClick = onConfigure) { Text(stringResource(R.string.chat_no_model_configure)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_no_model_later)) }
        },
    )
}
