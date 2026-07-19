package com.apk.claw.android.octopus_mobile.safety

import com.apk.claw.android.utils.SecretRedactor

/**
 * Central policy for tool risk labels and audit-safe summaries.
 *
 * Strong permissions stay available. This only classifies and redacts metadata
 * so powerful tool calls can be reviewed later.
 */
object ToolRiskPolicy {

    const val RISK_LOW = "low"
    const val RISK_MEDIUM = "medium"
    const val RISK_HIGH = "high"

    val HIGH_RISK_TOOLS: Set<String> = setOf(
        // VPN 隧道:劫持全局网络流量,把所有流量导向用户/插件提供的 SOCKS5 代理。
        // 高危 → 不可信来源走来源闸门,防远端静默把流量引到攻击者代理做中间人。
        // (stop_vpn/vpn_status 无副作用本可低危,但同族一起 HIGH 更简单一致,不留缺口。)
        "start_vpn",
        "stop_vpn",
        "vpn_status",
        "send_sms",
        "send_intent",
        "file_ops",
        "edit_file",
        "backup_app",
        "launch_freeform",
        "resize_window",
        "browser_install_extension",
        "browser_evaluate",
        // 生成网页脚本插件：产出会注入真实网页、能读页面数据的 JS（等同装扩展/在页内 evaluate）。
        // 高危 → 不可信来源走来源闸门,防远端静默给用户网页植入偷数据的脚本。
        "generate_plugin",
        // 生成声明式工具：产出一个会对外发 HTTP(可能带用户数据)的新工具。
        // 高危 → 不可信来源走来源闸门,防远端造一个把数据 POST 到攻击者域名的工具。
        "generate_tool",
        // install_app: 当前无对应已注册工具（孤儿技能已删），保留为前向兼容——
        // 若该能力以插件/技能形式重新出现，默认仍按高危闸门处理。见 ToolRiskPolicyCoverageTest。
        "install_app",
        "send_file",
        // 代码执行:Rhino 进程内沙箱跑 Agent 生成的 JS,具备 fetch(过 UrlGuard 防 SSRF)、
        // 受限目录文件读写、callTool(回 ToolRegistry)等能力 —— 非纯计算,最高危一类。
        // 登记 HIGH → 自动获得「不可信来源弹审批 + 全程审计」,无需新增闸门。见 RunCodeTool/ScriptSandbox。
        "run_code",
        // 会话式代码执行:变量/函数持久,跨多次调用累积状态,风险面与 run_code 一致(均能调高危工具)。
        "run_code_session",
        // Python 代码执行(Chaquopy CPython 3.11):具备 fetch/callTool/文件读写等能力,风险面与 run_code 一致。
        // 登记 HIGH → 不可信来源弹审批 + 全程审计。见 RunPythonTool/PythonSandbox。
        "run_python",
        // Shell 命令执行(经 Shizuku,shell UID 2000):虽只允许查询类命令白名单(pm list/dumpsys/
        // getprop/settings get/logcat -d 等),但仍是系统级特权入口,可能读出设备指纹/账号等敏感信息。
        // 登记 HIGH → 不可信来源走来源闸门 + 全程审计。状态变更命令被白名单拦截,走 file_ops/tap。
        "shell_exec",
        // Linux 容器 Shell(Alpine + PRoot,无需 root):能 apk add 任意包、跑任意 shell/Python/Node/ELF 二进制。
        // 虽然容器通过 PRoot chroot 隔离(看不到 App data/system),但能力面等同桌面 Linux,
        // 可装任意包、跑任意二进制、发任意网络请求(容器内 raw socket 不走宿主 UrlGuard)。
        // 最高危 → 不可信来源走来源闸门 + 全程审计。见 RunShellTool/RunShellSessionTool/LinuxSandbox。
        "run_shell",
        "run_shell_session",
        // 虚拟显示(Root):创建隐藏虚拟屏 + 触控注入 + 可截 FLAG_SECURE App。
        // 最高危 → 不可信来源走来源闸门 + 全程审计。见 VirtualDisplayTool/RootShellService。
        "virtual_display",
        // 全自动配置 Shizuku:与本机 adbd 完成 ADB 配对并跑 shell 拉起 Shizuku(shell 级特权入口)。
        // 最高危一类 → 不可信来源须弹审批 + 全程审计,防远端静默给自己开 Shizuku 提权。见 ShizukuAutoSetupTool。
        "shizuku_auto_setup",
        // 分享到广场:把本地小程序 html 对外发布到公开广场。高危 → 不可信来源须弹审批,
        // 防远端静默把用户/攻击者内容刷上广场。见 ShareToSquareTool。
        "share_to_square",
    )

