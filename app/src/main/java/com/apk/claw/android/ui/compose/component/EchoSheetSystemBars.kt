package com.apk.claw.android.ui.compose.component

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.apk.claw.android.ui.compose.theme.OctopusColors

/**
 * Call this first inside every `ModalBottomSheet { … }` content.
 *
 * The M3 1.3 sheet runs in its own edge-to-edge dialog window whose navigation bar is transparent, and two of
 * that window's defaults are wrong for ECHO:
 * - With 3-button navigation, Android keeps the navigation-bar contrast scrim on a transparent bar. In Snow it
 *   paints a white strip across the bottom of a sheet drawn in the page colour. Turning the contrast scrim off
 *   lets the sheet's own colour run behind the buttons, as it already does with gesture navigation.
 * - The sheet sets light or dark bar icons from the *system* theme. When the user has picked Snow or Graphite in
 *   Settings against the system theme, the navigation buttons came out white on Snow (or black on Graphite).
 *   Here they follow the app theme instead.
 */
@Composable
fun EchoSheetSystemBars() {
    val view = LocalView.current
    val light = OctopusColors.isLight
    SideEffect {
        val window = view.dialogWindow() ?: return@SideEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = light
            isAppearanceLightStatusBars = light
        }
    }
}

/**
 * Lets a modal drawer and its scrim reach under the navigation bar while the drawer is out.
 *
 * With 3-button navigation the activity window (edge to edge) paints its navigation bar in the opaque page
 * colour, on top of everything the app draws. The drawer scrim stopped at the bar, so Snow kept a light,
 * undimmed band under an open drawer. While the drawer is open or moving, the bar is transparent with no
 * contrast scrim, so the drawer sheet and the dimmed page show through behind the buttons; once it is closed,
 * the bar goes back to the page colour.
 */
@Composable
fun EchoDrawerNavigationBar(drawerState: DrawerState) {
    val view = LocalView.current
    val ground = OctopusColors.Background
    val offset = drawerState.currentOffset
    var closedOffset by remember { mutableFloatStateOf(0f) }
    if (!offset.isNaN() && offset < closedOffset) closedOffset = offset
    val out = !offset.isNaN() && closedOffset < 0f && offset > closedOffset
    SideEffect {
        val window = view.activityWindow() ?: return@SideEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = !out
        window.navigationBarColor = if (out) Color.Transparent.toArgb() else ground.toArgb()
    }
}

private fun View.activityWindow(): Window? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}

/** The dialog window that hosts this view: the nearest [DialogWindowProvider] up the view tree. */
private fun View.dialogWindow(): Window? {
    var node: Any? = this
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = (node as? View)?.parent
    }
    return null
}
