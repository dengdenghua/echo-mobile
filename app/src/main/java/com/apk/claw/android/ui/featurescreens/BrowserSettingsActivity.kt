package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.browser.BrowserWallpaperStore
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.octopus_mobile.browser.SystemWebViewEngine
import com.apk.claw.android.utils.KVUtils

/**
 * 浏览器设置 —— 默认搜索引擎选择 + 内核信息 + 插件入口。
 * 视觉与「功能」中心一致(iOS 分组列表)。只暴露真实可用项,不放假开关。
 */
class BrowserSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { BrowserSettingsScreen(onBack = { finish() }) }
    }
}

private val ChipBg = Color(0xFF1A2029)
private const val ZOOM_SMALL = 85
private const val ZOOM_NORMAL = 100
private const val ZOOM_LARGE = 120
private const val ZOOM_XLARGE = 150

@Composable
@Suppress("LongMethod")
private fun BrowserSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var engineId by remember { mutableStateOf(KVUtils.getSearchEngine()) }
    var desktopMode by remember { mutableStateOf(KVUtils.getBrowserDesktopMode()) }
    var textZoom by remember { mutableIntStateOf(KVUtils.getBrowserTextZoom()) }
    var hasWallpaper by remember { mutableStateOf(BrowserWallpaperStore.has(ctx)) }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && BrowserWallpaperStore.set(ctx, uri)) hasWallpaper = true
    }

    FeatureScaffold(title = stringResource(R.string.browser_settings_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // 默认搜索引擎(改动即时,搜索框也读同一偏好)。
            FSectionTitle(stringResource(R.string.browser_settings_search_engine))
            GroupCard {
                SearchEngines.ALL.forEachIndexed { i, e ->
                    EngineRow(
                        tag = e.tag,
                        name = e.label,
                        selected = e.id == engineId,
                        onClick = { KVUtils.setSearchEngine(e.id); engineId = e.id },
                    )
                    if (i != SearchEngines.ALL.lastIndex) RowDivider()
                }
            }

            // 浏览:桌面模式 + 文字大小(下次打开页面/刷新生效)。
            FSectionTitle(stringResource(R.string.browser_settings_browsing))
            GroupCard {
                SwitchRow(
                    title = stringResource(R.string.browser_settings_desktop_mode),
                    subtitle = stringResource(R.string.browser_settings_desktop_mode_hint),
                    checked = desktopMode,
                    onCheckedChange = { desktopMode = it; KVUtils.setBrowserDesktopMode(it) },
                )
                RowDivider()
                TextSizeRow(
                    current = textZoom,
                    onSelect = { textZoom = it; KVUtils.setBrowserTextZoom(it) },
                )
            }

            // 背景壁纸:自定义上传,首页背景显示。
            FSectionTitle(stringResource(R.string.browser_settings_wallpaper))
            GroupCard {
                ClickableRow(
                    title = stringResource(
                        if (hasWallpaper) R.string.browser_settings_wallpaper_change
                        else R.string.browser_settings_wallpaper_upload,
                    ),
                    subtitle = stringResource(R.string.browser_settings_wallpaper_hint),
                    onClick = { wallpaperPicker.launch("image/*") },
                )
                if (hasWallpaper) {
                    RowDivider()
                    ClickableRow(
                        title = stringResource(R.string.browser_settings_wallpaper_clear),
                        subtitle = "",
                        onClick = { BrowserWallpaperStore.clear(ctx); hasWallpaper = false },
                    )
                }
            }

            // 清除浏览数据:缓存 / Cookie / 网站存储。
            FSectionTitle(stringResource(R.string.browser_settings_clear_data))
            GroupCard {
                ClickableRow(
                    title = stringResource(R.string.browser_settings_clear_data),
                    subtitle = stringResource(R.string.browser_settings_clear_data_hint),
                    onClick = {
                        SystemWebViewEngine.clearBrowsingData(ctx)
                        Toast.makeText(ctx, ctx.getString(R.string.browser_settings_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FText, fontSize = 16.sp)
            Text(subtitle, color = FMuted, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FPrimary,
                checkedTrackColor = FPrimary.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun TextSizeRow(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.browser_settings_text_small) to ZOOM_SMALL,
        stringResource(R.string.browser_settings_text_normal) to ZOOM_NORMAL,
        stringResource(R.string.browser_settings_text_large) to ZOOM_LARGE,
        stringResource(R.string.browser_settings_text_xlarge) to ZOOM_XLARGE,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.browser_settings_text_size),
            color = FText, fontSize = 16.sp, modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, zoom) ->
                val on = zoom == current
                Box(
                    modifier = Modifier
                        .background(if (on) FPrimary.copy(alpha = 0.18f) else ChipBg, RoundedCornerShape(8.dp))
                        .clickable { onSelect(zoom) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = if (on) FPrimary else FMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClickableRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FText, fontSize = 16.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = FMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = FSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun RowDivider() {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(FBorder))
}

@Composable
private fun EngineRow(tag: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(ChipBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(tag.take(1), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(name, color = FText, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = FPrimary, modifier = Modifier.size(20.dp))
    }
}
