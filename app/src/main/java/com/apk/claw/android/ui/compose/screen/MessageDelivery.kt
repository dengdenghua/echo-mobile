package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import java.net.HttpURLConnection

enum class MessageDeliveryStatus { SENDING, SENT, FAILED, CANCELLED }
enum class MessageDeliveryFailure { AUTH_REQUIRED, UPDATE_REQUIRED, REJECTED }

/** Delivery acknowledgements never change another request or undo a confirmed acceptance. */
internal fun updateMessageDelivery(
    messages: MutableList<ChatMessage>, requestId: String, status: MessageDeliveryStatus,
    failure: MessageDeliveryFailure? = null,
) : Boolean {
    val index = messages.indexOfFirst { it is ChatMessage.UserMessage && it.requestId == requestId }
    if (index < 0) return false
    val message = messages[index] as ChatMessage.UserMessage
    val updated = message.copy(delivery = status,
        deliveryFailure = if (status == MessageDeliveryStatus.FAILED) failure else null)
    val changed = updated != message &&
        (message.delivery != MessageDeliveryStatus.SENT || status == MessageDeliveryStatus.SENT)
    if (changed) messages[index] = updated
    return changed
}

internal fun messageDeliveryFailure(code: Int): MessageDeliveryFailure = when (code) {
    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> MessageDeliveryFailure.AUTH_REQUIRED
    HttpURLConnection.HTTP_NOT_FOUND -> MessageDeliveryFailure.UPDATE_REQUIRED
    else -> MessageDeliveryFailure.REJECTED
}

@Composable
internal fun MessageDeliveryLabel(
    message: ChatMessage.UserMessage, retryEnabled: Boolean, onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = message.delivery ?: return
    Column(modifier.padding(top = 4.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(when (status) {
                MessageDeliveryStatus.SENDING -> R.string.chat_delivery_sending
                MessageDeliveryStatus.SENT -> R.string.chat_delivery_sent
                MessageDeliveryStatus.FAILED -> R.string.chat_delivery_failed
                MessageDeliveryStatus.CANCELLED -> R.string.chat_delivery_cancelled
            }), style = MaterialTheme.typography.labelSmall,
                color = if (status == MessageDeliveryStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
            if (status == MessageDeliveryStatus.FAILED && message.requestId != null) {
                TextButton(onClick = onRetry, enabled = retryEnabled) {
                    Text(stringResource(R.string.chat_delivery_retry))
                }
            }
        }
        if (status == MessageDeliveryStatus.FAILED) {
            Text(stringResource(when (message.deliveryFailure) {
                MessageDeliveryFailure.AUTH_REQUIRED -> R.string.chat_delivery_auth_required
                MessageDeliveryFailure.UPDATE_REQUIRED -> R.string.chat_delivery_update_required
                else -> R.string.chat_delivery_rejected
            }), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
