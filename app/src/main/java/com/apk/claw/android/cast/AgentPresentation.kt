package com.apk.claw.android.cast

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Agent 外接屏演示窗口 —— 在外接显示器上渲染独立的 Agent 工作区。
 *
 * 核心原理：
 *  android.app.Presentation 是一个特殊的 Dialog，它被创建在指定的 Display 上。
 *  当 show() 被调用后，系统会在外接显示器上创建一个新窗口。
 *  这个窗口与手机屏幕的窗口完全独立，可以显示不同的内容。
 *
 * 架构对标：
 *  - Samsung DeX：手机外接显示器时在外接屏显示桌面模式
 *  - Sula 超级启动器的"异步投屏"：同样的 Presentation API 实现
 *  - iPad 台前调度外接显示器：类似的双屏异显方案
 *
 * 使用方式：
 * ```
 * val display = externalDisplayManager.getPresentationDisplay()
 * if (display != null) {
 *     val presentation = AgentPresentation(context, display)
 *     presentation.show()
 * }
 * ```
 */
class AgentPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    companion object {
        private const val TAG = "AgentPresentation"
    }

    /** 当前在外接屏上运行的 App 列表 */
    private val runningApps = mutableListOf<RunningAppInfo>()

    /** Agent 状态回调 */
    var onStatusChanged: ((String) -> Unit)? = null

    /** Programmatic view references (created in buildWorkspaceUI) */
    private var statusTextView: TextView? = null
    private var workspaceView: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置窗口全屏
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // 构建 UI
        val rootView = buildWorkspaceUI()
        setContentView(rootView)

        Log.i(TAG, "AgentPresentation created on display #${display.displayId}")
    }

    /**
     * 构建外接屏上的工作区 UI。
     *
     * 布局结构：
     * ┌─────────────────────────────────┐
     * │  Echo Agent    状态栏  时间  │  ← 顶部状态栏
     * ├─────────────────────────────────┤
     * │                                 │
     * │   [App 窗口 1]  [App 窗口 2]    │  ← 主工作区（App freeform 窗口在这里）
     * │                                 │
     * │                                 │
     * ├─────────────────────────────────┤
     * │  📱 🏠 ⬅  🔄  ⚙               │  ← 底部 Dock 栏
     * └─────────────────────────────────┘
     */
    private fun buildWorkspaceUI(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090A0C"))
            setPadding(0, 0, 0, 0)
        }

        // ── 顶部状态栏 ──
        val statusBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#101216"))
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "Echo Agent Workspace"
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBar.addView(titleText)

        val statusText = TextView(context).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.parseColor("#6FE3F0"))
        }
        statusTextView = statusText
        statusBar.addView(statusText)

        root.addView(statusBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 主工作区（App 窗口将在这里显示）──
        val workspace = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#171A1F"))
        }
        workspaceView = workspace

        // 默认显示欢迎信息
        val welcomeText = TextView(context).apply {
            text = "Agent workspace ready.\nUse launch_freeform with display_id to start apps here."
            textSize = 20f
            setTextColor(Color.parseColor("#9AA3AE"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        workspace.addView(welcomeText)

        root.addView(workspace, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ── 底部 Dock 栏 ──
        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#101216"))
            setPadding(32, 12, 32, 12)
            gravity = Gravity.CENTER
        }

        val dockItems = listOf("📱 Apps", "🏠 Home", "⬅ Back", "🔄 Refresh", "⚙ Settings")
        for (item in dockItems) {
            val btn = TextView(context).apply {
                text = item
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(48, 16, 48, 16)
                setBackgroundColor(Color.parseColor("#090A0C"))
                setOnClickListener { handleDockAction(item) }
            }
            dock.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 0)
            })
        }

        root.addView(dock, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    /**
     * 处理 Dock 栏按钮点击。
     */
    private fun handleDockAction(action: String) {
        Log.i(TAG, "Dock action: $action")
        when {
            action.contains("Home") -> {
                // 发送 HOME 键到外接屏
                val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
                if (service != null) {
                    service.pressHome()
                }
                onStatusChanged?.invoke("Home pressed")
            }
            action.contains("Back") -> {
                val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
                if (service != null) {
                    service.pressBack()
                }
                onStatusChanged?.invoke("Back pressed")
            }
            action.contains("Refresh") -> {
                // 重启外接屏上的前台 App（通过重新发送 Home + 最近任务模拟）
                onStatusChanged?.invoke("Refreshing workspace...")
            }
            action.contains("Apps") -> {
                val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
                if (service != null) {
                    service.openRecentApps()
                }
                onStatusChanged?.invoke("Recent apps")
            }
            action.contains("Settings") -> {
                onStatusChanged?.invoke("Settings (not implemented)")
            }
        }
    }

    /**
     * 更新状态栏文字。
     */
    fun updateStatus(status: String) {
        statusTextView?.post {
            statusTextView?.text = status
        }
        onStatusChanged?.invoke(status)
    }

    /**
     * 获取工作区容器（用于在外接屏上添加 View）。
     */
    fun getWorkspace(): FrameLayout? {
        return workspaceView
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "AgentPresentation started")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "AgentPresentation stopped")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.i(TAG, "AgentPresentation detached from display #${display.displayId}")
    }

    /**
     * 运行中的 App 信息。
     */
    data class RunningAppInfo(
        val packageName: String,
        val taskId: Int,
        val windowX: Int,
        val windowY: Int,
        val windowWidth: Int,
        val windowHeight: Int
    )
}
