package com.apk.claw.android.ui.compose.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ECHO input fields. Material 3 draws focus, cursor and label with `colorScheme.primary`, which in ECHO
 * is the monochrome ink/snow of primary buttons, so a focused field got a heavy snow outline. Focus is
 * `core` in the brand (`--echo-focus`), and the resting outline is `line-strong`.
 */
object EchoFieldColors {
    private const val SELECTION_ALPHA = 0.32f

    @Composable
    fun outlined(container: Color = Color.Transparent): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = OctopusColors.Accent,
        unfocusedBorderColor = OctopusColors.BorderHigh,
        cursorColor = OctopusColors.Accent,
        focusedLabelColor = OctopusColors.Accent,
        focusedContainerColor = container,
        unfocusedContainerColor = container,
        selectionColors = selection,
    )

    /** Text selection handles and highlight, for every text field and SelectionContainer. */
    val selection: TextSelectionColors
        get() = TextSelectionColors(
            handleColor = OctopusColors.Accent,
            backgroundColor = OctopusColors.Accent.copy(alpha = SELECTION_ALPHA),
        )
}
