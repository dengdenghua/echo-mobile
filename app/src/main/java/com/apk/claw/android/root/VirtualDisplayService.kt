package com.apk.claw.android.root

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
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
 *
 * ── ROM 适配说明(触控注入) ──
 *
 * `input tap -d <displayId>` 的行为因 ROM 而异,已知差异:
 *
 *  | ROM 家族            | input -d 支持 | 备注 |
 *  |---------------------|---------------|------|
 *  | AOSP / Pixel        | ✅ API 31+    | 标准实现,基准参照 |
 *  | Samsung One UI      | ✅             | 基本跟随 AOSP,少数版本需 -t 触摸设备指定 |
 *  | MIUI (小米/Redmi)   | ⚠️ 部分       | 国内版 MIUI 14+ 起 input 被改造,偶尔吞 -d 参数;国际版 HyperOS 兼容 |
 *  | ColorOS (OPPO/OnePlus)| ⚠️ 部分     | ColorOS 13+ 限制 input 命令需 shell uid 触发,-d 通常可用但偶发无效 |
 *  | OriginOS (Vivo)     | ⚠️ 部分       | Funtouch/OriginOS 对 input 改造较多,-d 在某些版本被忽略 |
 *  | EMUI / HarmonyOS    | ✅             | 华为基本跟随 AOSP,HarmonyOS NEXT 起 input 行为未验证 |
 *  | 魅族 Flyme          | ⚠️ 部分       | 较旧版本 -d 不支持,需 sendevent 兜底 |
 *
 * 兜底策略(按优先级):
 *  1. **首选**:`input tap <x> <y> -d <displayId>`(API 31+ 标准,通过 RootShellService)
 *  2. **次选**:`InputManager.injectInputEvent` reflection(MotionEvent.setDisplayId + 三参 injectInputEvent)
 *     - 需 INJECT_EVENTS 权限或 system uid,普通 App / shell uid 通常失败
 *     - 但某些定制 ROM(如root + Magisk + LSPosed)下可能成功,值得一试
 *  3. **兜底**:`sendevent /dev/input/eventN` 直接写 evdev(需找到虚拟屏对应的 evdev 节点,实现复杂)
 *  4. **API < 31**:不支持虚拟屏触控注入,返回 false 让上层决策(建议改用 AccessibilityService 在主屏操作)
 *
 * 当前实现:策略 1 + 策略 2(失败自动 fallback)。策略 3 待实际 ROM 测试后补。
 * 生产建议:在 SettingsPage 加"虚拟屏触控注入体检"入口,跑一次 tap → 截图 → 比对,让用户感知兼容性。
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
        // `input tap -d <displayId>` 仅 Android 12+(API 31+)支持
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            Log.w(TAG, "tap 不支持:API < 31 无法指定 displayId(虚拟屏触控注入需 Android 12+)")
            return false
        }
        // 策略 1:走 RootShellService 白名单(input 命令前缀已允许)
        val result = RootShellService.exec("input tap $x $y -d $displayId")
        if (result?.exitCode == 0) return true
        Log.w(TAG, "tap 策略1失败(displayId=$displayId, x=$x, y=$y): exitCode=${result?.exitCode} " +
            "manufacturer=${android.os.Build.MANUFACTURER} " +
            "brand=${android.os.Build.BRAND} " +
            "model=${android.os.Build.MODEL}。尝试策略2(InputManager reflection)。")
        // 策略 2:InputManager.injectInputEvent reflection fallback
        return injectTapViaReflection(displayId, x, y)
    }

    /** 向虚拟屏注入滑动事件。 */
    @Suppress("ReturnCount")
    fun swipe(name: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val session = displays[name] ?: return false
        val displayId = session.virtualDisplay.display.displayId
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            Log.w(TAG, "swipe 不支持:API < 31 无法指定 displayId(虚拟屏触控注入需 Android 12+)")
            return false
        }
        val result = RootShellService.exec("input swipe $x1 $y1 $x2 $y2 $durationMs -d $displayId")
        if (result?.exitCode == 0) return true
        Log.w(TAG, "swipe 策略1失败(displayId=$displayId): exitCode=${result?.exitCode} " +
            "manufacturer=${android.os.Build.MANUFACTURER} " +
            "brand=${android.os.Build.BRAND}。尝试策略2(InputManager reflection)。")
        return injectSwipeViaReflection(displayId, x1, y1, x2, y2, durationMs)
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

    // ── InputManager reflection(策略 2 fallback) ──────────────────────────────
    // 缓存反射结果,避免每次 tap/swipe 重复 Class.forName + getMethod
    @Volatile private var inputManagerInstance: Any? = null
    @Volatile private var inputManagerMethod: Method? = null
    @Volatile private var reflectionTried = false
    @Volatile private var setDisplayIdMethod: Method? = null

    /**
     * 策略 2:用 InputManager.injectInputEvent reflection 向指定 display 注入触控。
     *
     * 调用 Android 隐藏 API:
     *  1. `InputManager.getInstance()`(hidden static)拿单例
     *  2. `MotionEvent.setDisplayId(int)`(API 31+ public,API < 31 hidden)
     *  3. `InputManager.injectInputEvent(InputEvent, int displayId, int mode)`(hidden 三参版)
     *
     * 权限要求:
     *  - INJECT_EVENTS 权限(system 签名),普通 App 没有
     *  - 或运行在 system uid(1k uid 之外)
     *  - Root 提权的进程是 root uid(0),不是 system uid(1000),所以 root 设备也通常失败
     *  - 唯一可能成功的场景:ROM 自定义 SELinux 策略宽松,或装了 LSPosed/Xposed hook 绕过权限
     *
     * 因此本方法 return false 是预期常态,只在极少数设备上会成功。失败不报错,只记 debug 日志。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun injectTapViaReflection(displayId: Int, x: Int, y: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime
        val pointerProperties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pointerCoords = MotionEvent.PointerCoords().apply {
            pressure = 1f
            size = 1f
            this.x = x.toFloat()
            this.y = y.toFloat()
        }
        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN,
            1, arrayOf(pointerProperties), arrayOf(pointerCoords),
            0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val upEvent = MotionEvent.obtain(
            downTime, eventTime + 16, MotionEvent.ACTION_UP,
            1, arrayOf(pointerProperties), arrayOf(pointerCoords),
            0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            val ok1 = injectEvent(downEvent, displayId)
            val ok2 = injectEvent(upEvent, displayId)
            ok1 && ok2
        } finally {
            downEvent.recycle()
            upEvent.recycle()
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun injectSwipeViaReflection(
        displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val steps = (durationMs / 16).coerceAtLeast(2)
        val pointerProperties = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val startCoords = MotionEvent.PointerCoords().apply {
            pressure = 1f; size = 1f; this.x = x1.toFloat(); this.y = y1.toFloat()
        }
        val endCoords = MotionEvent.PointerCoords().apply {
            pressure = 1f; size = 1f; this.x = x2.toFloat(); this.y = y2.toFloat()
        }
        val events = ArrayList<MotionEvent>(steps + 2)
        try {
            // DOWN
            events.add(MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, 1,
                arrayOf(pointerProperties), arrayOf(startCoords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            ))
            // MOVE 插值
            for (i in 1 until steps) {
                val t = i.toFloat() / steps
                val coords = MotionEvent.PointerCoords().apply {
                    pressure = 1f; size = 1f
                    this.x = x1 + (x2 - x1) * t
                    this.y = y1 + (y2 - y1) * t
                }
                events.add(MotionEvent.obtain(
                    downTime, downTime + (durationMs * i / steps), MotionEvent.ACTION_MOVE, 1,
                    arrayOf(pointerProperties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                ))
            }
            // UP
            events.add(MotionEvent.obtain(
                downTime, downTime + durationMs, MotionEvent.ACTION_UP, 1,
                arrayOf(pointerProperties), arrayOf(endCoords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            ))
            // 逐个注入
            for (ev in events) {
                if (!injectEvent(ev, displayId)) return false
            }
            return true
        } finally {
            events.forEach { it.recycle() }
        }
    }

    /** 反射设置 MotionEvent.displayId 并调用 InputManager.injectInputEvent 三参版。 */
    @Suppress("TooGenericExceptionCaught")
    private fun injectEvent(event: MotionEvent, displayId: Int): Boolean {
        if (!reflectionTried) {
            synchronized(this) {
                if (!reflectionTried) {
                    prepareReflection()
                    reflectionTried = true
                }
            }
        }
        val im = inputManagerInstance ?: return false
        val inject = inputManagerMethod ?: return false
        val setDisplayId = setDisplayIdMethod
        return try {
            // 设置 displayId
            if (setDisplayId != null) {
                setDisplayId.invoke(event, displayId)
            }
            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            inject.invoke(im, event, displayId, 0) as? Boolean ?: false
        } catch (e: Exception) {
            Log.d(TAG, "injectEvent reflection failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 一次性准备 InputManager reflection 引用。失败则后续 injectEvent 直接返回 false。 */
    @Suppress("TooGenericExceptionCaught")
    private fun prepareReflection() {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            // InputManager.getInstance() 是 hidden static 方法
            val getInstance = imClass.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            inputManagerInstance = getInstance.invoke(null)

            // 三参版 injectInputEvent(InputEvent event, int displayId, int mode) — Android 12+ hidden
            // 如果该方法不存在,尝试两参版(只注入默认 display)
            inputManagerMethod = try {
                imClass.getDeclaredMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).also { it.isAccessible = true }
            } catch (_: NoSuchMethodException) {
                null
            }

            // MotionEvent.setDisplayId(int) — API 31+ public,API < 31 hidden
            setDisplayIdMethod = try {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                try {
                    MotionEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .also { it.isAccessible = true }
                } catch (_: NoSuchMethodException) { null }
            }

            if (inputManagerInstance == null || inputManagerMethod == null) {
                Log.d(TAG, "InputManager reflection unavailable (im=${inputManagerInstance != null}, " +
                    "inject=${inputManagerMethod != null}, setDisplayId=${setDisplayIdMethod != null})")
            }
        } catch (e: Throwable) {
            Log.d(TAG, "InputManager reflection prepare failed: ${e.javaClass.simpleName}: ${e.message}")
            inputManagerInstance = null
            inputManagerMethod = null
        }
    }

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