    val MEDIUM_RISK_TOOLS: Set<String> = setOf(
        "tap",
        "long_press",
        "swipe",
        "input_text",
        "clipboard",
        "open_app",
        "system_key",
        "media_player",
        "browse_files",
        "search_files",
        "read_sms",
        "read_calendar",
        "get_usage_stats",
        // Agnes 生成类：调用外部付费 API，消耗用户积分，纳入审计。
        "generate_image",
        "generate_video",
        // 搜图:向外部图库/logo 服务发出用户查询词(数据出口),返回的图片 URL 会被嵌进生成物。
        // 不烧积分、不改设备状态,但有外部 egress + 影响产物 → 纳入审计(仅审计,不上高危闸门)。
        "search_image",
        // HTML 预览：离屏 WebView 执行任意 JS，纳入审计（与 browser_navigate 同级）。
        "preview_html",
        // 生成应用：内部自带两次 LLM 调用（消耗积分，与 generate_image/video 同类），
        // 产出走 preview_html 同一 iframe 通道，风险面不比它高，同级归类。
        "generate_app",
        // 生成技能：一次 LLM 调用产出 markdown 技能并写库（后续会注入 System Prompt）。
        // 与 generate_app 同类；纳入审计,让不可信源写技能也留痕。
        "generate_skill",
        // 导入技能：把外部 markdown 写进技能库（后续注入 System Prompt);纯本地无 LLM,但同样
        // 是「持久注入」面,纳入审计——防不可信源静默植入指令。
        "import_skill",
        // 状态变更 / 外部写入类：纳入审计，避免远程/LAN 不可信源驱动这些操作却无审计轨迹。
        // （仅审计，不新增拦截——HIGH 才会对不可信源走来源闸门。）
        "navigate",            // UI 导航编排（驱动一连串点击）
        "tap_by_vision",       // 视觉定位点击（与 tap 同类副作用）
        "repeat_actions",      // 重放录制动作序列（子动作各自再过一遍 executeTool 管线）
        "browser_navigate",    // 浏览器导航到任意 URL（SSRF 面，另由 UrlGuard 兜底）
        "browser_click",       // 网页交互（与 tap 同类）
        "browser_type",        // 网页输入（与 input_text 同类，可能填入凭据）
        "create_pm_task",      // 写企业 PM 系统
        "echo_act",            // 写 Echo 虚拟世界
        "echo_bind",           // 绑定角色进 Echo 虚拟世界
        "spawn_subagent",      // 子 Agent 执行子任务（内部各工具再走一遍 executeTool 管线）
        // 本地模型推理(llama.cpp GGUF):本地推理无外部 egress,但模型输出可能不当或含敏感信息 → 纳入审计。
        "run_local_model",
        // 会话重置:销毁会话 scope、释放持久状态。本身无外部副作用,但可丢弃用户/Agent 在会话里
        // 累积的变量与函数定义 —— 纳入审计便于排查「为何我的会话状态没了」。
        "run_code_reset",
    )

