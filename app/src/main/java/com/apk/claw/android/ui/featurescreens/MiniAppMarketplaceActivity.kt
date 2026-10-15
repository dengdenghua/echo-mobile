package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.registry.CommunityMiniApp
import com.apk.claw.android.registry.CommunityMiniAppInstaller
import com.apk.claw.android.registry.CommunitySquareApi
import com.apk.claw.android.registry.PluginRegistryStore
import kotlinx.coroutines.launch
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.ui.compose.component.LoadFailedState
import androidx.compose.ui.res.stringResource

/**
 * 广场小程序商城 —— 浏览 + 安装其他用户投稿并审核通过的社区小程序。
 *
 * 与「小程序」列表([MiniAppListActivity],管理**已装**小程序 + 权限)分开成独立入口:
 * 这里是「发现广场上还没装的小程序」，语义上是浏览远端目录而非管理本地已装项，跟
 * 插件商城([com.apk.claw.android.ui.compose.screen.PluginMarketplaceScreen])与
 * 「已装插件管理」分离是同一套原则。安装走 [CommunityMiniAppInstaller]，落地到
 * [com.apk.claw.android.plugin.PluginManager]「files」来源已有的、经 sha256 校验的同一条路径
 * (与本地生成小程序的 GenerateAppTool 共用)，不是另起一套安装机制。
 */
class MiniAppMarketplaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { MiniAppMarketplaceScreen(onBack = { finish() }) }
    }
}

@Composable
private fun MiniAppMarketplaceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<CommunityMiniApp>>(emptyList()) }
    var installedSlugs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installingSlug by remember { mutableStateOf<String?>(null) }

    fun reloadInstalled() {
        installedSlugs = PluginRegistryStore.installed(ctx).map { it.slug }.toSet()
    }

    fun reload() {
        loading = true
        loadError = null
        scope.launch {
            reloadInstalled()
            val result = CommunitySquareApi.list()
            result.onSuccess { apps = it }
                .onFailure { loadError = it.message.orEmpty() }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    FeatureScaffold(title = stringResource(R.string.square_apps_title), onBack = onBack) {
        when {
            loading -> EchoLoadingPanel()
            // 网络失败不是「没有内容」:说发生了什么,给一个重试
            loadError != null -> LoadFailedState(
                title = stringResource(R.string.market_load_failed),
                message = stringResource(R.string.market_load_failed_desc),
                onRetry = { reload() },
            )
            apps.isEmpty() -> FEmpty(stringResource(R.string.square_apps_empty))
            else -> Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                apps.forEach { app ->
                    CommunityMiniAppCard(
                        app = app,
                        installed = app.effectiveSlug in installedSlugs,
                        installing = installingSlug == app.effectiveSlug,
                        onInstall = {
                            if (installingSlug == null) {
                                installingSlug = app.effectiveSlug
                                scope.launch {
                                    val result = CommunitySquareApi.download(app.effectiveSlug)
                                    val err = if (result.isFailure) {
                                        ctx.getString(R.string.square_apps_download_failed)
                                    } else {
                                        CommunityMiniAppInstaller.install(ctx, result.getOrThrow())
                                            ?.let { ctx.getString(R.string.post_detail_install_failed, it) }
                                    }
                                    installingSlug = null
                                    reloadInstalled()
                                    android.widget.Toast.makeText(
                                        ctx,
                                        err ?: ctx.getString(R.string.square_apps_installed_toast, app.name),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityMiniAppCard(
    app: CommunityMiniApp,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    FCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.name.ifBlank { app.effectiveSlug },
                    color = FText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                if (app.description.isNotBlank()) {
                    Text(
                        app.description, color = FMuted, fontSize = 12.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("v${app.version.ifBlank { "1.0.0" }}", color = FMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            CommunityInstallButton(installed = installed, installing = installing, onInstall = onInstall)
        }
    }
}

@Composable
private fun CommunityInstallButton(installed: Boolean, installing: Boolean, onInstall: () -> Unit) {
    val label = stringResource(
        when {
            installing -> R.string.square_apps_installing
            installed -> R.string.square_apps_installed
            else -> R.string.square_apps_install
        },
    )
    val activeColor = if (installed) FMuted else FPrimary
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(activeColor.copy(alpha = 0.14f))
            .border(1.dp, activeColor.copy(alpha = 0.42f), RoundedCornerShape(15.dp))
            .then(
                if (installing || installed) Modifier else Modifier.clickable(onClick = onInstall)
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = activeColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
