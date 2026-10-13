package com.apk.claw.android.ui.compose

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.update.AppUpdater
import com.apk.claw.android.update.InstallVerdict
import kotlinx.coroutines.launch

private const val PERCENT_MAX = 100

/**
 * 在线更新弹窗宿主 —— 挂在 App 根部(MainActivity)。观察 [AppUpdater.available]:
 * 一旦发现新版本(自动启动检查 或 设置页手动检查),弹出「发现新版本」对话框,
 * 点更新即下载(带进度)并拉起系统安装器。force=true 时不给「稍后」。
 *
 * 新版本签名不同(服务端声明的指纹对不上,或下载后校验出来)时改弹「先卸载」说明:
 * 系统不允许覆盖安装,与其让安装器报「应用未安装」,不如先说清楚怎么做、会丢什么。
 */
@Composable
fun AppUpdateHost() {
    val info by AppUpdater.available.collectAsState()
    val u = info ?: return
    if (u.reinstallRequired) ReinstallDialog(u) else UpdateDialog(u)
}

@Composable
private fun UpdateDialog(u: AppUpdater.UpdateInfo) {
    val ctx = LocalContext.current
    var progress by remember(u) { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!u.force && progress == null) AppUpdater.dismiss() },
        title = { Text(stringResource(R.string.update_found_title, u.versionName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(u.notes.ifBlank { stringResource(R.string.update_default_notes) })
                progress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_downloading_progress, (p * PERCENT_MAX).toInt()))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null,
                onClick = {
                    progress = 0f
                    scope.launch {
                        val r = AppUpdater.downloadAndInstall(ctx, u) { p -> progress = p }
                        if (r.isFailure) {
                            progress = null
                            failureMessage(ctx, r.exceptionOrNull())?.let {
                                Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
                            }
                        }
                        // 成功:系统安装器已拉起,弹窗保留(用户装完回来自然消失)。
                    }
                },
            ) {
                Text(stringResource(if (progress == null) R.string.update_now else R.string.update_downloading))
            }
        },
        dismissButton = {
            if (!u.force && progress == null) {
                TextButton(onClick = { AppUpdater.dismiss() }) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}

/** Null for a signature mismatch: the host has already switched to [ReinstallDialog]. */
private fun failureMessage(ctx: Context, error: Throwable?): String? =
    when ((error as? AppUpdater.CannotInstallException)?.verdict) {
        InstallVerdict.SIGNATURE_MISMATCH -> null
        InstallVerdict.CORRUPT -> ctx.getString(R.string.update_package_corrupt)
        InstallVerdict.OTHER_APP -> ctx.getString(R.string.update_package_other_app)
        InstallVerdict.NOT_NEWER -> ctx.getString(R.string.update_package_not_newer)
        else -> ctx.getString(R.string.update_download_failed, error?.message.orEmpty())
    }

/**
 * 「先卸载」说明。force 在这里不生效:按钮装不上,强制弹窗只会把人困住。
 * 「用浏览器下载」把安装包存进公共「下载」目录,卸载本 App 后还在(App 自己的下载目录会随卸载清空)。
 */
@Composable
private fun ReinstallDialog(u: AppUpdater.UpdateInfo) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { AppUpdater.dismiss() },
        title = { Text(stringResource(R.string.update_reinstall_title, u.versionName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.update_reinstall_body))
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(u.url, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { openInBrowser(ctx, u.url) }) {
                Text(stringResource(R.string.update_reinstall_download))
            }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdater.dismiss() }) { Text(stringResource(R.string.update_later)) }
        },
    )
}

private fun openInBrowser(ctx: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Echo", url))
        Toast.makeText(ctx, ctx.getString(R.string.update_link_copied, url), Toast.LENGTH_LONG).show()
    }
}
