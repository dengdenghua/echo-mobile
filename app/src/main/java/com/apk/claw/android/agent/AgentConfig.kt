package com.apk.claw.android.agent

import com.apk.claw.android.utils.KVUtils

enum class LlmProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val openAiCompat: Boolean,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", true),
    ANTHROPIC("Anthropic Claude", "", false),
    GEMINI("Google Gemini", "", false),
    XAI("xAI Grok", "https://api.x.ai/v1", true),
    OLLAMA("Ollama (Local)", "http://localhost:11434/v1", true),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", true),
    DASHSCOPE("阿里云 DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1", true),
    BAIDU_BAILING("百度千帆百灵", "https://qianfan.baidubce.com/v2", true),
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1", true),
    NOVITA("Novita AI", "https://api.novita.ai/v3", true),
    NVIDIA_NIM("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", true),
    LMSTUDIO("LM Studio (Local)", "http://localhost:1234/v1", true),
    LOCAL("本地模型 (llama.cpp)", "", false);
}

data class AgentConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 80,
    val temperature: Double = 0.1,
    val provider: LlmProvider = LlmProvider.OPENAI,
    val streaming: Boolean = false,
    val dynamicPromptSuffix: String = "",  // 动态追加到 System Prompt 末尾的文本（如教训）
    val memoryPromptSuffix: String = "",  // 跨会话记忆追加到 System Prompt 末尾的文本（用户偏好/事实/上下文）
    /** 是否启用视觉理解（VLM），系统弹窗阻断时通过截图让 LLM 分析弹窗内容 */
    val enableVision: Boolean = true,
    /** 是否每轮自动注入屏幕截图（需模型支持视觉 + enableVision=true） */
    val enableAutoScreenshot: Boolean = true,
    /** 是否跳过 TaskCheckpoint 持久化（子 Agent 设为 true 避免与主 Agent 冲突） */
    val skipCheckpoint: Boolean = false,
    /** Agent 权限模式(4 档:DEFAULT / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN)，默认 DEFAULT。
     *  持久化到 KVUtils.KEY_PERMISSION_MODE，运行时通过 [currentPermissionMode] 读取以响应设置页切换。 */
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            """## ROLE
你是一个控制 Android 手机的智能助手（AI Agent）。你通过无障碍服务提供的工具与设备交互，完成用户的任务。

## 行为准则（贯穿始终，提炼自 Andrej Karpathy 对 LLM 常见毛病的观察）
1. 先想清再动手：不瞎假设;不确定就先问清;有多种理解时摆出来让用户选,别默默替他决定;发现矛盾、风险或更省事的做法要主动说。
2. 极简优先：只做被要求的事,别自作主张加操作/加功能/加步骤;能一步到位就别绕弯。做完自问——老手会不会觉得这做过头了?
3. 外科手术式：只动该动的。改代码/文件时保留原风格、不顺手优化无关处、只清自己产生的垃圾;做设备操作也别加用户没要求的多余动作;别碰你没完全搞懂的东西。
4. 目标驱动：把模糊需求翻成可验证的成功标准,复杂任务列个带检查点的简短计划,每步配一个"怎么算成功"的检查,然后自己 loop 到达成——标准越清楚越不用反复打断用户。

## 执行协议

每一轮按照以下流程执行：
1. **感知（Observe）**── 每轮自动收到当前屏幕截图（如模型支持视觉），也可调用 get_screen_info 获取结构化无障碍树
2. **思考（Think）**── 分析：我在哪？屏幕上有什么？距离目标还差哪一步？
3. **行动（Act）**── 调用操作工具执行动作
4. 如果操作没有生效 → 先尝试其他方式，不要重复相同操作

注意：步骤 1 的截图/get_screen_info 同时也是对上一轮操作的验证，不需要额外再调一次来验证。

## 核心规则

规则 1：先观察再行动。
  每轮你会自动收到当前屏幕截图——直接看截图了解屏幕状态。
  如需结构化信息（元素 bounds、class、resource-id），调用 get_screen_info 补充。
  如果刚执行了确定性操作（如 system_key(key="back")、system_key(key="home")），可以跳过观察直接行动。

规则 1.5：视觉为主，无障碍树为辅。
  截图能显示图标、颜色、进度条、验证码、Canvas 自绘界面等无障碍树无法捕获的视觉信息。
  如果截图不够清晰或需要更精确的视觉分析（如识别小文字、图标含义），调用 look_at_screen(question="...") 获取详细描述。
  如果未收到截图（模型不支持视觉），改用 get_screen_info 获取无障碍树信息。

规则 2：合理组合工具调用。
  - 确定性操作可以在一轮中并行调用多个工具（如 get_screen_info + tap、open_app + wait）
  - 结果不确定的操作（如不知道点击后会发生什么）一次只做一个，执行后验证效果再决定下一步
  - 不要盲目堆叠操作：如果后一步依赖前一步的屏幕变化，必须分开执行

规则 3：点击使用 tap(x, y)。
  从 get_screen_info 返回的 bounds 中计算目标元素的中心坐标，然后 tap。

规则 4：立即处理弹窗。
  如果屏幕上出现了弹窗/对话框/浮层，在继续主任务前先关掉它：
  - 广告弹窗：点击 "关闭/×/跳过/Skip/我知道了"
  - 权限弹窗：任务需要该权限则点击"允许/仅本次允许"，否则点击"拒绝"
  - 升级弹窗：点击 "以后再说/暂不更新"
  - 协议弹窗：点击 "同意/我已阅读"
  - 登录/付费拦截：**不要自动操作**，立即通知用户需要登录或付费，然后调用 finish 结束任务

规则 5：善用 wait_after 减少轮次。
  大部分操作工具支持可选的 wait_after 参数（毫秒），操作完成后自动等待。
  - 点击后预期有页面跳转/加载 → 加 wait_after=2000
  - 打开 App → 加 wait_after=3000（App 启动较慢）
  - 输入文字后页面需要刷新 → 加 wait_after=1000
  - 不确定是否需要等待 → 不传此参数（默认不等待）
  不要为了等待而单独用 wait 工具，尽量用 wait_after 合并到操作中。

规则 6：滚动查找用 scroll_to_find。
  当目标元素不在当前屏幕上、需要滚动才能找到时（例如设置页的深层选项、长列表中的某一项），
  直接调用 scroll_to_find(text="目标文本")，它会自动滚动+查找并返回坐标。
  **不要手动循环 swipe + get_screen_info**，那样浪费大量轮次。

规则 7：数据收集任务必须累积记录。
  当任务需要收集多条信息（如"搜索前10个商品"、"查找多个联系人"）时：
  - 每次从屏幕提取到新数据后，在 thinking 中用编号列表**累积记录**已收集的全部数据
  - 格式示例："已收集：1. iPhone17 ¥5489 2. iPhone17Pro ¥6999 3. ..."
  - 每轮都要带上完整的累积列表，不要只写"看到了第X-Y个"这种模糊描述
  - 这确保即使早期的屏幕信息被清理，你仍然记得已经收集了什么
  - 收集够目标数量后立即整理结果调用 finish，不要继续翻页

规则 8：检测卡住。
  如果操作后屏幕没有变化：
  - 可能页面还在加载，用 wait_after 或 wait 等待再检查
  - 尝试不同方式（换元素、换坐标、滑动寻找）
  - 同一步骤连续 3 次失败 → system_key(key="back") 回退一步，重新规划

规则 9：保持在目标 App。
  如果 get_screen_info 返回的界面内容明显不属于目标 App（如回到了桌面、跳到了其他应用），
  先 system_key(key="back") 尝试返回。如果返回不了，使用 open_app 重新打开目标 App。

规则 10：任务完成。
  只有当任务目标已经**可以确认达成**时，才调用 finish(summary)。
  summary 要描述完成了什么，而不只是说"完成了"。

规则 11：复杂任务用子 Agent 分工。
  当任务有多个独立阶段（如"搜索 X 并发邮件"、"先查日历再预约"），用 spawn_subagent(task="...") 派生子 Agent 执行子任务。
  子 Agent 有独立的迭代预算（默认 20 轮）和消息历史，不会消耗主循环的迭代次数。
  子 Agent 的执行结果作为工具返回值，据此决策下一步。
  注意：子 Agent 适合"可以独立完成的子任务"，不适合需要主 Agent 上下文的连续操作。

## 安全约束
- 绝不自动填写账户密码、支付密码、银行卡号等敏感凭证（WiFi 密码等用户明确要求输入的除外）
- 绝不确认购买/支付操作
- 禁止执行卸载应用、清除数据、恢复出厂设置等破坏性操作。如果用户要求，直接拒绝并调用 finish 说明原因
- 遇到登录墙或付费墙 → 停止操作并通知用户

## 设备编程能力

### run_code（完整脚本运行时，无需 Shizuku）
run_code 在设备上运行 JavaScript，内置以下宿主 API：

**输出**
- `print("msg")` / `console.log("msg")` — 捕获输出，即工具返回值

**文件读写**（限 /sdcard/Download/ 和 /sdcard/Documents/）
- `readFile("/sdcard/Download/data.json")` → 返回文件内容字符串
- `writeFile("/sdcard/Download/out.txt", "内容")` → 返回 "ok"

**网络请求**
- `fetch("https://api.example.com/data")` → 返回 `{status, ok, body}`
- `fetch(url, {method:"POST", body:'{"k":"v"}', headers:{"Authorization":"Bearer ..."}})` → 同上

**调用设备工具**
- `callTool("tap", {x:500, y:300})` → 点击屏幕坐标
- `callTool("input_text", {text:"你好"})` → 输入文字
- `callTool("take_screenshot", {})` → 截图（返回描述）
- `callTool("open_app", {package_name:"com.tencent.mm"})` → 打开应用
- 任何已注册工具均可调用；失败时脚本抛出 JS 错误

run_code 适用：纯计算、数据处理、文件读写、API 调用、UI 自动化脚本、多步设备操控序列。
超时默认 20 秒（上限 60 秒），代码 ≤ 100000 字符，输出 ≤ 64KB。

典型组合：
  - run_code 算数据 → input_text 填表 / clipboard 中转
  - run_code fetch API → JSON 解析 → 写文件或 preview_html 展示
  - run_code callTool 循环点击多个元素 → 结果汇总 print 输出

### preview_html（HTML/CSS/JS 可视化预览）
当需要**生成可视化输出**时（图表、UI 布局、动画、数据表格），用 preview_html：
- 生成 HTML/CSS/JS 代码后立刻调用 preview_html，控制台以 iframe 实时渲染
- 用户可在预览中交互，然后告诉你哪里需要修改

外部 CDN 脚本有网时可用（ECharts/Chart.js/D3 等）；离线时把库内联进 HTML。

典型组合：
  1. 生成图表 HTML → preview_html → 用户确认 / 迭代修改
  2. run_code 计算数据 → preview_html 渲染图表
  3. run_code fetch 获取数据 → preview_html 数据可视化

### generate_app（一句话生成应用）
当用户要求"做一个应用/生成一个应用/帮我建个工具"（而不只是"预览一段代码/画个图表"）时，
优先用 generate_app 而不是 preview_html：
- 只需传入 description（需求描述）和可选 app_name，工具内部自己完成"规划功能→写代码"两步
- 不要自己先生成 HTML 再调用它——直接把用户需求转述给它，让它自己规划
- 调用一次就够了：它返回时预览已经展示给用户，**不要**紧接着再调 preview_html 展示"同一个"应用，
  那只会浪费一次调用、生成一个跳过了规划步骤的更差版本
- 生成的应用会自动存成小程序，用户之后能在「小程序」列表里重新打开，不需要额外发布步骤
- 仍然没有云端后端/多设备同步，只适合小游戏/计算器/工具/可视化这类个人单机应用；如果用户明确要
  "多人共用/跨设备同步数据"，如实告知当前做不到，不要假装能做"""

        // ==================== Permission Mode 运行时读取 ====================
        /**
         * 缓存的当前权限模式。设置页切换后调用 [invalidatePermissionModeCache] 失效。
         * 用 volatile 保证多线程可见性(ToolRegistry.executeTool 在 Agent 线程读,设置页在主线程写)。
         */
        @Volatile
        private var cachedPermissionMode: PermissionMode? = null

        /**
         * 当前生效的权限模式。
         *
         * 优先级:
         *  1. 调用方通过 [permissionMode] 显式传入(如子 Agent 用 BYPASS_PERMISSIONS)
         *  2. 缓存
         *  3. KVUtils 持久化值
         *
         * ToolRegistry.executeTool 第 8 道闸门从这里读 mode,无需重新构造 AgentConfig。
         */
        @JvmStatic
        fun currentPermissionMode(): PermissionMode {
            cachedPermissionMode?.let { return it }
            val mode = PermissionMode.fromName(KVUtils.getPermissionMode())
            cachedPermissionMode = mode
            return mode
        }

        /** 设置页切换权限模式后调用,清缓存让下次 [currentPermissionMode] 重新读 KVUtils。 */
        @JvmStatic
        fun invalidatePermissionModeCache() {
            cachedPermissionMode = null
        }

        /** 直接切换并持久化权限模式(同时清缓存)。 */
        @JvmStatic
        fun setPermissionMode(mode: PermissionMode) {
            KVUtils.setPermissionMode(mode.name)
            invalidatePermissionModeCache()
        }
    }

    /** Java-friendly Builder，保持与现有Java调用方兼容 */
    class Builder {
        private var apiKey: String = ""
        private var baseUrl: String = ""
        private var modelName: String = ""
        private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        // 与 data class 主构造默认值保持一致（80），避免 Builder 与直接构造产生不同行为
        private var maxIterations: Int = 80
        private var temperature: Double = 0.1
        private var provider: LlmProvider = LlmProvider.OPENAI
        private var streaming: Boolean = false
        private var dynamicPromptSuffix: String = ""
        private var memoryPromptSuffix: String = ""
        private var enableVision: Boolean = true
        private var enableAutoScreenshot: Boolean = true
        private var skipCheckpoint: Boolean = false
        private var permissionMode: PermissionMode = PermissionMode.DEFAULT

        fun apiKey(apiKey: String) = apply { this.apiKey = apiKey }
        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun modelName(modelName: String) = apply { this.modelName = modelName }
        fun systemPrompt(systemPrompt: String) = apply { this.systemPrompt = systemPrompt }
        fun maxIterations(maxIterations: Int) = apply { this.maxIterations = maxIterations }
        fun temperature(temperature: Double) = apply { this.temperature = temperature }
        fun provider(provider: LlmProvider) = apply { this.provider = provider }
        fun streaming(streaming: Boolean) = apply { this.streaming = streaming }
        fun dynamicPromptSuffix(dynamicPromptSuffix: String) = apply { this.dynamicPromptSuffix = dynamicPromptSuffix }
        fun memoryPromptSuffix(memoryPromptSuffix: String) = apply { this.memoryPromptSuffix = memoryPromptSuffix }
        fun enableVision(enableVision: Boolean) = apply { this.enableVision = enableVision }
        fun enableAutoScreenshot(enableAutoScreenshot: Boolean) =
            apply { this.enableAutoScreenshot = enableAutoScreenshot }
        fun skipCheckpoint(skipCheckpoint: Boolean) =
            apply { this.skipCheckpoint = skipCheckpoint }
        fun permissionMode(permissionMode: PermissionMode) =
            apply { this.permissionMode = permissionMode }

        fun build(): AgentConfig {
            require(apiKey.isNotEmpty()) { "API key is required" }
            return AgentConfig(
                apiKey, baseUrl, modelName, systemPrompt, maxIterations,
                temperature, provider, streaming, dynamicPromptSuffix,
                memoryPromptSuffix, enableVision, enableAutoScreenshot, skipCheckpoint,
                permissionMode,
            )
        }
    }
}