    /**
     * 显式声明为低风险（不审计、不过高危来源闸门）的已注册工具白名单。
     *
     * 作用：[ToolRiskPolicyCoverageTest] 断言「每个已注册工具都必须出现在 HIGH/MEDIUM/LOW 之一」，
     * 从而堵住「新增工具未分类 → [riskOf] 静默默认 LOW → 绕过审计与来源闸门」这条「因遗漏而不安全」的漂移路径。
     *
     * 这里的条目均为只读/观察类或低危 navigation：无副作用或仅产生本地良性导航效果，纳入审计意义不大。
     * 任何会改变设备/应用/外部状态的工具都不应放在这里——见 MEDIUM/HIGH。
     */
    val KNOWN_LOW_RISK_TOOLS: Set<String> = setOf(
        // 只读 / 观察类
        "get_screen_info", "look_at_screen", "find_node_info", "get_installed_apps",
        "get_window_info", "take_screenshot", "browser_get_dom", "browser_screenshot",
        "current_time", "device_info", "echo_observe", "list_pm_projects",
        // 控制 / 无副作用
        "finish", "wait", "hello_world",
        // 视频生成状态查询（只读轮询，无副作用）
        "check_video",
        // 滚动 / 检索（轻量、低危）
        "scroll_to_find", "search_app_in_store",
        // mini-app 双工 action(移植 OpenRoom):list_apps 只读;app_action 只触发 mini-app 自己的
        // onAgentAction 处理器,其内部若调 callTool/device 仍走 OctopusBridge 权限门+来源闸门,故 LOW。
        "list_apps", "app_action", "read_app_events",
        // 输入按键事件：TV 遥控导航键，低危且高频，审计价值低于噪音成本，保留 LOW。
        "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
        "press_menu", "press_power", "volume_up", "volume_down",
    )

    /** 已知未注册但有意保留在风险名单中的工具名（前向兼容），供漂移守护排除。 */
    val INTENTIONAL_UNREGISTERED: Set<String> = setOf("install_app")

    private val SENSITIVE_KEY_PARTS = listOf(
        "key", "token", "secret", "password", "passwd", "pwd",
        "authorization", "cookie", "credential",
        "phone", "email", "address", "cvv", "otp", "session",
        "card_number", "card_no", "credit_card", "bank_card",
        "pin_code", "pincode"
    )

    fun riskOf(toolName: String): String = when (toolName) {
        in HIGH_RISK_TOOLS -> RISK_HIGH
        in MEDIUM_RISK_TOOLS -> RISK_MEDIUM
        else -> RISK_LOW
    }

    fun shouldAudit(toolName: String): Boolean = riskOf(toolName) != RISK_LOW

    fun summarizeParams(params: Map<String, Any>, maxValueChars: Int = 160): String {
        if (params.isEmpty()) return "{}"
        val sb = StringBuilder()
        for ((key, value) in params.toSortedMap()) {
            // 敏感键名整体打码；其余值再过一遍 SecretRedactor，
            // 防止无害键名的 value 里夹带密钥/验证码等明文。
            val shown = if (isSensitiveKey(key)) "<redacted>"
                else SecretRedactor.redact(value.toString()) ?: ""
            val entry = "$key=${shown.take(maxValueChars)}"
            // +2 为 ", " 分隔符的 worst case; 超限则追加 "..." 并 break,避免在条目中间截断
            if (sb.length + entry.length + 2 > 1000) {
                sb.append("...")
                break
            }
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(entry)
        }
        return "{${sb}}"
    }

    fun summarizeResult(result: String?, maxChars: Int = 240): String {
        // 工具结果(如 read_sms / clipboard / browser_evaluate 的返回)可能含验证码/token/cookie，
        // 先脱敏再截断,避免明文持久化进审计日志并在 AuditLogActivity 原样展示。
        val cleaned = (result ?: return "").replace('\n', ' ')
        return (SecretRedactor.redact(cleaned) ?: cleaned).take(maxChars)
    }

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_KEY_PARTS.any { lower.contains(it) }
    }
}
