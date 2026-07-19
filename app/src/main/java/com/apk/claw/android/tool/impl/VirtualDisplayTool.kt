package com.apk.claw.android.tool.impl

import android.util.Base64
import android.util.Log
import com.apk.claw.android.root.RootShellService
import com.apk.claw.android.root.VirtualDisplayService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult

/**
 * 虚拟显示管理工具 —— 创建/操作软件虚拟屏,实现后台并发自动化。
 *
 * 对标 Operit 的 Shower 系统。核心能力:
 *  - 创建隐藏虚拟屏(Root 下,不在物理屏显示)
 *  - 截图虚拟屏
 *  - 向虚拟屏注入触控/滑动
 *  - 在虚拟屏启动 App
 *
 * 使用场景:
 *  - 后台并发:Agent 在虚拟屏操作 App,用户继续用物理屏
 *  - 多任务:多个虚拟屏同时跑不同自动化
 *  - 无人值守:锁屏后虚拟屏继续工作
 *
 * 安全:
 *  - HIGH 风险:创建虚拟屏 + 注入触控 + 截图(可截 FLAG_SECURE App)→ 来源闸门 + 审计
 *  - 需要 Root(无 Root 时虚拟屏会在物理屏显示 overlay,无法隐藏)
 *
 * 子操作通过 `action` 参数指定:
 *  - create: 创建虚拟屏(params: name, width?, height?, dpi?)
 *  - screenshot: 截图(params: name)→ 返回 base64 图片
 *  - tap: 点击(params: name, x, y)
 *  - swipe: 滑动(params: name, x1, y1, x2, y2, duration_ms?)
 *  - launch: 启动 App(params: name, package_name, activity?)
 *  - destroy: 销毁虚拟屏(params: name)
 *  - list: 列出所有虚拟屏
 */
class VirtualDisplayTool : BaseTool() {

    companion object {
        private const val TAG = "VirtualDisplayTool"
        private val service: VirtualDisplayService? = null  // 由 Application 注入
    }

    override fun getName() = "virtual_display"
    override fun getDisplayName() = if (useChineseDescription) "虚拟显示" else "Virtual Display"

    override fun getParameters() = listOf(
        ToolParameter(
            "action",
            "string",
            "Operation: create | screenshot | tap | swipe | launch | destroy | list",
            true
        ),
        ToolParameter(
            "name",
            "string",
            "Virtual display name (unique identifier). Required for all actions except list.",
            false
        ),
        ToolParameter(
            "width",
            "integer",
            "Display width in pixels (create only). Default 1080.",
            false
        ),
        ToolParameter(
            "height",
            "integer",
            "Display height in pixels (create only). Default 1920.",
            false
        ),
        ToolParameter(
            "dpi",
            "integer",
            "Display density (create only). Default 280.",
            false
        ),
        ToolParameter(
            "x",
            "integer",
            "X coordinate (tap). Or X1 for swipe.",
            false
        ),
        ToolParameter(
            "y",
            "integer",
            "Y coordinate (tap). Or Y1 for swipe.",
            false
        ),
        ToolParameter(
            "x2",
            "integer",
            "End X coordinate (swipe only).",
            false
        ),
        ToolParameter(
            "y2",
            "integer",
            "End Y coordinate (swipe only).",
            false
        ),
        ToolParameter(
            "duration_ms",
            "integer",
            "Swipe duration in ms (swipe only). Default 300.",
            false
        ),
        ToolParameter(
            "package_name",
            "string",
            "App package name (launch only).",
            false
        ),
        ToolParameter(
            "activity",
            "string",
            "Activity class name (launch only, optional).",
            false
        ),
    )

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")
        val svc = service ?: getService()
            ?: return ToolResult.error(
                "VirtualDisplayService 未初始化(缺少 DisplayManager)。需要 App 注入。",
                ToolErr.INTERNAL,
            )

        // Root 检查(所有操作都需要 Root 才能隐藏虚拟屏)
        if (action != "list" && !RootShellService.isAvailable()) {
            return ToolResult.error(
                "虚拟显示需要 Root 权限(隐藏虚拟屏 + 触控注入)。设备未 Root。",
                ToolErr.PERMISSION,
            )
        }

