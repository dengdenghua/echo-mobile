package com.apk.claw.android.ui.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 统一主题入口。
 *
 * 暗色模式策略（统一双轨）：
 * - 默认跟随系统 [isSystemInDarkTheme]。
 * - 用户可在设置页强制切换（写入 KVUtils.isLightTheme），此时覆盖系统值。
 * - 切换后同步更新 [OctopusColors.isLight]，保证所有读取 OctopusColors 的 Composable 重组。
 *
 * 颜色方案：所有 token 来自 [OctopusColors]（即 XML colors.xml 单一真源），
 * 与 View 体系 [com.apk.claw.android.R.style.Theme_OctopusMobile] 完全对齐。
 */
@Composable
fun OctopusTheme(
    content: @Composable () -> Unit
) {
    // 系统暗色模式
    val systemDark = isSystemInDarkTheme()
    // 反应式真源 = OctopusColors.isLight。设置页切换主题时**直接改它**;这里只在「首次组合 / 系统暗色
    // 变化」时,按存储偏好(null=跟随系统)播种一次,**不在每次重组里回写**——否则会把用户刚切到的
    // 深色又冲回去(这正是「深色不生效」的元凶:原来用 remember 记了旧值 + SideEffect 每次回写)。
    LaunchedEffect(systemDark) {
        OctopusColors.isLight = com.apk.claw.android.utils.KVUtils.getThemeMode() ?: !systemDark
    }
    val light = OctopusColors.isLight

    val colorScheme = if (light) lightColorScheme(
        primary = OctopusColors.Primary,
        onPrimary = OctopusColors.OnPrimary,
        primaryContainer = OctopusColors.PrimaryContainer,
        onPrimaryContainer = OctopusColors.OnPrimaryContainer,
        inversePrimary = OctopusColors.PrimaryVariant,
        secondary = OctopusColors.Accent,
        onSecondary = OctopusColors.OnPrimary,
        // Tonal buttons, selected filter chips: a neutral step up, not a core tint (core marks, it does not fill).
        secondaryContainer = OctopusColors.ContainerHigh,
        onSecondaryContainer = OctopusColors.TextPrimary,
        tertiary = OctopusColors.Warning,
        onTertiary = OctopusColors.OnPrimary,
        tertiaryContainer = OctopusColors.WarningContainer,
        onTertiaryContainer = OctopusColors.WarningOnContainer,
        background = OctopusColors.Background,
        onBackground = OctopusColors.TextPrimary,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        // M3 container roles (menus, dialogs, sheets, switch tracks). Unset, they fall back to the
        // purple-tinted Material baseline (#211F26 menus, #E6E0E9 tracks) instead of Snow / Graphite.
        surfaceContainerLowest = OctopusColors.ContainerLow,
        surfaceContainerLow = OctopusColors.ContainerLow,
        surfaceContainer = OctopusColors.ContainerBrighten,
        surfaceContainerHigh = OctopusColors.ContainerBrighten,
        surfaceContainerHighest = OctopusColors.ContainerHigh,
        surfaceBright = OctopusColors.ContainerBrighten,
        surfaceDim = OctopusColors.ContainerBase,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextSecondary,
        error = OctopusColors.Error,
        onError = OctopusColors.OnPrimary,
        onErrorContainer = OctopusColors.ErrorOnContainer,
        errorContainer = OctopusColors.ErrorContainer,
        outline = OctopusColors.Border,
        outlineVariant = OctopusColors.BorderLow,
        inverseSurface = OctopusColors.ContainerInverse,
        inverseOnSurface = OctopusColors.TextInverse,
        scrim = OctopusColors.OverlayDim,
    ) else darkColorScheme(
        primary = OctopusColors.Primary,
        onPrimary = OctopusColors.OnPrimary,
        primaryContainer = OctopusColors.PrimaryContainer,
        onPrimaryContainer = OctopusColors.OnPrimaryContainer,
        inversePrimary = OctopusColors.PrimaryVariant,
        secondary = OctopusColors.Accent,
        onSecondary = OctopusColors.OnPrimary,
        // Tonal buttons, selected filter chips: a neutral step up, not a core tint (core marks, it does not fill).
        secondaryContainer = OctopusColors.ContainerHigh,
        onSecondaryContainer = OctopusColors.TextPrimary,
        tertiary = OctopusColors.Warning,
        onTertiary = OctopusColors.OnPrimary,
        tertiaryContainer = OctopusColors.WarningContainer,
        onTertiaryContainer = OctopusColors.WarningOnContainer,
        background = OctopusColors.Background,
        onBackground = OctopusColors.TextPrimary,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        // M3 container roles (menus, dialogs, sheets, switch tracks). Unset, they fall back to the
        // purple-tinted Material baseline (#211F26 menus, #E6E0E9 tracks) instead of Snow / Graphite.
        surfaceContainerLowest = OctopusColors.ContainerLow,
        surfaceContainerLow = OctopusColors.ContainerLow,
        surfaceContainer = OctopusColors.ContainerBrighten,
        surfaceContainerHigh = OctopusColors.ContainerBrighten,
        surfaceContainerHighest = OctopusColors.ContainerHigh,
        surfaceBright = OctopusColors.ContainerBrighten,
        surfaceDim = OctopusColors.ContainerBase,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextSecondary,
        error = OctopusColors.Error,
        onError = OctopusColors.OnPrimary,
        onErrorContainer = OctopusColors.ErrorOnContainer,
        errorContainer = OctopusColors.ErrorContainer,
        outline = OctopusColors.Border,
        outlineVariant = OctopusColors.BorderLow,
        inverseSurface = OctopusColors.ContainerInverse,
        inverseOnSurface = OctopusColors.TextInverse,
        scrim = OctopusColors.OverlayDim,
    )

    val view = LocalView.current
    val activity = view.context as? Activity

    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = EchoShapes,
    ) {
        // 选区手柄与高亮用 core(M3 默认取 primary,在 ECHO 里是单色)
        CompositionLocalProvider(LocalTextSelectionColors provides EchoFieldColors.selection, content = content)
    }
}

/**
 * ECHO radius ladder for Material components (BRAND.md): `radius-sm` 6 for inputs and menus,
 * `radius-md` 10 for cards, `radius-lg` 14 for dialogs and bottom sheets. Without it, M3 falls back to
 * its baseline 28 dp dialogs and sheets.
 */
private val EchoShapes = Shapes(
    extraSmall = OctopusShape.small,
    small = OctopusShape.small,
    medium = OctopusShape.large,
    large = OctopusShape.dialog,
    extraLarge = OctopusShape.dialog,
)
