package com.apk.claw.android.server.routes

import com.apk.claw.android.BuildConfig
import com.apk.claw.android.server.RemoteControlIndicator
import com.apk.claw.android.server.ScreenCaptureManager
import com.apk.claw.android.server.StreamTicketStore
import com.apk.claw.android.service.ClawAccessibilityService
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Semaphore

private const val MIME_JSON = "application/json"

class ScreenHandler(
    private val streamTickets: StreamTicketStore = StreamTicketStore(),
) : RouteHandler {

    private val screenCaptureManager = ScreenCaptureManager()
    private val mjpegStreamLock = Semaphore(2) // 限制 2 路并发 MJPEG

    private companion object {
        const val PIPE_BUFFER_BYTES = 512 * 1024   // 管道缓冲:容纳数帧,吸收客户端短暂抖动
        const val STALL_TIMEOUT_MS = 10_000L       // 超过此时长无写进展 → 判客户端停读,回收该路
        const val WATCHDOG_TICK_MS = 2_000L        // 看门狗巡检间隔
    }

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        return when (method) {
            NanoHTTPD.Method.POST -> uri == "/api/screen/stream-ticket"
            NanoHTTPD.Method.GET -> when (uri) {
                "/api/screen/screenshot",
                "/api/screen/stream",
                "/api/screen/info",
                "/api/screen/tree" -> true
                "/api/debug/screen-full" -> BuildConfig.DEBUG
                else -> false
            }
            else -> false
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when (session.uri) {
            "/api/screen/stream-ticket" -> handleStreamTicket(ctx)
            "/api/screen/screenshot" -> handleScreenshot(session, ctx)
            "/api/screen/stream" -> handleScreenStream(session, ctx)
            "/api/screen/info" -> handleScreenInfo(ctx)
            "/api/screen/tree" -> handleScreenTree(session, ctx)
            "/api/debug/screen-full" -> handleGetScreenFull(ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    /**
     * POST /api/screen/stream-ticket（需 Bearer 头，由 ConfigServer 的访问闸门保证）。
     * 返回 30s 内有效、只能使用一次的不透明票据，供 `/api/screen/stream?ticket=` 使用。
     */
    private fun handleStreamTicket(ctx: RouteContext): NanoHTTPD.Response {
        val ticket = streamTickets.issue(System.currentTimeMillis())
        val json = ctx.gson.toJson(
            mapOf(
                "code" to 0,
                "data" to mapOf("ticket" to ticket, "expiresInMs" to StreamTicketStore.DEFAULT_TTL_MS),
            )
        )
        val response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json)
        response.addHeader("Cache-Control", "no-store")
        return ctx.corsResponse(response)
    }

    /**
     * GET /api/screen/screenshot?quality=60&maxWidth=720
     * 单张 JPEG 截图。
     */
    private fun handleScreenshot(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val quality = session.parms["quality"]?.toIntOrNull() ?: 60
        val maxWidth = session.parms["maxWidth"]?.toIntOrNull() ?: 720

        val jpeg = if (maxWidth > 0) {
            screenCaptureManager.captureScaledJpeg(maxWidth, quality)
        } else {
            screenCaptureManager.captureJpeg(quality)
        }

        if (jpeg == null) {
            ctx.recordRemoteAccess(session, "screen_screenshot", false, "quality=$quality,maxWidth=$maxWidth", startMs)
            return ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"code":-1,"message":"当前无法获取截图"}"""
                )
            )
        }

        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "image/jpeg",
            java.io.ByteArrayInputStream(jpeg), jpeg.size.toLong()
        )
        response.addHeader("Cache-Control", "no-cache, no-store")
        ctx.recordRemoteAccess(session, "screen_screenshot", true, "quality=$quality,maxWidth=$maxWidth,bytes=${jpeg.size}", startMs)
        return ctx.corsResponse(response)
    }

    /**
     * GET /api/screen/stream?quality=65&maxWidth=900&fps=20
     * MJPEG 实时流（限制 2 路并发）。
     */
    private fun handleScreenStream(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        if (!mjpegStreamLock.tryAcquire()) {
            ctx.recordRemoteAccess(session, "screen_stream", false, "too_many_streams", startMs)
            return ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, MIME_JSON,
                    """{"code":-1,"message":"屏幕流连接过多,最多支持 2 路"}"""
                )
            )
        }

        val quality = session.parms["quality"]?.toIntOrNull() ?: 65
        val maxWidth = session.parms["maxWidth"]?.toIntOrNull() ?: 900
        // fps 上限 30,默认 20; 节流由 ScreenCaptureManager.THROTTLE_MS=33 兜底
        val fps = session.parms["fps"]?.toIntOrNull()?.coerceIn(1, 30) ?: 20
        val frameIntervalMs = (1000L / fps)

        val boundary = "octopus_mjpeg_boundary"
        val contentType = "multipart/x-mixed-replace; boundary=$boundary"

        val pipe = PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOut = PipedOutputStream(pipe)

        // 后台线程写帧
        val viewerSource = ctx.sourceOf(session)
        RemoteControlIndicator.onViewerStarted(viewerSource)
        // 最近一次成功写帧的时刻;看门狗据此判断客户端是否停读(写阻塞)。
        val lastProgressMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val writer = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val jpeg = screenCaptureManager.captureScaledJpeg(maxWidth, quality)
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        pipeOut.write("--$boundary\r\n".toByteArray())
                        pipeOut.write("Content-Type: image/jpeg\r\n".toByteArray())
                        pipeOut.write("Content-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                        pipeOut.write(jpeg)
                        pipeOut.write("\r\n".toByteArray())
                        pipeOut.flush()
                        lastProgressMs.set(System.currentTimeMillis())
                    }
                    Thread.sleep(frameIntervalMs)
                }
            } catch (_: java.io.IOException) {
                // 客户端断开 / 被看门狗中断(InterruptedIOException)
            } catch (_: InterruptedException) {
                // 正常停止
            } finally {
                try { pipeOut.close() } catch (_: Exception) {}
                mjpegStreamLock.release()
                RemoteControlIndicator.onViewerEnded(viewerSource)
            }
        }, "MJPEG-Stream").apply { isDaemon = true }

        // 卡死看门狗:客户端停读会让 PipedOutputStream.write 永久阻塞,写线程占住信号量许可
        // 不释放 → 2 路占满后所有后续流恒 429。超过 STALL_TIMEOUT 无写进展则中断写线程
        // (其阻塞在 Object.wait 上,interrupt 会抛 InterruptedIOException 解除)并关流。
        val watchdog = Thread({
            try {
                while (writer.isAlive) {
                    Thread.sleep(WATCHDOG_TICK_MS)
                    if (System.currentTimeMillis() - lastProgressMs.get() > STALL_TIMEOUT_MS) {
                        writer.interrupt()
                        try { pipe.close() } catch (_: Exception) {}
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // 写线程已结束
            }
        }, "MJPEG-Watchdog").apply { isDaemon = true }

        writer.start()
        watchdog.start()

        val response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, contentType, pipe, Long.MAX_VALUE)
        response.addHeader("Cache-Control", "no-cache, no-store")
        ctx.recordRemoteAccess(session, "screen_stream", true, "quality=$quality,maxWidth=$maxWidth,fps=$fps", startMs)
        return ctx.corsResponse(response)
    }

    /**
     * GET /api/screen/info
     * 屏幕分辨率及无障碍服务状态。
     */
    private fun handleScreenInfo(ctx: RouteContext): NanoHTTPD.Response {
        val info = screenCaptureManager.getScreenInfo() ?: mapOf(
            "width" to 0,
            "height" to 0,
            "serviceRunning" to false
        )
        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to info))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/screen/tree?full=false
     * 远程读屏：返回无障碍可见的 UI 树（供远端 Agent 决策点击坐标）。
     */
    private fun handleScreenTree(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val full = session.parms["full"]?.toBoolean() ?: false
        val svc = ClawAccessibilityService.getInstance()
            ?: run {
                ctx.recordRemoteAccess(session, "screen_tree", false, "full=$full,service=unavailable", startMs)
                return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"code":-1,"message":"无障碍服务未运行"}"""
                ))
            }
        val tree = if (full) svc.screenTreeFull else svc.screenTree
        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to (tree ?: "")))
        ctx.recordRemoteAccess(session, "screen_tree", true, "full=$full,chars=${tree?.length ?: 0}", startMs)
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    private fun handleGetScreenFull(ctx: RouteContext): NanoHTTPD.Response {
        val service = ClawAccessibilityService.getInstance()
            ?: return ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"无障碍服务未运行"}"""
                )
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }
}
