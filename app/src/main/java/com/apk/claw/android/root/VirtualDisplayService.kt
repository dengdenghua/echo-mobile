package com.apk.claw.android.root

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟显示服务 —— 创建软件虚拟 Display,让 Agent 能在后台并发操作 UI。
 *
 * 对标 Operit 的 Shower 系统。核心能力:
 *  - 创建虚拟 Display(Root 权限下可创建隐藏虚拟屏,不在物理屏显示)
 *  - 截图虚拟屏(ImageReader 读取 Surface 帧数据)
 *  - 向虚拟屏注入触控事件(Root 下通过 input 命令或 InputManager 注入)
 *
 * 使用场景:
 *  - 后台并发自动化:Agent 在虚拟屏里操作 App,物理屏继续用户交互
 *  - 多任务并行:多个虚拟屏同时跑不同 App 的自动化
 *  - 无人值守:设备锁屏后虚拟屏继续工作
 *
 * 实现原理:
 *  1. 通过 [DisplayManager.createVirtualDisplay] 创建虚拟屏(需要 CREATE_VIRTUAL_DISPLAY 权限或 Root)
 *  2. 用 [ImageReader] 绑定到虚拟屏的 Surface,读取帧数据 → JPEG
 *  3. 通过 Root `input tap` 或 InputManager 注入触控(指定 displayId)
 *
 * 安全:
 *  - 高危:创建虚拟屏 + 注入触控 → 登记为 HIGH 风险
 *  - 虚拟屏截图不经过物理屏,FLAG_SECURE 的 App 仍可被截(虚拟屏没有 secure 标记)
 *  - 需 Root 才能创建隐藏虚拟屏(无 Root 时虚拟屏会在物理屏显示 overlay)
 *
 * 局限:
 *  - Android 12+ 对虚拟屏限制更严(需 Root 才能创建完全隐藏的虚拟屏)
 *  - 触控注入需要 Root(input 命令需要 system/shell 权限)
 *  - 虚拟屏 App 可能检测到非主屏环境而拒绝运行(少数银行 App)
 */
