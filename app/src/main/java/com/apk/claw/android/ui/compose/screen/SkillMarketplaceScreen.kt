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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.apk.claw.android.registry.RegistryAsset
import com.apk.claw.android.registry.RegistryClient
import com.apk.claw.android.registry.RegistrySkillStore
import com.apk.claw.android.registry.mobileFit
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
 * 技能商城(广场 → 能力)。
 *
 * 从公网 registry 浏览/下载技能,落地本地缓存,与内置技能**并存**(内置一律保留)。
 * 下载的「指令型」技能由注入通道([RegistrySkillStore.enabledKnowledge])喂给 agent,
 * 不混进可执行工具列表(见 [RegistryClient])。
 */
@Composable
fun SkillMarketplaceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tint = OctopusTints.Skill

    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf<List<RegistryAsset>>(emptyList()) }
    var installedSlugs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var mobileOnly by remember { mutableStateOf(true) }   // 默认只显示适合手机的(option 3)

    fun reloadInstalled() { installedSlugs = RegistrySkillStore.installed(ctx).map { it.slug }.toSet() }

    LaunchedEffect(Unit) {
        reloadInstalled()
        val list = RegistryClient.listSkills()
        skills = list
        loadFailed = list.isEmpty()
        loading = false
    }

    val categories = remember(skills) {
        skills.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
    }
    val filtered = remember(skills, query, category, mobileOnly) {
        skills.filter { a ->
            (!mobileOnly || a.mobileFit) &&
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
        // 顶栏:返回 + 标题 + 计数
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
                    stringResource(R.string.skill_marketplace_title),
                    color = OctopusColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                Text(
                    stringResource(R.string.skill_marketplace_subtitle, installedSlugs.size, skills.size),
                    color = OctopusColors.TextMuted, fontSize = OctopusType.caption, maxLines = 1,
                )
            }
        }

        // 搜索框
        SearchField(query = query, onQuery = { query = it }, modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs))

        // 筛选行:仅手机适配(默认开)+ 分类
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            OctopusTextPill(
                text = stringResource(R.string.skill_filter_mobile),
                tint = tint,
                selected = mobileOnly,
            ) { mobileOnly = !mobileOnly }
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
                    stringResource(if (loadFailed) R.string.skill_load_failed else R.string.skill_empty),
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
                    SkillStoreCard(
                        asset = asset,
                        tint = tint,
                        installed = asset.slug in installedSlugs,
                        installing = asset.slug in installing,
                        onInstall = {
                            installing = installing + asset.slug
                            scope.launch {
                                val err = RegistrySkillStore.install(ctx, asset)
                                installing = installing - asset.slug
                                reloadInstalled()
                                Toast.makeText(
                                    ctx,
                                    err ?: ctx.getString(R.string.skill_installed_toast, asset.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onUninstall = {
                            RegistrySkillStore.uninstall(ctx, asset.slug)
                            reloadInstalled()
                            Toast.makeText(ctx, ctx.getString(R.string.skill_uninstalled_toast, asset.name), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OctopusCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = OctopusColors.TextMuted, modifier = Modifier.size(OctopusIconSize.small))
            Spacer(Modifier.width(OctopusSpacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.skill_search_hint), color = OctopusColors.TextMuted, fontSize = OctopusType.body)
                }
                BasicTextField(
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
private fun SkillStoreCard(
    asset: RegistryAsset,
    tint: Color,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OctopusSpacing.md)) {
            Text(asset.name, color = OctopusColors.TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(asset.description, color = OctopusColors.TextMuted, fontSize = OctopusType.caption, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(OctopusSpacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                asset.category?.takeIf { it.isNotBlank() }?.let { c ->
                    Text(c, color = tint, fontSize = OctopusType.tag, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.width(OctopusSpacing.sm))
                }
                Spacer(Modifier.weight(1f))
                InstallButton(tint, installed, installing, onInstall, onUninstall)
            }
        }
    }
}

@Composable
private fun InstallButton(
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
