package com.apk.claw.android.ui.featurescreens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusThemeStyle
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusTheme

val FBg get() = OctopusColors.Background
val FSurface get() = OctopusColors.Surface
val FSurface2 get() = OctopusColors.SurfaceVariant
val FPrimary get() = OctopusColors.Primary

/** Thumb/glyph colour on a filled Primary, Success (core) or Warning surface: legible in Snow and Graphite. */
val FOnPrimary get() = OctopusColors.OnPrimary
val FText get() = OctopusColors.TextPrimary
val FSub get() = OctopusColors.TextSecondary
val FMuted get() = OctopusColors.TextMuted
val FBorder get() = OctopusColors.Border
val FSuccess get() = OctopusColors.Success
val FWarning get() = OctopusColors.Warning

/** Breach red: errors, failures, deletion and security events only. */
val FError get() = OctopusColors.Error

/**
 * FeatureScreen Activity chrome shared by the secondary Compose pages.
 */
@Suppress("DEPRECATION")
fun ComponentActivity.applyFeatureChrome() {
    runCatching {
        window.statusBarColor = OctopusColors.statusBarArgb
        window.navigationBarColor = OctopusColors.statusBarArgb
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = OctopusColors.isLight
            isAppearanceLightNavigationBars = OctopusColors.isLight
        }
    }
}

fun ComponentActivity.setFeatureContent(content: @Composable () -> Unit) {
    applyFeatureChrome()
    setContent {
        OctopusTheme {
            content()
        }
    }
}

/** 通用深色页脚手架：顶栏（返回 + 标题 + 可选右侧动作）+ 内容区 */
@Composable
fun FeatureScaffold(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(OctopusBackground.pageBrush()).statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(62.dp).padding(start = 2.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_close), tint = FText)
            }
            Text(title, color = FText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (action != null) action()
        }
        content()
    }
}

@Composable
fun FSectionTitle(text: String) {
    Text(
        text,
        color = FSub,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun FCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = OctopusShape.large,
        color = OctopusBackground.cardSurface,
        border = BorderStroke(1.dp, OctopusBackground.cardBorder),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun FEmpty(text: String) {
    Text(
        text,
        color = FMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
    )
}

@Composable
fun FPill(text: String, color: Color) {
    Surface(shape = OctopusShape.capsule, color = color.copy(alpha = 0.15f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * A caution line: an outlined warning icon plus text, both in [color]. Replaces the "⚠️ …" emoji prefix,
 * so the state is carried by an icon and words, not by an emoji.
 */
@Composable
fun FWarningNote(text: String, modifier: Modifier = Modifier, color: Color = FWarning) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.WarningAmber, contentDescription = null, tint = color,
            modifier = Modifier.padding(top = 1.dp).size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Trailing "opens a page" chevron for navigation rows. */
@Composable
fun FChevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = FMuted,
        modifier = Modifier.padding(start = 4.dp).size(20.dp),
    )
}

/** A small outlined icon in front of a label, for metric and value rows. */
@Composable
fun FIconLabel(icon: ImageVector, text: String, color: Color = FText, fontSize: Int = 13, bold: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = FSub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
