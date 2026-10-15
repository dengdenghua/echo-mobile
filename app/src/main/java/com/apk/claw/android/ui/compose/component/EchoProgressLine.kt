package com.apk.claw.android.ui.compose.component

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/** The brand motion curve, `cubic-bezier(0.2, 0.8, 0.2, 1)` (BRAND.md, 材质与动效). */
val EchoEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** The line is 2 dp: thin enough to read as a hairline, thick enough to see move. */
val EchoProgressLineHeight = 2.dp

/** A short inline line where a small spinner used to sit (a row, a button). */
val EchoProgressLineInlineWidth = 24.dp

/** A line on its own in a centred layout (a call screen, a QR code slot). */
val EchoProgressLineCenteredWidth = 120.dp

private const val SEGMENT_FRACTION = 0.4f
private const val CYCLE_MS = 1600

/** The segment travels from one segment-width left of the track to its right edge: 3.5 segment-widths. */
private const val TRAVEL_IN_SEGMENTS = 3.5f

/**
 * The ECHO loading indicator (BRAND.md: no spinners; loading is one thin `core` line).
 *
 * Indeterminate: a 40%-wide [color] segment slides across a hairline [trackColor] track every 1.6 s
 * on the brand curve, the same as the web `ProgressLine`. When the user has turned animations off
 * (Accessibility > Remove animations, which sets the animator duration scale to 0) the segment stays
 * still at the start of the track.
 *
 * It fills the width it is given; pass `Modifier.width(EchoProgressLineInlineWidth)` for an inline line.
 * Screen readers hear it as an indeterminate progress bar named "Loading".
 */
@Composable
fun EchoProgressLine(
    modifier: Modifier = Modifier,
    color: Color = OctopusColors.Accent,
    trackColor: Color = OctopusColors.Border,
) {
    val label = stringResource(R.string.common_loading)
    val position by rememberSegmentPosition()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(EchoProgressLineHeight)
            .progressSemantics()
            .semantics { contentDescription = label },
    ) {
        drawRect(trackColor)
        val segment = size.width * SEGMENT_FRACTION
        val start = -segment + position * segment * TRAVEL_IN_SEGMENTS
        drawSpan(color, start, start + segment)
    }
}

/**
 * The determinate form, for work with a real percentage (a download, a page load): the [color] line
 * grows from the start of the track to [progress] (0–1).
 */
@Composable
fun EchoProgressLine(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = OctopusColors.Accent,
    trackColor: Color = OctopusColors.Border,
) {
    val value = progress().coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(EchoProgressLineHeight)
            .progressSemantics(value),
    ) {
        drawRect(trackColor)
        drawSpan(color, 0f, size.width * value)
    }
}

/**
 * The busy state inside a filled button: a short line in the button's [contentColor] on a faint track of the
 * same colour (`core` cannot be read on the monochrome primary button). The caller keeps the button's size,
 * for example by drawing its label invisibly underneath.
 */
@Composable
fun EchoButtonBusyLine(contentColor: Color, modifier: Modifier = Modifier) {
    EchoProgressLine(
        modifier.width(EchoProgressLineInlineWidth),
        color = contentColor,
        trackColor = contentColor.copy(alpha = BUSY_TRACK_ALPHA),
    )
}

private const val BUSY_TRACK_ALPHA = 0.24f

/**
 * Whole-area loading: the line along the top edge of the area, with an optional calm [caption] under it.
 * It replaces the centred spinner on pages and panels.
 */
@Composable
fun EchoLoadingPanel(
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        EchoProgressLine()
        if (caption != null) {
            Text(
                caption,
                color = OctopusColors.TextSecondary,
                fontSize = OctopusType.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
            )
        }
    }
}

/** Draws [color] between [from] and [to] (left-to-right coordinates), clipped to the track, mirrored in RTL. */
private fun DrawScope.drawSpan(color: Color, from: Float, to: Float) {
    val left = from.coerceIn(0f, size.width)
    val right = to.coerceIn(0f, size.width)
    if (right <= left) return
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - right else left
    drawRect(color, topLeft = Offset(x, 0f), size = Size(right - left, size.height))
}

/** 0 → 1 once per cycle while animations are on; a fixed start position when they are off. */
@Composable
private fun rememberSegmentPosition(): State<Float> {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    if (!animate) return remember { mutableFloatStateOf(STILL_POSITION) }
    return rememberInfiniteTransition(label = "echo_progress_line").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = EchoEasing), RepeatMode.Restart),
        label = "echo_progress_segment",
    )
}

/** With motion off the segment rests at the start of the track (x = 0), like the web line. */
private const val STILL_POSITION = 1f / TRAVEL_IN_SEGMENTS
