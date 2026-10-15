package com.apk.claw.android.ui.compose.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.persona.Persona
import com.apk.claw.android.octopus_mobile.persona.PersonaStore

/** A monogram letter fills about 42% of its tile; a line icon about 55%. */
private const val MONOGRAM_SCALE = 0.42f
private const val ICON_SCALE = 0.55f

/**
 * A persona's avatar, drawn the ECHO way (BRAND.md: emoji are never icons). Every persona sits on the same
 * graphite tile as the app icon, with an ink glyph:
 * - Echo, the app's own persona, wears the ECHO mark (as in the chat header);
 * - the other built-in personas wear a line icon;
 * - a user persona wears a monogram: the letter the user picked, or the first letter of its name.
 */
@Composable
fun PersonaAvatar(persona: Persona, size: Dp, modifier: Modifier = Modifier) {
    val tile = modifier.size(size).clip(CircleShape).background(colorResource(R.color.echo_icon_background))
    if (persona.id == PersonaStore.ECHO_ID && persona.avatar.isBlank()) {
        Image(painterResource(R.drawable.ic_echo_foreground), contentDescription = null, modifier = tile)
        return
    }
    val ink = colorResource(R.color.echo_ink_graphite)
    Box(tile, contentAlignment = Alignment.Center) {
        val icon = builtinIcon(persona)
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * ICON_SCALE))
        } else {
            val fontSize = with(LocalDensity.current) { (size * MONOGRAM_SCALE).toSp() }
            Text(persona.monogram(), color = ink, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Line icons for the untouched built-in personas; a user-picked letter wins over the icon. */
private fun builtinIcon(persona: Persona): ImageVector? = when {
    !persona.isBuiltin || persona.avatar.isNotBlank() -> null
    persona.id == PersonaStore.TSUNDERE_ID -> Icons.Outlined.SentimentSatisfied
    persona.id == PersonaStore.PRO_ID -> Icons.Outlined.WorkOutline
    else -> null
}
