package com.apk.claw.android.tool

import com.apk.claw.android.agent.CancellationToken
import com.blankj.utilcode.util.ScreenUtils
import java.util.concurrent.locks.LockSupport

abstract class BaseTool {

    companion object {
        @JvmField
        var useChineseDescription: Boolean = true

        private const val MAX_WAIT_AFTER_MS = 10000L

        @JvmStatic
        val WAIT_AFTER_PARAM = ToolParameter(
            "wait_after",
            "integer",
            "Optional: milliseconds to wait after this action completes (e.g. 2000 for page load). Default 0 (no wait).",
            false
        )

        /**
         * 非幂等工具名集合。这些工具执行会改变设备/应用状态，失败时不应自动重试。
         */
        @JvmField
        val NON_IDEMPOTENT_TOOLS: MutableSet<String> = hashSetOf(
            "tap", "long_press", "swipe", "input_text",
            "dpad_center", "dpad_up", "dpad_down", "dpad_left", "dpad_right",
            "press_menu", "press_power", "volume_up", "volume_down",
            "press_home", "press_recents", "press_back",
            "system_key", "open_app", "send_sms", "send_intent", "send_file",
            "clipboard", "file_ops", "backup_app", "launch_freeform", "resize_window",
            "navigate", "media_player", "schedule_task", "cancel_scheduled_task",
            "repeat_actions", "scroll", "scroll_to_find",
            "browser_click", "browser_type", "browser_navigate", "browser_go_back",
            "browser_go_forward", "browser_reload", "browser_submit", "browser_scroll",
            "browser_tap_by_vision", "tap_by_vision", "vision_click",
            "browser_evaluate", "browser_install_extension",
            "search_app_in_store",
            "run_code",
            "run_code_session",
            "run_python",
            "shell_exec",
            "run_shell",
            "run_shell_session",
            "virtual_display",
            "preview_html",
            "generate_app",
            "edit_file",
            "spawn_subagent",
            // SSH/SFTP:有状态副作用,失败不应自动重试
            "ssh_connect", "ssh_disconnect", "ssh_exec",
            "sftp_write", "sftp_rm", "sftp_mv", "sftp_mkdir",
            // 远程工作空间:有状态副作用,失败不应自动重试
            "workspace_mount", "workspace_unmount", "workspace_push", "workspace_sync",
        )

        /**
         * 只读工具名集合。这些工具纯查询/无副作用,可在同一轮 ReAct 中并行执行,
         * 显著加速 LLM 同时发起多个 get_xxx / list_xxx / search_xxx / read_xxx 的场景。
         *
         * 判定原则(保守):
         *  - 仅查询不修改任何状态(get/list/read/search/look/browse/take_screenshot/wait)
         *  - 子 Agent 派生虽不改外部状态,但占用大量 Agent 资源,不算只读
         *  - 本地推理(run_local_model)无外部 egress 且无设备状态变更,归只读
         *  - 流程控制(finish)虽无副作用,但与 ReAct 主循环强耦合,保守不并行——单独走原路径
         *  - browser_evaluate 看似查询,但会执行 JS 改网页状态,不算只读
         *
         * 不在集合里的工具默认非只读,走串行执行。
         */
        @JvmField
        val READONLY_TOOLS: MutableSet<String> = hashSetOf(
            // 屏幕感知
            "get_screen_info", "look_at_screen", "vision_markers", "find_node_info",
            "take_screenshot", "get_window_info",
            // 视频理解(纯查询 VLM,无设备状态变更)
            "analyze_video",
            // 设备查询
            "get_installed_apps", "get_usage_stats", "wait",
            // 文件浏览(只读)
            "browse_files", "search_files",
            // 个人上下文(只读)
            "read_sms", "read_calendar",
            // PM 任务
            "list_pm_projects",
            // 生图/视频查询
            "check_video", "search_image",
            // mini-app
            "list_apps", "read_app_events",
            // VPN 状态
            "vpn_status",
            // 浏览器(只读)
            "browser_get_dom", "browser_screenshot",
            // 定时任务查询
            "list_scheduled_tasks",
            // SSH/SFTP 只读(查询类,可并行;但会占用同一 SSH session 的 channel,JSch 支持并发)
            "ssh_list", "sftp_ls", "sftp_read", "sftp_stat",
            // 远程工作空间列表/拉取(只读,可并行)
            "workspace_list", "workspace_pull",
            // 代码检索(纯查询,可并行)
            "search_code",
            // 本地模型推理(纯计算,无外部副作用)
            "run_local_model"
        )

        private val threadCancelToken = ThreadLocal<CancellationToken>()

        @JvmStatic
        fun currentCancellationToken(): CancellationToken? = threadCancelToken.get()

        @JvmStatic
        fun <T> withCancellationToken(token: CancellationToken?, block: () -> T): T {
            val old = threadCancelToken.get()
            threadCancelToken.set(token)
            return try {
                block()
            } finally {
                if (old != null) threadCancelToken.set(old) else threadCancelToken.remove()
            }
        }
    }

    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult

    /**
     * 是否为幂等工具。
     * 幂等工具（默认）：重复执行不会产生副作用（read/get/list/observe 类），失败可安全自动重试。
     * 非幂等工具：执行会改变状态（click/tap/input/send/submit 类），失败不应自动重试，
     *           需要先验证当前状态再由 LLM 决策。
     * 子类可覆写此方法，或在 NON_IDEMPOTENT_TOOLS 中注册工具名。
     */
    open fun isIdempotent(): Boolean = getName() !in NON_IDEMPOTENT_TOOLS

