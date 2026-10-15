package com.apk.claw.android.ui.compose.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.registry.PluginRegistryStore
import com.apk.claw.android.registry.RegistryAsset
import com.apk.claw.android.registry.RegistryClient
import com.apk.claw.android.ui.compose.component.OctopusCard
import com.apk.claw.android.ui.compose.component.OctopusTextPill
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.launch

/**
 * 插件商城 —— 从公网 registry 浏览/下载可执行插件(mini-app、browser-script、tool 等)。
 *
 * 下载的插件落地到 filesDir/plugins/<slug>/,由 [PluginRegistryStore] 管理,
 * [PluginManager] 在下次 loadAll() 时自动拾取。
 */
@Composable
fun PluginMarketplaceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tint = OctopusTints.Plugin

    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var plugins by remember { mutableStateOf<List<RegistryAsset>>(emptyList()) }
    var installedSlugs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    fun reloadInstalled() {
        installedSlugs = PluginRegistryStore.installed(ctx).map { it.slug }.toSet()
    }

    LaunchedEffect(Unit) {
        reloadInstalled()
        val list = RegistryClient.listPlugins()
        plugins = list
        loadFailed = list.isEmpty()
        loading = false
    }

    val categories = remember(plugins) {
        plugins.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
    }
    val filtered = remember(plugins, query, category) {
        plugins.filter { a ->
            (category == null || a.category == category) &&
                (query.isBlank() ||
                    a.name.contains(query, true) ||
                    a.description.contains(query, true) ||
                    a.slug.contains(query, true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding(),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OctopusSpacing.sm, end = OctopusSpacing.lg, top = OctopusSpacing.sm, bottom = OctopusSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = OctopusColors.TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.plugin_marketplace_title),
                    color = OctopusColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                Text(
                    stringResource(R.string.plugin_marketplace_subtitle, installedSlugs.size, plugins.size),
                    color = OctopusColors.TextMuted, fontSize = OctopusType.caption, maxLines = 1,
                )
            }
            Icon(Icons.Filled.Extension, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.medium))
        }

        // 搜索
        PluginSearchField(
            query = query,
            onQuery = { query = it },
            modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
        )

        // 分类筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            OctopusTextPill(
                text = stringResource(R.string.skill_filter_all),
                tint = tint,
                selected = category == null,
            ) { category = null }
            categories.forEach { c ->
                OctopusTextPill(text = c, tint = tint, selected = category == c) { category = c }
            }
        }

        // 列表
        when {
            loading -> EchoLoadingPanel(Modifier.fillMaxSize())
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (loadFailed) R.string.plugin_marketplace_load_failed else R.string.plugin_marketplace_empty),
                    color = OctopusColors.TextMuted, fontSize = OctopusType.body,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = OctopusSpacing.lg, end = OctopusSpacing.lg,
                    top = OctopusSpacing.xs, bottom = OctopusLayout.bottomNavContentPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
                verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
            ) {
                items(filtered, key = { it.id }) { asset ->
                    PluginStoreCard(
                        asset = asset,
                        tint = tint,
                        installed = asset.slug in installedSlugs,
                        installing = asset.slug in installing,
                        onInstall = {
                            installing = installing + asset.slug
                            scope.launch {
                                val data = RegistryClient.downloadPlugin(asset.id)
                                val err = if (data == null) ctx.getString(R.string.plugin_marketplace_download_failed)
                                          else PluginRegistryStore.install(ctx, asset, data)
                                installing = installing - asset.slug
                                reloadInstalled()
                                Toast.makeText(
                                    ctx,
                                    err ?: ctx.getString(R.string.plugin_marketplace_installed_toast, asset.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onUninstall = {
                            PluginRegistryStore.uninstall(ctx, asset.slug)
                            reloadInstalled()
                            Toast.makeText(ctx, ctx.getString(R.string.plugin_marketplace_uninstalled_toast, asset.name), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OctopusCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = OctopusColors.TextMuted, modifier = Modifier.size(OctopusIconSize.small))
            Spacer(Modifier.width(OctopusSpacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.plugin_marketplace_search_hint), color = OctopusColors.TextMuted, fontSize = OctopusType.body)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = OctopusColors.TextPrimary, fontSize = OctopusType.body),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(OctopusColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PluginStoreCard(
    asset: RegistryAsset,
    tint: Color,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OctopusSpacing.md)) {
            Text(
                asset.name,
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.bodyStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (asset.kind.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    asset.kind,
                    color = tint.copy(alpha = 0.8f),
                    fontSize = OctopusType.tag,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(
                asset.description,
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                asset.category?.takeIf { it.isNotBlank() }?.let { c ->
                    Text(c, color = tint, fontSize = OctopusType.tag, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.width(OctopusSpacing.sm))
                }
                Spacer(Modifier.weight(1f))
                PluginInstallButton(tint, installed, installing, onInstall, onUninstall)
            }
        }
    }
}

@Composable
private fun PluginInstallButton(
    tint: Color,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val label = when {
        installing -> stringResource(R.string.skill_installing)
        installed -> stringResource(R.string.skill_installed)
        else -> stringResource(R.string.skill_install)
    }
    val activeColor = if (installed) OctopusColors.TextMuted else tint
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(activeColor.copy(alpha = 0.14f))
            .border(1.dp, activeColor.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .then(
                if (installing) Modifier
                else Modifier.clickable { if (installed) onUninstall() else onInstall() }
            )
            .padding(horizontal = OctopusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = activeColor, fontSize = OctopusType.tag, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
