package com.apk.claw.android.media

import android.content.Context
import android.view.SurfaceView
import com.apk.claw.android.utils.XLog

/**
 * mpv 播放控制器 —— STUB 版本.
 *
 * 原本依赖 `is.xyz.mpv.MPVLib`(老 mpv-android JNI 接口),但项目使用的
 * `io.github.abdallahmehiz:mpv-android-lib:0.1.12` 已经把类重命名为 `MPV`,
 * API 也从静态包装改成了 instance + Kotlin Flow 封装(详见
 * https://central.sonatype.com/artifact/io.github.abdallahmehiz/mpv-android-lib).
 *
 * 这个 stub 保留所有 PlayerActivity 调用的 API 签名,但所有方法都是 noop —
 * 调用时只 log warning。这样 build 能跑通,PlayerActivity 不会编译错。
 *
 * TODO: 按 abdallahmehiz/mpvKt 源码重写,使用 `is.xyz.mpv.MPV` instance
 * 形式 (val mpv = MPV(); mpv.create(); mpv.observeProperty(...) etc.).
 *
 * ⚠️ 已废弃（PROJECT_ANALYSIS P2 死代码清理,2026-07）：
 *     `IS_AVAILABLE = false`,所有方法均为 noop。MediaTools 与 PlayerActivity 仍引用
 *     本对象以保持编译,但视频播放功能从未真正接通。后续应整体移除或按新 API 重写。
 */
@Deprecated(
    "MpvController 是 stub,视频播放未接通(IS_AVAILABLE=false,所有方法 noop)。详见 PROJECT_ANALYSIS P2。",
    level = DeprecationLevel.WARNING,
)
object MpvController {

    private const val TAG = "MpvController"

    /**
     * 本地 mpv 播放是否可用。当前为 stub(mpv-android-lib 未按新 API 接入),
     * 所有播放方法都是 noop。工具层据此对播放类操作返回明确"不可用"而非假成功;
     * 真正接入 mpv 后改为 true。
     */
    const val IS_AVAILABLE = false

    @Volatile
    private var initialized = false

    var currentFile: String? = null
        private set

    var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onFileLoaded: (() -> Unit)? = null
    var onPlaybackEnd: (() -> Unit)? = null
    var onPositionChanged: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    fun initialize(context: Context, configDir: String? = null) {
        XLog.w(TAG, "initialize() — stub, mpv integration not yet ported to abdallahmehiz API")
        initialized = true
    }

    fun attachSurfaceView(surfaceView: SurfaceView) {
        XLog.w(TAG, "attachSurfaceView() — stub")
    }

    fun detachSurfaceView() {
        XLog.w(TAG, "detachSurfaceView() — stub")
    }

    fun play(path: String, subtitlePath: String? = null, startPositionMs: Long = 0) {
        XLog.w(TAG, "play($path) — stub, mpv playback not implemented")
        currentFile = path
    }

    fun pause() {
        XLog.w(TAG, "pause() — stub")
    }

    fun resume() {
        XLog.w(TAG, "resume() — stub")
    }

    fun togglePause() {
        XLog.w(TAG, "togglePause() — stub")
    }

    fun stop() {
        XLog.w(TAG, "stop() — stub")
        currentFile = null
    }

    fun seekTo(positionMs: Long) {
        XLog.w(TAG, "seekTo($positionMs) — stub")
    }

    fun seekRelative(offsetMs: Long) {
        XLog.w(TAG, "seekRelative($offsetMs) — stub")
    }

    fun setVolume(volume: Int) {
        XLog.w(TAG, "setVolume($volume) — stub")
    }

    fun getVolume(): Int = 100

    fun setSubtitleTrack(trackId: Int) {
        XLog.w(TAG, "setSubtitleTrack($trackId) — stub")
    }

    fun addSubtitle(path: String, title: String = "") {
        XLog.w(TAG, "addSubtitle($path) — stub")
    }

    fun setSubtitleSize(size: Int) {
        XLog.w(TAG, "setSubtitleSize($size) — stub")
    }

    fun setAudioTrack(trackId: Int) {
        XLog.w(TAG, "setAudioTrack($trackId) — stub")
    }

    fun isPlaying(): Boolean = false

    fun getPositionMs(): Long = 0

    fun getDurationMs(): Long = 0

    /**
     * Returns a human-readable snapshot of player state. Stub returns
     * a fixed message; real implementation would query mpv properties.
     */
    fun getPlaybackInfo(): Map<String, Any?> = mapOf(
        "playing" to false,
        "file" to currentFile,
        "position_ms" to 0L,
        "duration_ms" to 0L,
        "stub" to true,
    )

    fun release() {
        XLog.w(TAG, "release() — stub")
        initialized = false
    }
}
