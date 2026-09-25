package com.apk.claw.android.ui.featurescreens

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.H264Decoder
import com.apk.claw.android.octopus_mobile.OctopusMobileClient

/**
 * 母体远程桌面 —— 手机看 PC 屏幕(H.264 硬解)、触控/键盘控制 PC（类似 ToDesk）。
 *
 * 复用现有 [OctopusMobileClient] WebSocket：
 *  - 订阅 pc_screen/subscribe，母体推 H.264(Annex-B) 帧 → [H264Decoder]/MediaCodec 解码渲染到 SurfaceView
 *  - 触摸 → 归一化坐标 → remote/input（母体还原为 PC 鼠键）
 *
 * 前提：先在 设置 → 母体连接 配好地址并连接(DUAL / RPC_ONLY)。
 */
class PcRemoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { PcRemoteScreen(onBack = { finish() }) }
    }
}

private const val RECEIVER_CHECK_MS = 1_000L
private const val RECEIVER_STALE_MS = 3_000L
private const val MAX_JPEG_BYTES = 350_000
private const val MAX_JPEG_DIMENSION = 1280

@Composable
private fun PcRemoteScreen(onBack: () -> Unit) {
    val client = appViewModel.octopusClient
    val decoder = remember { H264Decoder() }  // 分辨率从 SPS 自动解析
    var frames by remember { mutableStateOf(0) }
    var jpegFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var lastFrameAt by remember { mutableStateOf(0L) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var receiverActive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(RECEIVER_CHECK_MS)
            if (lastFrameAt > 0 && android.os.SystemClock.elapsedRealtime() - lastFrameAt > RECEIVER_STALE_MS) {
                jpegFrame = null
                frames = 0
            }
        }
    }
    var showKeyboard by remember { mutableStateOf(false) }
    val controlClient by rememberUpdatedState(if (jpegFrame == null && frames > 0) client else null)
    val connected = client?.currentState() == ConnectionState.ONLINE ||
        client?.currentState() == ConnectionState.HELLO_SENT

    val receiverLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(client, receiverLifecycle) {
        if (client != null) {
            receiverActive = true
            client.onPcFrame = { bytes ->
                if (bytes.size >= 5) {
                    val idLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    val type = bytes[2].toInt() and 0xFF
                    val isKey = (bytes[3].toInt() and 0x01) != 0
                    val start = 4 + idLen
                    if (type == 0x01 && start < bytes.size) {
                        decoder.feed(bytes.copyOfRange(start, bytes.size), isKey)
                        mainHandler.post {
                            if (receiverActive) {
                                frames++
                                lastFrameAt = android.os.SystemClock.elapsedRealtime()
                                jpegFrame = null
                            }
                        }
                    } else if (type == 0x02 && start < bytes.size && bytes.size - start <= MAX_JPEG_BYTES) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, start, bytes.size - start, options)
                        if (options.outWidth in 1..MAX_JPEG_DIMENSION && options.outHeight in 1..MAX_JPEG_DIMENSION) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, start, bytes.size - start)
                            if (bitmap != null) mainHandler.post {
                                if (receiverActive) {
                                    jpegFrame = bitmap.asImageBitmap()
                                    frames++
                                    lastFrameAt = android.os.SystemClock.elapsedRealtime()
                                }
                            }
                        }
                    }
                }
            }
        }
        val frameCallback = client?.onPcFrame
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                receiverActive = true
                client?.onPcFrame = frameCallback
                client?.subscribePcScreen()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                receiverActive = false
                client?.onPcFrame = null
                client?.unsubscribePcScreen()
                mainHandler.removeCallbacksAndMessages(null)
                jpegFrame = null
                frames = 0
            }
        }
        receiverLifecycle.addObserver(observer)
        onDispose {
            receiverLifecycle.removeObserver(observer)
            receiverActive = false
            mainHandler.removeCallbacksAndMessages(null)
            client?.unsubscribePcScreen()
            client?.onPcFrame = null
            decoder.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 视频画面：16:9 居中,SurfaceView 即视频矩形 → 触摸坐标直接按本视图尺寸归一化
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { decoder.start(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { decoder.stop() }
                    })
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o -> sendN(controlClient, size, o.x, o.y, "tap") },
                        onLongPress = { o -> sendN(controlClient, size, o.x, o.y, "rightclick") },
                    )
                }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        var twoFinger = false
                        var dragEmitted = false
                        var centroidInit = false
                        var lastCentroidY = 0f
                        var lastPos = first.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                twoFinger = true
                                val cy = pressed.map { it.position.y }.average().toFloat()
                                if (!centroidInit) { lastCentroidY = cy; centroidInit = true }
                                val dy = cy - lastCentroidY
                                lastCentroidY = cy
                                if (kotlin.math.abs(dy) > 1f && size.height > 0) {
                                    controlClient?.sendRemoteInput("scroll", 0f, dy / size.height)
                                }
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 1 && !twoFinger) {
                                val p = pressed[0]
                                if (dragEmitted || (p.position - first.position).getDistance() > slop) {
                                    if (!dragEmitted) {
                                        sendN(
                                            controlClient, size, first.position.x, first.position.y,
                                            "down", clamp = true,
                                        )
                                        dragEmitted = true
                                    }
                                    sendN(controlClient, size, p.position.x, p.position.y, "move", clamp = true)
                                    p.consume()
                                    lastPos = p.position
                                }
                            }
                        }
                        if (dragEmitted) {
                            sendN(controlClient, size, lastPos.x, lastPos.y, "up", clamp = true)
                        }
                    }
                },
        )

        jpegFrame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = "电脑共享画面",
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // Browser-selected windows are view-only; never send their coordinates to the server host.
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }, contentScale = ContentScale.Fit)
        }

        if (frames == 0) {
            Text(
                if (connected) stringResource(R.string.pcremote_waiting_screen)
                else stringResource(R.string.pcremote_not_connected),
                color = Color.White, fontSize = 14.sp,
            )
        }

        // 顶栏
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.pcremote_back_button), color = Color.White, fontSize = 14.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { onBack() } })
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Filled.Computer, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.settings_pc_remote_desktop_title), color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(if (frames > 0) stringResource(R.string.pcremote_frame_count, frames) else if (connected) stringResource(R.string.pcremote_status_connected) else stringResource(R.string.pcremote_status_disconnected), color = Color(0xFF8AB4F8), fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            Text("⌫", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures { controlClient?.sendRemoteInput("key", text = "backspace") }
                },
            )
            Spacer(Modifier.width(12.dp))
            Text("Esc", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures { controlClient?.sendRemoteInput("key", text = "esc") }
                },
            )
            Spacer(Modifier.width(12.dp))
            Text("⌨", color = Color.White, fontSize = 18.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { showKeyboard = true } })
        }
    }

    if (showKeyboard) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyboard = false },
            title = { Text(stringResource(R.string.pcremote_input_dialog_title)) },
            text = {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFEEEEEE)).padding(10.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotEmpty()) controlClient?.sendRemoteInput("type", text = input)
                    showKeyboard = false
                }) { Text(stringResource(R.string.screen_cast_send_button)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    controlClient?.sendRemoteInput("key", text = "enter")
                    showKeyboard = false
                }) { Text(stringResource(R.string.pcremote_enter_button)) }
            },
        )
    }
}

/** 触摸点按视图尺寸直接归一化并发送（视图即 16:9 视频矩形）。clamp=拖拽用,出界也发以免按住不放。 */
private fun sendN(client: OctopusMobileClient?, size: IntSize, x: Float, y: Float, action: String, clamp: Boolean = false) {
    if (client == null || size.width == 0 || size.height == 0) return
    var nx = x / size.width
    var ny = y / size.height
    if (clamp) { nx = nx.coerceIn(0f, 1f); ny = ny.coerceIn(0f, 1f) }
    else if (nx !in 0f..1f || ny !in 0f..1f) return
    client.sendRemoteInput(action, nx, ny)
}
