package com.apk.claw.android.ui.compose.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.EchoProgressLineCenteredWidth
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.EchoSheetSystemBars
import com.apk.claw.android.ui.compose.theme.EchoChatStyle as Style
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The remote screen viewport stays graphite in both themes, like a video frame (ECHO `ground` graphite). */
private val ScreenGround = Color(0xFF101216)

/** The hairline track under the loading line on the always-dark frame. */
private const val FRAME_TRACK_ALPHA = 0.16f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DotScreenSheet(member: DotMember, initialEnvironment: DotEnvironment,
    onDismiss: () -> Unit, onChangeRole: () -> Unit) {
    var environment by remember { mutableStateOf(initialEnvironment) }
    var automatic by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var configure by remember { mutableStateOf(false) }
    var frame by remember(environment) { mutableStateOf<Bitmap?>(null) }
    var capturedAt by remember(environment) { mutableLongStateOf(0L) }
    var issue by remember(environment) { mutableStateOf<Int?>(null) }
    var fetching by remember(environment) { mutableStateOf(false) }
    var lastRefresh by remember(environment) { mutableIntStateOf(-1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(environment, automatic, refresh, foreground, configure) {
        if (!foreground || configure || (!automatic && lastRefresh == refresh)) return@LaunchedEffect
        if (!DotRepository.validBaseUrl(DotEnvironmentStore.address(environment))) {
            issue = R.string.dot_screen_unconfigured
            return@LaunchedEffect
        }
        do {
            lastRefresh = refresh
            fetching = true
            try {
                frame = DotScreenRepository.frame(environment)
                capturedAt = System.currentTimeMillis()
                issue = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                issue = when ((e as? DotScreenRepository.ScreenException)?.code) {
                    401, 403 -> R.string.dot_auth_needed
                    404 -> R.string.dot_screen_update_required
                    503 -> R.string.dot_screen_unavailable
                    else -> R.string.dot_screen_offline
                }
            } finally { fetching = false }
            if (!automatic || issue in listOf(R.string.dot_screen_update_required, R.string.dot_auth_needed,
                    R.string.dot_screen_unavailable)) break
            delay(if (issue == null) 3000 else 8000)
        } while (true)
    }
    @Composable
    fun content(full: Boolean) {
        EchoSheetSystemBars()
        Column(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()
            else Modifier.heightIn(max = 680.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.name, color = Style.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) {
                        Icon(environment.icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(environment.tabLabel))
                        Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        listOf(DotEnvironment.HOME, DotEnvironment.COMPUTER).forEach { target ->
                            DropdownMenuItem(text = { Text(stringResource(target.title)) },
                                leadingIcon = { Icon(target.icon, null) }, onClick = { menu = false; environment = target })
                        }
                    }
                }
                IconButton(onClick = { if (full) fullscreen = false else onDismiss() }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = Style.muted)
                }
            }
            Text(stringResource(R.string.dot_screen_observe_only), color = Style.muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().then(if (full) Modifier.weight(1f) else Modifier.height(340.dp))
                .clip(OctopusShape.large).background(ScreenGround), contentAlignment = Alignment.Center) {
                val image = frame
                if (image != null) {
                    var zoom by remember(environment, full) { mutableFloatStateOf(1f) }
                    var pan by remember(environment, full) { mutableStateOf(Offset.Zero) }
                    var viewport by remember { mutableStateOf(IntSize.Zero) }
                    val transform = rememberTransformableState { scale, offset, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 4f)
                        val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
                        val maxX = ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
                        val maxY = ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
                        pan = Offset((pan.x + offset.x).coerceIn(-maxX, maxX), (pan.y + offset.y).coerceIn(-maxY, maxY))
                    }
                    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.clipToBounds().then(if (full) Modifier.transformable(transform)
                        else Modifier.clickable { fullscreen = true }), contentAlignment = Alignment.Center) {
                        Image(image.asImageBitmap(), stringResource(R.string.dot_screen_frame),
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom,
                                translationX = pan.x, translationY = pan.y), contentScale = ContentScale.Fit)
                    }
                } else if (fetching) EchoProgressLine(Modifier.width(EchoProgressLineCenteredWidth),
                    trackColor = OctopusColors.TextFixedWhitePrimary.copy(alpha = FRAME_TRACK_ALPHA))
                else Text(stringResource(issue ?: R.string.dot_screen_loading),
                    color = OctopusColors.TextFixedWhitePrimary,
                    fontSize = 13.sp, modifier = Modifier.padding(20.dp))
            }
            issue?.let { Text(stringResource(it), color = Style.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp)) }
            if (capturedAt != 0L) Text(stringResource(R.string.dot_screen_last_frame,
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(capturedAt))),
                color = Style.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { automatic = !automatic }) {
                    Icon(if (automatic) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (automatic) R.string.dot_screen_pause else R.string.dot_screen_resume), tint = Style.muted)
                }
                IconButton(onClick = { refresh++ }, enabled = !fetching) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.browser_refresh_page), tint = Style.muted)
                }
                if (!full) IconButton(onClick = { fullscreen = true }) {
                    Icon(Icons.Filled.Fullscreen, stringResource(R.string.dot_screen_fullscreen), tint = Style.muted)
                }
                IconButton(onClick = { configure = true }) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.dot_env_configure), tint = Style.muted)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onChangeRole) { Text(stringResource(R.string.dot_screen_switch_role)) }
            }
        }
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Style.background) { content(true) }
    } else ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Style.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { content(false) }
    if (configure) DotConnectionDialog(environment, onDismiss = { configure = false },
        onSaved = { configure = false; refresh++ })
}
