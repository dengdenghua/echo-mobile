package com.apk.claw.android.ui.desktop

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * TV 焦点高亮修饰器(spring 物理动画版)。
 * 聚焦时平滑放大 1.1x + 高亮边框 + 投影。
 */
@Composable
fun Modifier.tvFocusHolo(
    focusedColor: Color = Holo.Accent,
    cornerRadius: Int = 16,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "focusScale"
    )

    val elevation by animateFloatAsState(
        targetValue = if (isFocused) 16f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "focusElevation"
    )

    return this
        .scale(scale)
        .shadow(elevation.dp)
        .then(
            if (isFocused) {
                Modifier.border(2.dp, focusedColor, RoundedCornerShape(cornerRadius.dp))
            } else {
                Modifier
            }
        )
        .focusable(interactionSource = interactionSource)
}

/**
 * 焦点记忆:记住某行某列的焦点位置,跨页面回来恢复。
 * 用 key 标识一个焦点位置,存储 focused 状态。
 */
@Composable
fun rememberTvFocusPosition(rowKey: String): TvFocusPosition {
    val position = remember { TvFocusPosition() }
    return position
}

class TvFocusPosition {
    var focusedIndex: Int = 0
}

/**
 * 在 Composable 进入 composition 时恢复焦点。
 */
@Composable
fun Modifier.restoreFocusOnEnter(
    focusRequester: FocusRequester,
    shouldRestore: Boolean,
): Modifier {
    LaunchedEffect(shouldRestore) {
        if (shouldRestore) {
            focusRequester.requestFocus()
        }
    }
    return this
}
