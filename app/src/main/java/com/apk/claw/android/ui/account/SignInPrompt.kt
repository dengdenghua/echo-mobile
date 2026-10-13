package com.apk.claw.android.ui.account

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountFeature
import com.apk.claw.android.account.AccountGate
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.widget.AlertDialog

/**
 * 「登录后可用」: what a local-mode user sees instead of an error when a feature needs the
 * account. A calm explanation plus 去登录, never a failed request.
 */
object SignInPrompt {

    @StringRes
    fun reasonRes(feature: AccountFeature): Int = when (feature) {
        AccountFeature.WALLET -> R.string.account_gate_wallet
        AccountFeature.COMMUNITY -> R.string.account_gate_community
        AccountFeature.REMOTE_CONSOLE -> R.string.account_gate_remote_console
        AccountFeature.VOICE_CALL -> R.string.account_gate_voice_call
        AccountFeature.MEDIA_GENERATION -> R.string.account_gate_media
        AccountFeature.CLOUD_SYNC -> R.string.account_gate_cloud_sync
    }

    /** Dialog with 去登录 / 暂不. [context] must be an Activity (the Compose LocalContext is one). */
    fun show(context: Context, feature: AccountFeature) {
        AlertDialog.show(
            context = context,
            title = context.getString(R.string.account_gate_title),
            message = context.getString(reasonRes(feature)),
            actionTitle = context.getString(R.string.account_gate_sign_in),
            cancelTitle = context.getString(R.string.account_gate_not_now),
            onAction = { context.startActivity(LoginActivity.signInIntent(context)) },
        )
    }

    /** True when [feature] can be used; otherwise shows the 「登录后可用」 dialog and returns false. */
    fun ensure(context: Context, feature: AccountFeature): Boolean {
        if (AccountGate.isAvailable(feature)) return true
        show(context, feature)
        return false
    }
}

/** Full-screen variant of [SignInPrompt] for screens that are entirely an account feature. */
@Composable
fun SignInRequiredPanel(
    feature: AccountFeature,
    onSignIn: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = OctopusSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = OctopusColors.TextMuted,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(OctopusSpacing.md))
        Text(
            stringResource(R.string.account_gate_title),
            color = OctopusColors.TextPrimary,
            fontSize = OctopusType.bodyLg,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OctopusSpacing.sm))
        Text(
            stringResource(SignInPrompt.reasonRes(feature)),
            color = OctopusColors.TextSecondary,
            fontSize = OctopusType.body,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OctopusSpacing.xl))
        Row {
            PanelButton(stringResource(R.string.account_gate_sign_in), primary = true, onClick = onSignIn)
            Spacer(Modifier.width(OctopusSpacing.md))
            PanelButton(stringResource(R.string.account_gate_not_now), primary = false, onClick = onClose)
        }
    }
}

@Composable
private fun PanelButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(OctopusShape.capsule)
            .background(if (primary) OctopusColors.Primary else OctopusColors.FillSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.xl, vertical = OctopusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) OctopusColors.OnPrimary else OctopusColors.TextPrimary,
            fontSize = OctopusType.body,
            fontWeight = FontWeight.Medium,
        )
    }
}
