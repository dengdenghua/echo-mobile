package com.apk.claw.android.ui.compose.theme

import androidx.compose.ui.graphics.Color

/** Echo conversation and Dock surfaces, with matching light and dark palettes. */
object EchoChatStyle {
    val background get() = if (OctopusColors.isLight) Color(0xFFF6F7FB) else Color(0xFF11131B)
    val surface get() = if (OctopusColors.isLight) Color.White else Color(0xFF20232F)
    val softSurface get() = if (OctopusColors.isLight) Color(0xFFEEF0F6) else Color(0xFF292D3A)
    val accent get() = if (OctopusColors.isLight) Color(0xFF5965C7) else Color(0xFFADB6FF)
    val accentSurface get() = if (OctopusColors.isLight) Color(0xFFE8ECFF) else Color(0xFF323B62)
    val onAccentSurface get() = if (OctopusColors.isLight) Color(0xFF303D7A) else Color(0xFFE4E8FF)
    val text get() = if (OctopusColors.isLight) Color(0xFF242938) else Color(0xFFF1F3FA)
    val muted get() = if (OctopusColors.isLight) Color(0xFF697286) else Color(0xFFA4AEC3)
    val border get() = if (OctopusColors.isLight) Color(0xFFE6E9F1) else Color(0xFF363B4C)
}
