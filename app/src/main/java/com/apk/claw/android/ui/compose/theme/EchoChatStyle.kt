package com.apk.claw.android.ui.compose.theme

import androidx.compose.ui.graphics.Color

/**
 * Echo conversation and Dock surfaces.
 *
 * Every role is an ECHO token from [OctopusColors] (graphite / snow, with `core` cyan as the only accent),
 * so the chat, the Dock and the Dot sheets follow the same palette and theme switch as the rest of the app.
 * Add new roles here as aliases of existing tokens rather than as new hex values.
 */
object EchoChatStyle {
    /** Page ground. */
    val background: Color get() = OctopusColors.Background

    /** Raised surface: composer, cards, header capsule. */
    val surface: Color get() = OctopusColors.Surface

    /** Quiet fill: agent bubbles, stat chips, secondary rows. */
    val softSurface: Color get() = OctopusColors.ContainerBase

    /** `core`: the one accent, for small marks only (an icon, a selected dot), never for large fills. */
    val accent: Color get() = OctopusColors.Accent

    /** The user's own messages and highlighted panels: one step above [softSurface], still neutral. */
    val accentSurface: Color get() = OctopusColors.ContainerHigh
    val onAccentSurface: Color get() = OctopusColors.TextPrimary

    val text: Color get() = OctopusColors.TextPrimary
    val muted: Color get() = OctopusColors.TextSecondary

    /**
     * `line-strong` flattened onto the ground. It is opaque on purpose: callers soften it with
     * `.copy(alpha = …)`, which would replace (not multiply) the alpha of a translucent line token.
     */
    val border: Color get() = OctopusColors.ContainerHighest
}