    /**
     * 是否为只读工具(纯查询/无副作用)。
     *
     * 与 [isIdempotent] 区别:
     *  - isIdempotent=true 表示"重复执行结果一致",允许失败自动重试
     *  - isReadOnly=true 表示"完全不修改任何状态",允许并行执行
     *
     * 例:`open_app` 是 idempotent(重试仍打开同一 App),但不是 readonly(改变设备前台状态)。
     *     `reset_config` 是 idempotent(重置一次=重置两次),但不是 readonly(改了配置)。
     *     `get_screen_info` 既是 idempotent 也是 readonly。
     *
     * 默认基于 [READONLY_TOOLS] 集合判断;子类可覆写以覆盖默认行为
     * (如 MCP 工具可根据 schema 的 readOnly hint 自动判断)。
     */
    open fun isReadOnly(): Boolean = getName() in READONLY_TOOLS

    /**
     * 返回工具参数列表 + wait_after 通用参数。
     * 供 ToolBridge 注册工具规格时使用。
     */
    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        // 不给 wait / finish / get_screen_info 等观察类工具加 wait_after
        if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot", "get_installed_apps", "find_node_info", "scroll_to_find", "list_scheduled_tasks", "schedule_task", "cancel_scheduled_task", "read_sms", "read_calendar", "get_usage_stats", "send_intent", "send_sms", "get_window_info", "resize_window", "launch_freeform", "browse_files", "search_files", "file_ops", "backup_app", "navigate", "media_player", "edit_file")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    /**
     * 执行工具并处理 wait_after 等待。
     * 供 ToolRegistry.executeTool() 调用。
     */
    fun executeWithWaitAfter(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        return executeWithWaitAfter(params, null)
    }

    fun executeWithWaitAfter(
        params: @JvmSuppressWildcards Map<String, Any>,
        cancellationToken: CancellationToken?
    ): ToolResult {
        return withCancellationToken(cancellationToken) {
            val result = try {
                cancellationToken?.checkCancelled()
                execute(params)
            } catch (ie: InterruptedException) {
                ToolResult.error("Task cancelled during tool execution")
            } catch (e: com.apk.claw.android.agent.TaskCancelledException) {
                ToolResult.error("Task cancelled during tool execution")
            }
            if (result.isSuccess) {
                val waitMs = optionalLong(params, "wait_after", 0)
                if (waitMs in 1..MAX_WAIT_AFTER_MS) {
                    sleepInterruptible(waitMs)
                }
            }
            result
        }
    }

    /**
     * 子类可调用的可中断 sleep。
     * 有 CancellationToken 时响应取消；无 token 时用 LockSupport.parkNanos 替代 Thread.sleep,
     * 可被 Thread.interrupt() 唤醒,避免阻塞线程无法取消。
     * @return true=正常完成等待；false=被中断/取消。
     */
    protected fun sleepInterruptible(ms: Long): Boolean {
        val token = currentCancellationToken()
        return if (token != null) {
            token.sleepInterruptible(ms)
        } else {
            parkInterruptible(ms)
        }
    }

    /** 用 LockSupport 实现可中断等待，避免 Thread.sleep。 */
    private fun parkInterruptible(ms: Long): Boolean {
        if (ms <= 0) return true
        val deadlineNs = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            if (Thread.interrupted()) return false
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0) break
            LockSupport.parkNanos(remainingNs)
        }
        return !Thread.interrupted()
    }

    /** 子类可调用：若当前任务已取消则抛 TaskCancelledException。 */
    protected fun checkCancelled() {
        currentCancellationToken()?.checkCancelled()
    }

    /** 英文描述，子类必须实现 */
    abstract fun getDescriptionEN(): String

    /** 中文描述，子类必须实现 */
    abstract fun getDescriptionCN(): String

    /** 根据语言开关返回描述 */
    fun getDescription(): String =
        if (useChineseDescription) getDescriptionCN() else getDescriptionEN()

    /** 用于展示给用户看的中文名称，子类可覆写 */
    open fun getDisplayName(): String = getName()

    // === Parameter helpers ===

    protected fun requireString(params: @JvmSuppressWildcards Map<String, Any>, key: String): String {
        return params[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    protected fun requireInt(params: @JvmSuppressWildcards Map<String, Any>, key: String): Int {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toInt()
            else -> try { value.toString().toInt() } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Parameter '$key' must be an integer, got: $value")
            }
        }
    }

    protected fun requireLong(params: @JvmSuppressWildcards Map<String, Any>, key: String): Long {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toLong()
            else -> try { value.toString().toLong() } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Parameter '$key' must be a long, got: $value")
            }
        }
    }

    protected fun optionalInt(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun optionalLong(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Long): Long {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalString(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: String): String {
        return params[key]?.toString() ?: defaultValue
    }

    protected fun optionalBoolean(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBoolean()
        }
    }

    // === Screen bounds helpers ===

    /**
     * 获取屏幕尺寸 [width, height]。
     */
    protected fun getScreenSize(): IntArray {
        return intArrayOf(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight())
    }

    /**
     * 校验坐标是否在屏幕范围内，超出则返回错误信息，合法返回 null。
     */
    protected fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        }
        return null
    }
}
