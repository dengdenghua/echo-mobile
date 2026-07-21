package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 代码执行工具 —— 在 Rhino JS 引擎（JVM 内）执行 Agent 生成的脚本。
 *
 * 宿主 API（脚本直接调用）:
 *  - print(…) / console.log(…)   → 输出到工具结果
 *  - readFile(path)               → 读文件（限 Download/Documents）
 *  - writeFile(path, content)     → 写文件（同上）
 *  - fetch(url, options?)         → 同步 HTTP（过 UrlGuard 防 SSRF），返回 {status, ok, body}
 *  - callTool(name, params?)      → 调用任意已注册工具（沿用 ToolRegistry 策略）
 *
 * 安全模型：登记为 HIGH 风险 → ToolRegistry 高危来源闸门自动拦截不可信来源；
 * 沙箱本身不可调用系统 shell，文件访问限定安全路径，fetch 禁内网/回环/元数据并有硬超时，
 * CPU 死循环由指令观察器按 timeout 中断。
 */
class RunCodeTool : BaseTool() {

    companion object {
        private const val MAX_CODE_LEN = 100_000
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val MAX_TIMEOUT_MS = 60_000L
    }

    override fun getName() = "run_code"
    override fun getDisplayName() = if (useChineseDescription) "运行代码" else "Run Code"

    override fun getParameters() = listOf(
        ToolParameter(
            "code", "string",
            "JavaScript to execute in a sandboxed Rhino engine on the device. " +
            "Built-in host APIs: print()/console.log() for output; readFile(path)/writeFile(path,content) " +
            "for file I/O (Download and Documents only); fetch(url, options?) for HTTP; " +
            "callTool(name, params, timeoutMs?) to call any registered device tool (default 10s timeout, max 30s). " +
            "Async is supported: Promise/.then, setTimeout/setInterval/clearTimeout, queueMicrotask — " +
            "the sandbox runs an event loop until all timers/promises settle (bounded by timeout_ms). " +
            "Note: fetch()/readFile()/callTool() are synchronous (return values directly, no await needed); " +
            "async/await SYNTAX is NOT supported by this engine — use Promise + .then() instead. " +
            "Use print() or console.log() to emit results.",
            true
        ),
        ToolParameter(
            "timeout_ms", "integer",
            "Max execution time in milliseconds. Default 20000, max 60000.",
            false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val code = requireString(params, "code")
        if (code.length > MAX_CODE_LEN)
            return ToolResult.error("代码过长（${code.length} > $MAX_CODE_LEN 字符）。")
        val timeout = optionalLong(params, "timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000L, MAX_TIMEOUT_MS)
        // 把当前任务取消令牌传给沙箱,使事件循环等待可被中断,避免 Thread.sleep 阻塞。
        // ScriptSandbox 为 object 单例,RunCodeTool/RunCodeSessionTool/RunCodeResetTool 共享同一份会话状态。
        val raw = ScriptSandbox.execute(code, timeout, currentCancellationToken())
        // run_code 输出走 TEXT Artifact(spec refine-chat-interaction Task 8):
        // 把 stdout 包装为结构化 ToolResult.textBody,UI 据此生成 TEXT 类 Artifact 卡片
        // (折叠态前 3 行预览,右侧栏 TextDetailPane 显示全文)。data 仍照常喂给 LLM 做决策。
        // 失败结果不包装(textBody 仅用于成功输出的展示),原样返回保留 errorCode/errorLine。
        return if (raw.isSuccess && !raw.data.isNullOrEmpty()) {
            ToolResult.successWithText(raw.data!!, raw.data!!)
        } else {
            raw
        }
    }

    override fun getDescriptionEN() = """
        Execute JavaScript on the device using a Rhino engine (no Shizuku required).
        Built-in host APIs available in the script:
          print("msg") / console.log("msg")    — captured output becomes the tool result
          readFile("/sdcard/Download/foo.txt")  — read a file (Download/Documents only)
          writeFile("/sdcard/Download/out.txt", "content") — write a file
          fetch("https://api.example.com/data", {method:"POST", body:"...", headers:{...}})
              — synchronous HTTP request, returns {status, ok, body}
          callTool("tap", {x:500, y:300}, timeoutMs?) — call any registered device tool (default 10s, max 30s)

        Common callTool targets (use list_apps/get_screen_info to discover more):
          UI: tap/swipe/input_text/scroll_to_find/take_screenshot/get_screen_info/find_node_info
          Apps: open_app/list_apps/app_action/navigate/press_back/press_home
          Files: browse_files/search_files/file_ops/edit_file
          System: send_intent/read_calendar/read_sms/get_usage_stats
          Media: generate_image/generate_video/search_image
          Code: run_code/run_python/preview_html/generate_app

        Async: Promise/.then, setTimeout/setInterval/clearTimeout, queueMicrotask all work — the
        sandbox drives an event loop until timers and promises settle (within timeout_ms).
        Caveat: async/await syntax is NOT supported by the Rhino engine — use Promise + .then().
        fetch/readFile/callTool are synchronous (no await needed).

        Use for: data transforms, file processing, API calls, UI automation scripts,
        calculations, text manipulation, multi-step device interactions.
        Timeout default 20s (max 60s). Code ≤ 100000 chars. Output ≤ 64KB (truncated, not error).
    """.trimIndent()

    override fun getDescriptionCN() = """
        在设备上用 Rhino 引擎执行 JavaScript（无需 Shizuku，所有用户可用）。
        脚本内置宿主 API:
          print("msg") / console.log("msg")       → 输出捕获，即工具返回值
          readFile("/sdcard/Download/foo.txt")     → 读文件（限 Download/Documents 目录）
          writeFile("/sdcard/Download/out.txt", "内容") → 写文件
          fetch("https://api.example.com/data", {method:"POST", body:"...", headers:{...}})
              → 同步 HTTP 请求，返回 {status, ok, body}
          callTool("tap", {x:500, y:300}, timeoutMs?) → 调用已注册设备工具（默认 10s 超时，上限 30s）

        callTool 可调用的常用工具（完整列表可通过 list_apps/get_screen_info 等探查）:
          UI: tap/swipe/input_text/scroll_to_find/take_screenshot/get_screen_info/find_node_info
          应用: open_app/list_apps/app_action/navigate/press_back/press_home
          文件: browse_files/search_files/file_ops/edit_file
          系统: send_intent/read_calendar/read_sms/get_usage_stats
          媒体: generate_image/generate_video/search_image
          代码: run_code/run_python/preview_html/generate_app

        异步支持：Promise/.then、setTimeout/setInterval/clearTimeout、queueMicrotask 均可用——
        沙箱内置事件循环会一直驱动到所有定时器/Promise 结束（受 timeout_ms 约束）。
        注意：Rhino 引擎不支持 async/await 语法，请改用 Promise + .then();
        fetch/readFile/callTool 是同步的（直接返回，无需 await）。

        适用：数据处理、文件读写、接口调用、UI 自动化脚本、计算、多步设备交互。
        超时默认 20 秒（上限 60 秒），代码 ≤ 100000 字符，输出 ≤ 64KB（超限截断不报错）。
    """.trimIndent()
}