        return try {
            when (action) {
                "create" -> {
                    val name = requireString(params, "name")
                    val width = optionalInt(params, "width", 1080)
                    val height = optionalInt(params, "height", 1920)
                    val dpi = optionalInt(params, "dpi", 280)
                    val displayId = svc.createDisplay(name, width, height, dpi)
                    if (displayId != null) {
                        ToolResult.success("虚拟屏 '$name' 已创建 (displayId=$displayId, ${width}x${height})")
                    } else {
                        ToolResult.error("虚拟屏创建失败(需要 Root 或 DisplayManager 限制)", ToolErr.INTERNAL)
                    }
                }
                "screenshot" -> {
                    val name = requireString(params, "name")
                    val jpeg = svc.screenshot(name)
                    if (jpeg != null) {
                        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                        ToolResult.successWithImage("虚拟屏 '$name' 截图(${jpeg.size} bytes)", base64)
                    } else {
                        ToolResult.error("截图失败(虚拟屏不存在或无帧数据)", ToolErr.NOT_FOUND)
                    }
                }
                "tap" -> {
                    val name = requireString(params, "name")
                    val x = requireInt(params, "x")
                    val y = requireInt(params, "y")
                    if (svc.tap(name, x, y)) {
                        ToolResult.success("虚拟屏 '$name' 点击 ($x, $y) 成功")
                    } else {
                        ToolResult.error("点击失败(触控注入需要 Root)", ToolErr.PERMISSION)
                    }
                }
                "swipe" -> {
                    val name = requireString(params, "name")
                    val x1 = requireInt(params, "x")
                    val y1 = requireInt(params, "y")
                    val x2 = requireInt(params, "x2")
                    val y2 = requireInt(params, "y2")
                    val duration = optionalInt(params, "duration_ms", 300)
                    if (svc.swipe(name, x1, y1, x2, y2, duration)) {
                        ToolResult.success("虚拟屏 '$name' 滑动 ($x1,$y1)→($x2,$y2) 成功")
                    } else {
                        ToolResult.error("滑动失败", ToolErr.PERMISSION)
                    }
                }
                "launch" -> {
                    val name = requireString(params, "name")
                    val pkg = requireString(params, "package_name")
                    val activity = optionalString(params, "activity", "")
                    if (svc.launchApp(name, pkg, activity.takeIf { it.isNotBlank() })) {
                        ToolResult.success("App '$pkg' 已在虚拟屏 '$name' 启动")
                    } else {
                        ToolResult.error("启动失败(包名错误或 Root 权限不足)", ToolErr.UPSTREAM)
                    }
                }
                "destroy" -> {
                    val name = requireString(params, "name")
                    svc.destroyDisplay(name)
                    ToolResult.success("虚拟屏 '$name' 已销毁")
                }
                "list" -> {
                    val list = svc.listDisplays()
                    ToolResult.success(if (list.isEmpty()) "(无活跃虚拟屏)" else list.joinToString(", "))
                }
                else -> ToolResult.error(
                    "未知 action: $action。支持: create/screenshot/tap/swipe/launch/destroy/list",
                    ToolErr.INVALID_PARAM,
                )
            }
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "参数错误", ToolErr.INVALID_PARAM)
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            ToolResult.error("虚拟显示操作异常: ${e.message}", ToolErr.INTERNAL)
        }
    }

    /** 获取 VirtualDisplayService 实例(由 App 注入或从 Context 获取 DisplayManager)。 */
    private fun getService(): VirtualDisplayService? {
        return try {
            val ctx = ToolRegistry.appContext ?: return null
            val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            // 缓存到 companion 的 service 字段(首次创建后复用)
            VirtualDisplayHolder.hold(dm)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get DisplayManager: ${e.message}")
            null
        }
    }

    private object VirtualDisplayHolder {
        @Volatile
        private var instance: VirtualDisplayService? = null
        fun hold(dm: android.hardware.display.DisplayManager): VirtualDisplayService {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = VirtualDisplayService(dm)
                }
            }
            return instance!!
        }
    }

    override fun getDescriptionEN() = """
        Manage virtual displays for background concurrent automation (requires Root).
        Creates hidden virtual screens where the Agent can operate Apps while the user
        continues using the physical screen.

        Actions:
          create   — Create virtual display (params: name, width?, height?, dpi?)
          screenshot — Capture virtual display (returns base64 JPEG)
          tap      — Inject touch (params: name, x, y)
          swipe    — Inject swipe (params: name, x, y, x2, y2, duration_ms?)
          launch   — Launch App on virtual display (params: name, package_name, activity?)
          destroy  — Destroy virtual display (params: name)
          list     — List all active virtual displays

        Requires Root (hidden virtual display + touch injection).
        HIGH risk: can capture FLAG_SECURE Apps, source gate applies.
    """.trimIndent()

    override fun getDescriptionCN() = """
        管理虚拟显示,实现后台并发自动化(需要 Root)。
        创建隐藏虚拟屏,Agent 在虚拟屏操作 App,用户继续使用物理屏。

        操作:
          create   — 创建虚拟屏(参数: name, width?, height?, dpi?)
          screenshot — 截图虚拟屏(返回 base64 JPEG)
          tap      — 注入点击(参数: name, x, y)
          swipe    — 注入滑动(参数: name, x, y, x2, y2, duration_ms?)
          launch   — 在虚拟屏启动 App(参数: name, package_name, activity?)
          destroy  — 销毁虚拟屏(参数: name)
          list     — 列出所有活跃虚拟屏

        需要 Root(隐藏虚拟屏 + 触控注入)。
        HIGH 风险:可截取 FLAG_SECURE 的 App,走来源闸门 + 审计。
    """.trimIndent()
}
