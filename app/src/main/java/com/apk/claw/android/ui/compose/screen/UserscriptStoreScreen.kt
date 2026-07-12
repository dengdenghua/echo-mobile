package com.apk.claw.android.ui.compose.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.ui.browser.UserscriptStore
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.tvFocusable
import com.apk.claw.android.ui.compose.theme.tvOverscan
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import okhttp3.Request

/**
 * 油猴脚本商店 —— 已安装 / 推荐两个 Tab。
 *
 * - 已安装 Tab：列出 [UserscriptStore.listInstalled]，每张卡片支持启用开关 + 卸载。
 * - 推荐 Tab：内置 8 个常用脚本（广告拦截/夜间模式/页面翻译 等），点击即安装。
 * - 底部「从 URL 安装」：输入脚本 URL，后台下载并解析安装。
 *
 * 进入页面时把已安装脚本同步到 [com.apk.claw.android.octopus_mobile.browser.BrowserPluginHost]，
 * 确保浏览器加载下个页面时按最新列表注入。
 */
@Composable
fun UserscriptStoreScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(UserscriptStore.listInstalled()) }
    var showInstallUrlDialog by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    fun refresh() { installed = UserscriptStore.listInstalled() }

    // 进入页面时同步一次：保证商店里装的脚本对浏览器生效
    LaunchedEffect(Unit) {
        runCatching { UserscriptStore.syncToPluginHost(ctx) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
            .tvOverscan(),
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = OctopusColors.TextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                stringResource(R.string.userscript_store_title),
                modifier = Modifier.weight(1f),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleLg,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = { showInstallUrlDialog = true }, enabled = !downloading) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = OctopusColors.Primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.userscript_install_from_url),
                        tint = OctopusColors.Primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // ── Tab 切换 ──
        StoreTabs(selected = tab) { tab = it }

        // ── 内容 ──
        if (tab == 0) {
            InstalledTab(
                installed = installed,
                onToggle = { id ->
                    UserscriptStore.toggle(id)
                    runCatching { UserscriptStore.syncToPluginHost(ctx) }
                    refresh()
                },
                onUninstall = { id ->
                    UserscriptStore.uninstall(id)
                    runCatching { UserscriptStore.syncToPluginHost(ctx) }
                    refresh()
                },
            )
        } else {
            RecommendTab(
                installedIds = installed.map { it.id }.toSet(),
                onInstall = { script ->
                    val entry = UserscriptStore.install(script.code, source = "store")
                    if (entry != null) {
                        runCatching { UserscriptStore.syncToPluginHost(ctx) }
                        refresh()
                        Toast.makeText(ctx, ctx.getString(R.string.userscript_installed_toast, entry.name), Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    if (showInstallUrlDialog) {
        InstallFromUrlDialog(
            downloading = downloading,
            onDismiss = { showInstallUrlDialog = false },
            onStart = { url ->
                downloading = true
                Thread {
                    val result = downloadAndInstall(url)
                    downloading = false
                    mainHandler.post {
                        result.onSuccess { name ->
                            runCatching { UserscriptStore.syncToPluginHost(ctx) }
                            refresh()
                            Toast.makeText(ctx, ctx.getString(R.string.userscript_installed_toast, name), Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            XLog.w("UserscriptStore", "install from url failed: ${e.message}")
                            Toast.makeText(ctx, ctx.getString(R.string.userscript_install_failed_toast, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }.apply { isDaemon = true }.start()
            },
        )
    }
}

private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

// ── Tab ──────────────────────────────────────

@Composable
private fun StoreTabs(selected: Int, onChange: (Int) -> Unit) {
    val tabs = listOf(
        R.string.userscript_tab_installed,
        R.string.userscript_tab_recommend,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        tabs.forEachIndexed { index, res ->
            val isSelected = index == selected
            Surface(
                shape = OctopusShape.capsule,
                color = if (isSelected) OctopusColors.Primary else OctopusColors.SurfaceDeep.copy(alpha = 0.5f),
                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.6f)),
                modifier = Modifier.clickable { onChange(index) }.tvFocusable(),
            ) {
                Text(
                    stringResource(res),
                    modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
                    color = if (isSelected) OctopusColors.OnPrimary else OctopusColors.TextSecondary,
                    fontSize = OctopusType.body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

// ── 已安装 Tab ────────────────────────────────

@Composable
private fun InstalledTab(
    installed: List<UserscriptStore.UserscriptEntry>,
    onToggle: (String) -> Unit,
    onUninstall: (String) -> Unit,
) {
    if (installed.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.userscript_empty_installed),
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.body,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(OctopusSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        items(installed, key = { it.id }) { entry ->
            UserscriptCard(
                name = entry.name,
                author = entry.author,
                version = entry.version,
                matchPatterns = entry.matchPatterns,
                description = entry.description,
                enabled = entry.enabled,
                icon = iconForName(entry.name),
                onToggle = { onToggle(entry.id) },
                onUninstall = { onUninstall(entry.id) },
            )
        }
    }
}

// ── 推荐 Tab ─────────────────────────────────

@Composable
private fun RecommendTab(
    installedIds: Set<String>,
    onInstall: (RecommendedScript) -> Unit,
) {
    val scripts = remember { RecommendedScripts.all }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(OctopusSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        items(scripts, key = { it.id }) { script ->
            val installed = script.id in installedIds
            RecommendCard(
                name = script.name,
                description = script.description,
                author = script.author,
                version = script.version,
                matchPatterns = script.matchPatterns,
                icon = script.icon,
                installed = installed,
                onInstall = { onInstall(script) },
            )
        }
    }
}

// ── 卡片 ─────────────────────────────────────

@Composable
private fun UserscriptCard(
    name: String,
    author: String,
    version: String,
    matchPatterns: List<String>,
    description: String,
    enabled: Boolean,
    icon: ImageVector,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
) {
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.solidSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OctopusBackground.solidBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .tvFocusable(),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(OctopusColors.Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = OctopusColors.Primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(OctopusSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        color = OctopusColors.TextPrimary,
                        fontSize = OctopusType.bodyStrong,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildString {
                        if (author.isNotBlank()) append(author)
                        if (version.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("v$version")
                        }
                    }
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            color = OctopusColors.TextSecondary,
                            fontSize = OctopusType.tag,
                        )
                    }
                }
                Switch(checked = enabled, onCheckedChange = { onToggle() })
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(OctopusSpacing.sm))
                Text(
                    description,
                    color = OctopusColors.TextSecondary,
                    fontSize = OctopusType.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (matchPatterns.isNotEmpty()) {
                Spacer(Modifier.height(OctopusSpacing.sm))
                PatternChips(matchPatterns)
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onUninstall) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(stringResource(R.string.userscript_uninstall), color = OctopusColors.TextMuted, fontSize = OctopusType.caption)
                }
            }
        }
    }
}

@Composable
private fun RecommendCard(
    name: String,
    description: String,
    author: String,
    version: String,
    matchPatterns: List<String>,
    icon: ImageVector,
    installed: Boolean,
    onInstall: () -> Unit,
) {
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.solidSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OctopusBackground.solidBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .tvFocusable(),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(OctopusColors.Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = OctopusColors.Primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(OctopusSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        color = OctopusColors.TextPrimary,
                        fontSize = OctopusType.bodyStrong,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildString {
                        if (author.isNotBlank()) append(author)
                        if (version.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("v$version")
                        }
                    }
                    if (meta.isNotBlank()) {
                        Text(meta, color = OctopusColors.TextSecondary, fontSize = OctopusType.tag)
                    }
                }
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(OctopusSpacing.sm))
                Text(
                    description,
                    color = OctopusColors.TextSecondary,
                    fontSize = OctopusType.caption,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (matchPatterns.isNotEmpty()) {
                Spacer(Modifier.height(OctopusSpacing.sm))
                PatternChips(matchPatterns)
            }
            Spacer(Modifier.height(OctopusSpacing.sm))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onInstall, enabled = !installed) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        if (installed) stringResource(R.string.userscript_installed_label)
                        else stringResource(R.string.userscript_install),
                        color = if (installed) OctopusColors.TextMuted else OctopusColors.Primary,
                        fontSize = OctopusType.caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternChips(patterns: List<String>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        patterns.take(3).forEach { p ->
            Surface(
                shape = OctopusShape.capsule,
                color = OctopusColors.SurfaceDeep.copy(alpha = 0.6f),
            ) {
                Text(
                    p,
                    modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = 2.dp),
                    color = OctopusColors.TextSecondary,
                    fontSize = OctopusType.micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (patterns.size > 3) {
            Text(
                "+${patterns.size - 3}",
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.micro,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

// ── 从 URL 安装对话框 ─────────────────────────

@Composable
private fun InstallFromUrlDialog(
    downloading: Boolean,
    onDismiss: () -> Unit,
    onStart: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(stringResource(R.string.userscript_install_from_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                enabled = !downloading,
                placeholder = { Text("https://example.com/script.user.js", fontSize = OctopusType.caption) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !downloading && url.isNotBlank(),
                onClick = { onStart(url.trim()) },
            ) {
                Text(stringResource(R.string.userscript_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

// ── 下载安装 ─────────────────────────────────

private fun downloadAndInstall(url: String): Result<String> = runCatching {
    val req = Request.Builder().url(url).build()
    OctoHttp.shared.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) error("empty body")
        val entry = UserscriptStore.install(body, source = "url")
            ?: error("parse failed")
        entry.name
    }
}

// ── 推荐脚本 ─────────────────────────────────

data class RecommendedScript(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val matchPatterns: List<String>,
    val icon: ImageVector,
    val code: String,
)

object RecommendedScripts {
    val all: List<RecommendedScript> = listOf(
        RecommendedScript(
            id = "userscript_ad_block",
            name = "广告拦截",
            description = "隐藏常见广告元素（AdSense/百度推广/弹窗/悬浮广告），页面更干净。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.Block,
            code = AD_BLOCK_JS,
        ),
        RecommendedScript(
            id = "userscript_night_mode",
            name = "夜间模式",
            description = "向页面注入暗色主题样式，保护视力，适合夜间浏览。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.Nightlight,
            code = NIGHT_MODE_JS,
        ),
        RecommendedScript(
            id = "userscript_translate",
            name = "页面翻译",
            description = "一键跳转到 Google Translate 翻译当前页面。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.Translate,
            code = TRANSLATE_JS,
        ),
        RecommendedScript(
            id = "userscript_video_speed",
            name = "视频速度控制",
            description = "为页面视频添加倍速控制（0.5x ~ 3x），快捷键 [ / ] 调速。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.FastForward,
            code = VIDEO_SPEED_JS,
        ),
        RecommendedScript(
            id = "userscript_unblock_context",
            name = "解除右键限制",
            description = "移除网站对 oncontextmenu / onselectstart / ondragstart 的拦截。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.TouchApp,
            code = UNBLOCK_CONTEXT_JS,
        ),
        RecommendedScript(
            id = "userscript_auto_pager",
            name = "自动翻页",
            description = "滚动到页面底部时自动点击「下一页」链接，实现无限滚动。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.VerticalAlignBottom,
            code = AUTO_PAGER_JS,
        ),
        RecommendedScript(
            id = "userscript_clean_read",
            name = "简洁阅读",
            description = "移除侧边栏 / 广告 / 评论等干扰元素，聚焦正文。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.AutoFixHigh,
            code = CLEAN_READ_JS,
        ),
        RecommendedScript(
            id = "userscript_form_fill",
            name = "表单自动填充",
            description = "记忆表单输入内容，下次访问同站点自动回填。",
            author = "Octopus",
            version = "1.0.0",
            matchPatterns = listOf("*://*/*"),
            icon = Icons.Filled.NoteAlt,
            code = FORM_FILL_JS,
        ),
    )
}

@Composable
private fun iconForName(name: String): ImageVector = when {
    name.contains("广告") -> Icons.Filled.Block
    name.contains("夜间") || name.contains("暗") -> Icons.Filled.Nightlight
    name.contains("翻译") -> Icons.Filled.Translate
    name.contains("视频") -> Icons.Filled.FastForward
    name.contains("右键") -> Icons.Filled.TouchApp
    name.contains("翻页") -> Icons.Filled.VerticalAlignBottom
    name.contains("阅读") -> Icons.Filled.AutoFixHigh
    name.contains("表单") -> Icons.Filled.NoteAlt
    else -> Icons.Filled.Science
}

// ── 脚本源码 ─────────────────────────────────

private const val AD_BLOCK_JS = """
// ==UserScript==
// @name         广告拦截
// @namespace    octopus/ad-block
// @version      1.0.0
// @description  隐藏常见广告元素
// @author       Octopus
// @match        *://*/*
// @grant        GM_addStyle
// @run-at       document-start
// ==/UserScript==
(function () {
  'use strict';
  GM_addStyle([
    'iframe[src*="ads"], iframe[src*="doubleclick"], iframe[src*="googlesyndication"]{display:none!important}',
    'div[id*="ad-"], div[id*="ads-"], div[id*="advert"], div[class*="ad-banner"], div[class*="ad_box"]{display:none!important}',
    'ins.adsbygoogle, [id*="AdSense"], [class*="ad-slot"]{display:none!important}',
    'div[class*="popup-ad"], div[class*="float-ad"], div[id*="layer-ad"]{display:none!important}',
    '[class*="sponsor"], [id*="sponsor"], [class*="promotion"]{display:none!important}'
  ].join('\n'));
})();
"""

private const val NIGHT_MODE_JS = """
// ==UserScript==
// @name         夜间模式
// @namespace    octopus/night-mode
// @version      1.0.0
// @description  页面暗色主题注入
// @author       Octopus
// @match        *://*/*
// @grant        GM_addStyle
// @run-at       document-end
// ==/UserScript==
(function () {
  'use strict';
  GM_addStyle(
    'html{filter:invert(1) hue-rotate(180deg) !important;background:#222 !important;}' +
    'img,video,iframe,canvas,svg,[style*="background-image"]{filter:invert(1) hue-rotate(180deg) !important;}'
  );
})();
"""

private const val TRANSLATE_JS = """
// ==UserScript==
// @name         页面翻译
// @namespace    octopus/translate
// @version      1.0.0
// @description  调用 Google Translate 翻译当前页面
// @author       Octopus
// @match        *://*/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==
(function () {
  'use strict';
  var btn = document.createElement('div');
  btn.textContent = '译';
  btn.style.cssText = 'position:fixed;right:16px;bottom:80px;width:44px;height:44px;border-radius:50%;' +
    'background:#5856D6;color:#fff;font-size:18px;font-weight:bold;display:flex;align-items:center;' +
    'justify-content:center;cursor:pointer;z-index:99999;box-shadow:0 2px 8px rgba(0,0,0,0.3);';
  btn.onclick = function () {
    var u = 'https://translate.google.com/translate?sl=auto&tl=zh-CN&u=' + encodeURIComponent(location.href);
    location.href = u;
  };
  document.body.appendChild(btn);
})();
"""

private const val VIDEO_SPEED_JS = """
// ==UserScript==
// @name         视频速度控制
// @namespace    octopus/video-speed
// @version      1.0.0
// @description  视频播放器倍速控制
// @author       Octopus
// @match        *://*/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==
(function () {
  'use strict';
  function applyRate(r) {
    var vids = document.querySelectorAll('video');
    vids.forEach(function (v) { v.playbackRate = r; });
  }
  document.addEventListener('keydown', function (e) {
    if (e.key === '[') {
      var v = document.querySelector('video');
      if (v) { v.playbackRate = Math.max(0.5, v.playbackRate - 0.25); }
    } else if (e.key === ']') {
      var v2 = document.querySelector('video');
      if (v2) { v2.playbackRate = Math.min(3, v2.playbackRate + 0.25); }
    }
  });
  // 注入小提示
  var tip = document.createElement('div');
  tip.textContent = '[ 减速 | ] 加速';
  tip.style.cssText = 'position:fixed;right:16px;bottom:120px;background:rgba(0,0,0,0.6);color:#fff;' +
    'padding:6px 10px;border-radius:6px;font-size:12px;z-index:99999;';
  document.body.appendChild(tip);
  setTimeout(function () { tip.style.display = 'none'; }, 4000);
})();
"""

private const val UNBLOCK_CONTEXT_JS = """
// ==UserScript==
// @name         解除右键限制
// @namespace    octopus/unblock-context
// @version      1.0.0
// @description  移除 oncontextmenu 拦截
// @author       Octopus
// @match        *://*/*
// @grant        none
// @run-at       document-start
// ==/UserScript==
(function () {
  'use strict';
  var props = ['oncontextmenu', 'onselectstart', 'ondragstart', 'oncopy', 'oncut', 'onpaste'];
  function unblock() {
    props.forEach(function (p) {
      document[p] = null;
      try { Object.defineProperty(document, p, { get: function () { return null; }, set: function () {}, configurable: true }); } catch (e) {}
    });
    document.querySelectorAll('*').forEach(function (el) {
      props.forEach(function (p) { el[p] = null; });
    });
  }
  unblock();
  document.addEventListener('DOMContentLoaded', unblock);
})();
"""

private const val AUTO_PAGER_JS = """
// ==UserScript==
// @name         自动翻页
// @namespace    octopus/auto-pager
// @version      1.0.0
// @description  滚动到底部自动点击下一页
// @author       Octopus
// @match        *://*/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==
(function () {
  'use strict';
  function findNextLink() {
    var links = document.querySelectorAll('a');
    for (var i = 0; i < links.length; i++) {
      var t = (links[i].textContent || '').trim();
      if (/^(下一页|下页|next\s*→?|next page)$/i.test(t)) return links[i];
    }
    var byRel = document.querySelector('a[rel="next"]');
    if (byRel) return byRel;
    return null;
  }
  var loading = false;
  window.addEventListener('scroll', function () {
    if (loading) return;
    if (window.innerHeight + window.scrollY >= document.body.scrollHeight - 200) {
      var next = findNextLink();
      if (next) {
        loading = true;
        next.click();
      }
    }
  });
})();
"""

private const val CLEAN_READ_JS = """
// ==UserScript==
// @name         简洁阅读
// @namespace    octopus/clean-read
// @version      1.0.0
// @description  移除侧边栏/广告/评论
// @author       Octopus
// @match        *://*/*
// @grant        GM_addStyle
// @run-at       document-end
// ==/UserScript==
(function () {
  'use strict';
  GM_addStyle(
    'aside,[role="complementary"],.sidebar,#sidebar,[class*="side-bar"],' +
    '.comments,#comments,[id*="comment-list"],[class*="ad-"],' +
    '[class*="related-posts"],.popup,.modal-backdrop{display:none!important;}'
  );
  document.querySelectorAll('aside, [role="complementary"], .sidebar, #sidebar').forEach(function (el) {
    el.style.display = 'none';
  });
})();
"""

private const val FORM_FILL_JS = """
// ==UserScript==
// @name         表单自动填充
// @namespace    octopus/form-fill
// @version      1.0.0
// @description  记忆并填充表单
// @author       Octopus
// @match        *://*/*
// @grant        GM_getValue
// @grant        GM_setValue
// @run-at       document-idle
// ==/UserScript==
(function () {
  'use strict';
  var key = 'octopus_form_' + location.host;
  // 回填
  try {
    var saved = JSON.parse(GM_getValue(key, '{}') || '{}');
    document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], textarea').forEach(function (el) {
      var k = el.name || el.id || el.placeholder;
      if (k && saved[k]) {
        setTimeout(function () { el.value = saved[k]; el.dispatchEvent(new Event('input', { bubbles: true })); }, 300);
      }
    });
  } catch (e) {}
  // 提交时记忆
  document.addEventListener('submit', function () {
    var data = {};
    document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], textarea').forEach(function (el) {
      var k = el.name || el.id || el.placeholder;
      if (k && el.value) data[k] = el.value;
    });
    try { GM_setValue(key, JSON.stringify(data)); } catch (e) {}
  }, true);
})();
"""