class VirtualDisplayService(
    private val displayManager: DisplayManager,
) {

    companion object {
        private const val TAG = "VirtualDisplay"
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920
        private const val DEFAULT_DPI = 280
        private const val SCREENSHOT_QUALITY = 80
    }

    /** 活跃的虚拟屏(name → Display)。 */
    private val displays = mutableMapOf<String, DisplaySession>()

    data class DisplaySession(
        val name: String,
        val virtualDisplay: VirtualDisplay,
        val imageReader: ImageReader,
        val handler: Handler,
        val width: Int,
        val height: Int,
    )

    /** 创建虚拟显示。
     * @param name 虚拟屏名称(唯一标识)
     * @param width 宽度像素(默认 1080)
     * @param height 高度像素(默认 1920)
     * @param dpi 密度(默认 280)
     * @return 成功返回 displayId,失败返回 null
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun createDisplay(
        name: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI,
    ): Int? {
        if (displays.containsKey(name)) {
            return displays[name]?.virtualDisplay?.display?.displayId
        }

        return try {
            val handler = Handler(Looper.getMainLooper())
            val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val surface = imageReader.surface

            // FLAG_PUBLIC(1<<0) 允许其他进程看到此虚拟屏
            // FLAG_OWN_CONTENT(1<<2) 不镜像主屏,显示自己的内容
            // FLAG_AUTO_MIRROR(1<<4) 自动镜像(与 OWN_CONTENT 互斥,这里用 OWN_CONTENT)
            // 注:Android 12+ 对 FLAG_AUTO_MIRROR 限制更严,生产应据 API 级别动态选择
            val flags = 0x1 or 0x4  // FLAG_PUBLIC | FLAG_OWN_CONTENT

            @Suppress("DEPRECATION")
            val virtualDisplay = displayManager.createVirtualDisplay(
                name, width, height, dpi, surface, flags,
            )

            if (virtualDisplay == null) {
                Log.w(TAG, "Failed to create virtual display (need Root?)")
                return null
            }

            val session = DisplaySession(name, virtualDisplay, imageReader, handler, width, height)
            displays[name] = session

            val displayId = virtualDisplay.display.displayId
            Log.i(TAG, "Virtual display created: $name (id=$displayId, ${width}x${height})")
            displayId
        } catch (e: Exception) {
            Log.e(TAG, "createDisplay failed", e)
            null
        }
    }

    /** 截图虚拟屏。
     * @param name 虚拟屏名称
     * @return JPEG 数据(base64),失败返回 null
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun screenshot(name: String): ByteArray? {
        val session = displays[name] ?: return null
        return try {
            val image = session.imageReader.acquireLatestImage() ?: return null
            try {
                imageToJpeg(image, session.width, session.height)
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "screenshot failed", e)
            null
        }
    }

    /** 向虚拟屏注入触控事件(Root 下通过 input 命令)。
     * @param name 虚拟屏名称
     * @param x X 坐标
     * @param y Y 坐标
     * @return 成功返回 true
     */
    @Suppress("ReturnCount")
    fun tap(name: String, x: Int, y: Int): Boolean {
        val session = displays[name] ?: return false
        val displayId = session.virtualDisplay.display.displayId
        // Android 12+ 的 input 命令支持 -d <displayId>
        // 需要 Root 权限(input 命令属于 shell 权限)
        val result = RootShellService.exec("input tap $x $y -d $displayId")
        return result?.exitCode == 0
    }

    /** 向虚拟屏注入滑动事件。 */
    @Suppress("ReturnCount")
    fun swipe(name: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val session = displays[name] ?: return false
        val displayId = session.virtualDisplay.display.displayId
        val result = RootShellService.exec("input swipe $x1 $y1 $x2 $y2 $durationMs -d $displayId")
        return result?.exitCode == 0
    }

    /** 在虚拟屏启动 App。
     * @param name 虚拟屏名称
     * @param packageName 包名
     * @param activity 可选 Activity(默认用 launcher Activity)
     */
    @Suppress("ReturnCount")
    fun launchApp(name: String, packageName: String, activity: String? = null): Boolean {
        val session = displays[name] ?: return false
        val displayId = session.virtualDisplay.display.displayId
        val cmd = if (activity != null) {
            "am start --display $displayId -n $packageName/$activity"
        } else {
            "am start --display $displayId $packageName"
        }
        val result = RootShellService.exec(cmd)
        return result?.exitCode == 0
    }

    /** 销毁虚拟屏,释放资源。 */
    fun destroyDisplay(name: String) {
        displays.remove(name)?.let { session ->
            try {
                session.virtualDisplay.release()
                session.imageReader.close()
            } catch (e: Exception) {
                Log.w(TAG, "destroyDisplay cleanup error: ${e.message}")
            }
            Log.i(TAG, "Virtual display destroyed: $name")
        }
    }

    /** 销毁所有虚拟屏。 */
    fun destroyAll() {
        displays.keys.toList().forEach { destroyDisplay(it) }
    }

    /** 获取所有活跃虚拟屏名称。 */
    fun listDisplays(): List<String> = displays.keys.toList()

    /** 获取虚拟屏 displayId。 */
    fun getDisplayId(name: String): Int? = displays[name]?.virtualDisplay?.display?.displayId

    // ── 内部工具 ──

    /** Image RGBA 转 JPEG。 */
    @Suppress("MagicNumber")
    private fun imageToJpeg(image: Image, width: Int, height: Int): ByteArray {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        // 创建 Bitmap
        val bitmap = android.graphics.Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 裁剪到实际宽度
        val cropped = if (rowPadding > 0) {
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }

        // 转 JPEG
        val out = ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
        if (cropped !== bitmap) bitmap.recycle()
        cropped.recycle()
        return out.toByteArray()
    }
}
