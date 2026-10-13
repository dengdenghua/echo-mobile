package com.apk.claw.android.ui.compose.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * 加载失败的整页状态：先说发生了什么，再给一个「重试」。
 *
 * 和空状态分开：网络失败不能显示成「这里还没有内容」，否则用户会以为真的没有东西。
 */
@Composable
fun LoadFailedState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = OctopusSpacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = OctopusColors.TextMuted,
            modifier = Modifier.size(OctopusIconSize.large),
        )
        Spacer(Modifier.height(OctopusSpacing.md))
        Text(
            title,
            color = OctopusColors.TextPrimary,
            fontSize = OctopusType.bodyStrong,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            message,
            color = OctopusColors.TextMuted,
            fontSize = OctopusType.caption,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OctopusSpacing.lg))
        OutlinedButton(
            onClick = onRetry,
            shape = OctopusShape.small,
            border = BorderStroke(1.dp, OctopusColors.BorderHigh),
        ) {
            Text(stringResource(R.string.common_retry), color = OctopusColors.TextPrimary)
        }
    }
}
