package com.apk.claw.android.ui.compose.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.persona.Persona

/** An emoji avatar fills about three quarters of its tile. */
private const val EMOJI_SCALE = 0.72f

/**
 * A persona's avatar. The built-in Echo persona has no emoji avatar and shows the ECHO mark on its
 * graphite tile, the same way the chat header does; a user persona shows the emoji the user picked.
 */
@Composable
fun PersonaAvatar(persona: Persona, size: Dp, modifier: Modifier = Modifier) {
    if (persona.avatar.isBlank()) {
        Image(
            painterResource(R.drawable.ic_echo_foreground),
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(colorResource(R.color.echo_icon_background)),
        )
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            val fontSize = with(LocalDensity.current) { (size * EMOJI_SCALE).toSp() }
            Text(persona.avatar, fontSize = fontSize)
        }
    }
}
