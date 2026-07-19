package com.apk.claw.android.tool

import android.util.Log
import com.apk.claw.android.tool.impl.*
import com.apk.claw.android.tool.impl.browser.*
import com.apk.claw.android.tool.impl.mobile.*
import com.apk.claw.android.tool.impl.tv.*
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.octopus_mobile.safety.ToolCallGuardrailController
import com.apk.claw.android.octopus_mobile.safety.GuardrailDecision
import com.apk.claw.android.octopus_mobile.safety.GuardrailAction
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.octopus_mobile.safety.CircuitBreaker
import com.apk.claw.android.octopus_mobile.safety.PermissionModeManager
import com.apk.claw.android.octopus_mobile.safety.PermissionPolicy
import com.apk.claw.android.octopus_mobile.safety.ApprovalFlow
import com.apk.claw.android.octopus_mobile.safety.IrreversibleActions
import com.apk.claw.android.octopus_mobile.safety.UndoWindow
import com.apk.claw.android.octopus_mobile.safety.DryRunGate
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.octopus_mobile.MobileActionTimeline
import com.apk.claw.android.octopus_mobile.evolution.TurnScorer
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import java.util.concurrent.ConcurrentHashMap

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = ConcurrentHashMap<String, BaseTool>()
    private val pluginTools = ConcurrentHashMap.newKeySet<String>()  // 插件注册的工具名称
    var deviceType: DeviceType = DeviceType.TV
        private set

    @Volatile
    private var browserEngine: com.apk.claw.android.octopus_mobile.browser.BrowserEngine? = null

    /** 工具调用护栏（重复失败 / 无进展检测） */
    val guardrail = ToolCallGuardrailController()

    /** 安全门（PII/Secret 扫描 + LLM 法官） */
    @Volatile
    var safetyGate: SafetyGate? = null

    /** 回合打分器（自进化 L1 层） */
    @Volatile
    var turnScorer: TurnScorer? = null

    /** 事件总线（模块间解耦通知） */
    @Volatile
    var eventBus: EventBus? = null

    /** 断路器（按工具维度熔断，防止持续失败的工具拖垮系统） */
    @Volatile
    var circuitBreaker: CircuitBreaker? = null

    /** Android Context（用于审批弹窗，由 Application 或 Activity 注入） */
    @Volatile
    var appContext: android.content.Context? = null

    // ── 来源信任闸门(R2/R3/R12) ──
    // 标记一段调用来自"不可信来源"：远程母体 WS 的 tool/execute、LAN HTTP debug execute、
    // 由不可信触发源(通知/短信/屏幕文本)自动触发的主动规则。这些路径不经过 LLM agent，
    // 也没有人工确认，历史上可直接驱动最高权限工具。
    private val untrustedDepth = ThreadLocal.withInitial { 0 }

    /** 在 block 内把当前线程的工具调用标记为"不可信来源"（同步执行，结束后恢复）。 */
    fun <T> withUntrustedSource(block: () -> T): T {
        untrustedDepth.set(untrustedDepth.get() + 1)
        return try {
            block()
        } finally {
            untrustedDepth.set((untrustedDepth.get() - 1).coerceAtLeast(0))
        }
    }

    fun isUntrustedSource(): Boolean = untrustedDepth.get() > 0

    // ── 会话级工作空间(per-conversation workspace) ──
    // 类似 Codex 启动时 --cd 选定项目目录:当前会话的工作空间路径,覆盖全局脚本工作空间
    // (KVUtils.getScriptWorkspace)。run_code/run_python 读此处得到 WORKSPACE 全局变量。
    // 由 ChatAgentBridge.run 在执行任务前通过 [withWorkspace] 注入,任务结束自动清除。
    private val workspaceOverride = ThreadLocal.withInitial<String?> { null }

    /**
     * 在 block 内把当前线程的工具调用工作空间设为 [workspace]。
     * [workspace] 为 null/空时清除覆盖(回退全局默认)。
     * ScriptSandbox / PythonSandbox 通过 [currentWorkspace] 读取。
     */
    fun <T> withWorkspace(workspace: String?, block: () -> T): T {
        val prev = workspaceOverride.get()
        workspaceOverride.set(workspace?.takeIf { it.isNotBlank() })
        return try {
            block()
        } finally {
            workspaceOverride.set(prev)
        }
    }

    /**
     * 当前线程的工作空间覆盖值(由 [withWorkspace] 注入)。
     * 为 null 时调用方应回退到 [KVUtils.getScriptWorkspace]。
     */
    fun currentWorkspace(): String? = workspaceOverride.get()

    /**
     * 不可信来源调用高危工具时的确认回调（供 UI 接入"逐次人工确认"）。
     * 返回 true=放行。未注册时回退到 [com.apk.claw.android.utils.KVUtils.isRemoteHighRiskAllowed]
     * （默认 false=拦截）。
     */
    @Volatile
    var highRiskConfirmer: ((toolName: String, params: Map<String, Any>) -> Boolean)? = null

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
        registerBrowserTools()
        registerSystemTools()
    }

    @Synchronized
    fun setBrowserEngine(engine: com.apk.claw.android.octopus_mobile.browser.BrowserEngine) {
        browserEngine = engine
        // 重新注册 browser tools（用新 engine）
        tools.keys.removeIf { it.startsWith("browser_") }
        registerBrowserTools()
    }

    /**
     * 清除浏览器引擎引用（Activity onDestroy 时调用，避免内存泄漏）。
     */
    fun clearBrowserEngine() {
        browserEngine = null
        tools.keys.removeIf { it.startsWith("browser_") }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(LookAtScreenTool())
        register(VisionMarkersTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(FinishTool())
        // 生图/生视频(Agnes 增值;会员免费/非会员扣积分由服务端处理)
        register(com.apk.claw.android.tool.impl.GenerateImageTool())
        // 生视频:提交返回 video_id,check_video 凭它轮询 /agnesapi(视频约 2 分钟生成)
        register(com.apk.claw.android.tool.impl.GenerateVideoTool())
        register(com.apk.claw.android.tool.impl.CheckVideoTool())
        // 搜图(免 key,Openverse 图库照片 + Clearbit 品牌 logo):给「产品设计工作流」配真实素材,不烧积分。
        register(com.apk.claw.android.tool.impl.SearchImageTool())
        // 企业版 PM 编程接入(D①):未配置 octopus.pm.url 时工具会返回错误而非崩溃。
        register(CreatePmTaskTool())
        register(ListPmProjectsTool())

        // 代码执行(Rhino 沙箱,纯 JVM,所有用户可用,登记为 HIGH)
        register(com.apk.claw.android.tool.impl.RunCodeTool())
        // 会话式代码执行:同一 sessionId 复用 scope,变量/函数定义跨多次执行持久(分步调试/多轮构建)
        register(com.apk.claw.android.tool.impl.RunCodeSessionTool())
        // 会话重置:销毁指定会话,释放持久状态
        register(com.apk.claw.android.tool.impl.RunCodeResetTool())
        // Python 代码执行(Chaquopy CPython 3.11,纯 App 进程,所有用户可用,登记为 HIGH)
        // 适合需要丰富标准库 / 列表推导 / 装饰器 / 类继承等 JS 沙箱表达力不足的场景
        register(com.apk.claw.android.tool.impl.RunPythonTool())

        // Shell 命令执行(经 Shizuku,只读查询白名单:pm/dumpsys/getprop/settings get/logcat -d 等)
        // 高危 → 不可信来源走来源闸门;只允许查询类命令,状态变更走 file_ops/tap/input_text
        register(com.apk.claw.android.tool.impl.ShellExecTool())

        // Linux 容器 Shell(Alpine + PRoot,无需 root):能 apk add 任意包、跑任意 shell/Python/Node/ELF 二进制。
        // 高危 → 不可信来源走来源闸门 + 全程审计。容器隔离(PRoot chroot)是主要防线。
        register(com.apk.claw.android.tool.impl.RunShellTool())
        register(com.apk.claw.android.tool.impl.RunShellSessionTool())

        // 本地模型推理(llama.cpp GGUF):设备端离线推理,隐私数据不出设备。
        // MEDIUM 风险(本地推理无外部 egress,但模型输出可能不当 → 纳入审计)。
        register(com.apk.claw.android.tool.localmodel.RunLocalModelTool())

        // mini-app 双工 action 架构(移植 OpenRoom):两工具间接层,Agent 发现并操作已装 mini-app。
        register(com.apk.claw.android.tool.impl.ListAppsTool())
        register(com.apk.claw.android.tool.impl.AppActionTool())
        register(com.apk.claw.android.tool.impl.ReadAppEventsTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(SearchAppInStoreTool())
        // Path A: 移动端专属系统工具
        register(ReadSmsTool())
        register(SendSmsTool())
    }

    /** Path A: 系统级工具（Intent 通信 + 个人上下文），TV 和 MOBILE 均可用 */
    private fun registerSystemTools() {
        register(SendIntentTool())
        register(ReadCalendarTool())
        register(GetUsageStatsTool())

        // Extendroid 式多窗口工具（需要 Shizuku）
        register(LaunchFreeformTool())
        register(ResizeWindowTool())
        register(GetWindowInfoTool())

        // HTML 预览工具（离屏 WebView 渲染，截图返回）
        register(PreviewHtmlTool())
        // 生成应用（Phase 1：规划+编码两段流水线，产出走 preview_html 同一展示通道，无持久化）
        register(GenerateAppTool())
        register(ShareToSquareTool())
        register(GenerateSkillTool())
        register(ImportSkillTool())
        register(GenerateBrowserPluginTool())
        register(GenerateToolTool())

        // AI NAS 文件管理工具（需要 Shizuku）
        register(BrowseFilesTool())
        register(SearchFilesTool())
        register(FileOpsTool())
        register(AppBackupTool())
        register(com.apk.claw.android.tool.impl.EditFileTool())

        // UI 导航知识图谱工具(明确指 impl/NavigateTool.kt,
        // 跟 impl/browser/BrowserTools.kt 里的 NavigateTool 重名)
        register(com.apk.claw.android.tool.impl.NavigateTool())

        // 子 Agent 工具（多 Agent 分工：主 Agent 派生子 Agent 执行复杂子任务）
        register(com.apk.claw.android.tool.impl.SubAgentTool())

        // 全自动配置 Shizuku：视觉 Agent 照「自动配置 Shizuku」技能剧本读配对码,本工具做 ADB 握手。
        register(com.apk.claw.android.shizuku.autosetup.ShizukuAutoSetupTool())
        com.apk.claw.android.shizuku.autosetup.ShizukuAutoSetupSkill.seedIfAbsent()

        // 媒体播放器工具（mpv 引擎）
        register(MediaTools())

        // 100 行扩展示例工具（EXTENDING.md）
        HelloWorldTools.registerAll()

        // Echo Universe 工具：让 agent 感知并影响 Echo 虚拟世界
        EchoUniverseTools.registerAll()

        // VPN 代理工具（SOCKS5 隧道引擎，社区插件可调用）
        register(StartVpnTool())
        register(StopVpnTool())
        register(VpnStatusTool())
    }

    private fun registerBrowserTools() {
        val engine = browserEngine ?: return  // 没有 engine 就不注册 browser tools
        register(com.apk.claw.android.tool.impl.browser.BrowserNavigateTool(engine))
        register(GetDomTool(engine))
        register(BrowserClickTool(engine))
        register(BrowserTypeTool(engine))
        register(BrowserScreenshotTool(engine))
        register(BrowserEvaluateTool(engine))
        register(InstallExtensionTool(engine))
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    /**
     * 注册插件工具（带标记）。
     *
     * 安全:拒绝覆盖同名内置工具 —— 否则声明式/registry 插件可用同名工具劫持内置高危工具
     * (如 file_ops/run_code)的实现或风险语义。仅允许覆盖已存在的「插件」工具(可更新)。
     */
    fun registerPluginTool(tool: BaseTool) {
        val name = tool.getName()
        val existing = tools[name]
        if (existing != null && name !in pluginTools) {
            Log.w("ToolRegistry", "refuse to register plugin tool '$name': collides with built-in tool")
            return
        }
        tools[name] = tool
        pluginTools.add(name)
    }

    /**
     * 注销指定名称的工具。
     */
    fun unregister(name: String) {
        tools.remove(name)
        pluginTools.remove(name)
    }

    /**
     * 检查指定工具是否由插件提供。
     */
    fun isPluginTool(name: String): Boolean = pluginTools.contains(name)

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    /** 工具是否启用(用户可在「技能」页停用非核心工具,Agent 工具规格据此过滤)。 */
    fun isToolEnabled(name: String): Boolean =
        name !in com.apk.claw.android.utils.KVUtils.getDisabledTools()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        return executeTool(name, params, null)
    }

    fun executeTool(name: String, params: Map<String, Any>, cancellationToken: com.apk.claw.android.agent.CancellationToken?): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name", ToolErr.NOT_FOUND)
        val auditStartMs = System.currentTimeMillis()
        val auditRisk = ToolRiskPolicy.riskOf(name)
        val auditParams = if (ToolRiskPolicy.shouldAudit(name) && PermissionModeManager.getCurrentPolicy().auditLogEnabled) {
            ToolRiskPolicy.summarizeParams(params)
        } else {
            ""
        }

        fun audited(result: ToolResult, blockedBy: String? = null): ToolResult {
            val duration = System.currentTimeMillis() - auditStartMs
            MobileActionTimeline.record(
                toolName = name,
                params = params,
                success = result.isSuccess,
                resultText = if (result.isSuccess) result.data else result.error,
                blockedBy = blockedBy,
                durationMs = duration,
                source = if (isUntrustedSource()) "untrusted" else "local",
                hasImage = result.imageBase64 != null,
                hasHtml = result.htmlContent != null,
            )
            if (ToolRiskPolicy.shouldAudit(name) && PermissionModeManager.getCurrentPolicy().auditLogEnabled) {
                val resultText = if (result.isSuccess) result.data else result.error
                ToolAuditLog.record(
                    ToolAuditLog.Entry(
                        id = "tool_${auditStartMs}_${name}",
                        ts = auditStartMs,
                        toolName = name,
                        risk = auditRisk,
                        params = auditParams,
                        success = result.isSuccess,
                        result = ToolRiskPolicy.summarizeResult(resultText),
                        blockedBy = blockedBy,
                        durationMs = duration,
                    )
                )
                eventBus?.publish(EventBus.ToolAuditEvent(name, auditRisk, result.isSuccess, blockedBy, duration))
            }
            return result
        }

        if (!isToolEnabled(name)) {
            eventBus?.publish(EventBus.ToolBlockedEvent(name, "tool_disabled", "settings"))
            return audited(ToolResult.error("工具已停用: $name", ToolErr.BLOCKED), blockedBy = "settings")
        }

        // ── 演示/只读模式:改动型工具全跳过(只演示不执行),只读工具照常,让用户安全预览 ──
        // 早于所有门:短路返回演示结果,不真正改动设备/账号。fail-safe——只增拦截。
        if (DryRunGate.shouldSkip(name, com.apk.claw.android.utils.KVUtils.isDryRunMode())) {
            eventBus?.publish(EventBus.ToolBlockedEvent(name, "dry_run", "dry_run"))
            return audited(DryRunGate.skipResult(name), blockedBy = "dry_run")
        }

        // ── 权限策略（统一读取 PermissionModeManager）──
        val policy = PermissionModeManager.getCurrentPolicy()

        // ── 断路器熔断检查（全工具维度，防止持续失败拖垮系统）──
        // 不可关闭项：circuitBreakerEnabled 始终为 true，即使 FULL_POWER 也不关
        if (policy.circuitBreakerEnabled) {
            val breaker = circuitBreaker
            if (breaker != null) {
                try {
                    breaker.check()
                } catch (e: CircuitBreaker.CircuitOpenException) {
                    eventBus?.publish(EventBus.ToolBlockedEvent(name, e.reason, "circuit_breaker"))
                    return audited(
                        ToolResult.error("工具调用被熔断: ${e.reason}（冷却 ${e.cooldownSeconds}s）", ToolErr.BLOCKED),
                        blockedBy = "circuit_breaker",
                    )
                }
            }
        }

        // ── 高危/中危工具来源闸门 + 审批流程 ──
        // APPROVAL 模式：不可信来源调高危工具 → 弹窗审批；中危工具按 mediumRiskAction 处理
        // FULL_POWER 模式：trustAllSources=true，跳过来源闸门，高危工具自动放行
        // 注:中危工具(tap/swipe/input_text/clipboard/browser_navigate 等)从不可信来源驱动时
        // 也能造成实质危害(读剪贴板凭据/输密码/跳钓鱼站),故同样走闸门,仅 action 默认更宽松。
        val riskLevel = ToolRiskPolicy.riskOf(name)
        val isHighRisk = riskLevel == ToolRiskPolicy.RISK_HIGH
        val isMediumRisk = riskLevel == ToolRiskPolicy.RISK_MEDIUM
        val needsSourceGate = !policy.trustAllSources && isUntrustedSource() && (isHighRisk || isMediumRisk)

        if (needsSourceGate) {
            // HIGH 走 highRiskAction,MEDIUM 走 mediumRiskAction(默认 ALLOW,用户可收紧为 CONFIRM)
            val action = if (isHighRisk) policy.highRiskAction else policy.mediumRiskAction
            when (action) {
                PermissionPolicy.RiskAction.BLOCK -> {
                    eventBus?.publish(EventBus.ToolBlockedEvent(name, "risk_blocked", "policy"))
                    return audited(
                        ToolResult.error("${if (isHighRisk) "高危" else "中危"}工具「$name」被安全策略拦截（当前为审批模式）。", ToolErr.BLOCKED),
                        blockedBy = "policy",
                    )
                }
                PermissionPolicy.RiskAction.CONFIRM -> {
                    // 确认优先级（与 [highRiskConfirmer] 文档契约一致）：
                    //  1) 若注册了 UI 确认回调（逐次人工确认）→ 用它；
                    //  2) 否则若用户在设置里显式打开"允许远程来源执行高危工具"→ 放行（适合无人值守的母体/局域网/群控机）；
                    //  3) 否则弹本地审批窗（默认，阻塞等待设备前用户确认）。
                    val riskDesc = "${if (isHighRisk) "高危" else "中危"}工具 · 不可信来源(${if (isUntrustedSource()) "远程/自动" else "本地"})"
                    val approved = highRiskConfirmer?.invoke(name, params)
                        ?: if (com.apk.claw.android.utils.KVUtils.isRemoteHighRiskAllowed()) {
                            true
                        } else {
                            ApprovalFlow.requestApproval(appContext, name, params, riskDesc)
                        }
                    if (!approved) {
                        eventBus?.publish(EventBus.ToolBlockedEvent(name, "approval_denied", "policy"))
                        return audited(
                            ToolResult.error("${if (isHighRisk) "高危" else "中危"}工具「$name」被用户拒绝或审批超时。", ToolErr.PERMISSION),
                            blockedBy = "approval",
                        )
                    }
                }
                PermissionPolicy.RiskAction.ALLOW -> {
                    // 放行（不弹窗）
                }
            }
        }

        // ── 不可逆动作·撤销窗口 ──
        // 补"本地在场用户驱动不可逆外部副作用"的缺口:上面的来源闸门只拦不可信来源,
        // 本地(trusted)高危动作此前零拦截。这里给发短信/发帖/发文件一个 Gmail undo-send 式可撤销窗:
        // 默认放行不加确认摩擦,但留数秒可撤销。仅本地来源触发(不可信来源已走 ApprovalFlow,不叠弹窗);
        // 无前台 Activity/用户关闭 → UndoWindow 内部直接放行,不改无人值守/满血语义。
        if (IrreversibleActions.isIrreversible(name) && !isUntrustedSource()) {
            val proceed = UndoWindow.awaitOrProceed(name, IrreversibleActions.describe(name, params))
            if (!proceed) {
                eventBus?.publish(EventBus.ToolBlockedEvent(name, "undo_cancelled", "undo_window"))
                return audited(
                    ToolResult.error("操作已被撤销（撤销窗口内取消）: $name", ToolErr.PERMISSION),
                    blockedBy = "undo_window",
                )
            }
        }

        // ── 安全门检查（PII/Secret 扫描 + LLM 宪法法官）──
        // safetyGateEnabled 控制完整 SafetyGate（含 LLM 法官层）。
        // 但 privacyScannerEnabled 是"不可关闭项"：即使 FULL_POWER 下 safetyGateEnabled=false，
        // 只要 privacyScannerEnabled=true（始终），仍单独跑 PrivacyScanner 规则层（零成本），
        // 防止 Secret/PII 明文外泄。
        if (policy.safetyGateEnabled) {
            safetyGate?.let { gate ->
                val verdict = gate.checkToolCall(name, params)
                if (verdict.isBlocked) {
                    eventBus?.publish(EventBus.ToolBlockedEvent(name, verdict.reason, "safety"))
                    return audited(ToolResult.error("安全拦截: ${verdict.reason}", ToolErr.BLOCKED), blockedBy = "safety")
                }
            }
        } else if (policy.privacyScannerEnabled) {
            // FULL_POWER 模式下仍跑规则层（不调 LLM 法官，零成本）
            val argsText = params.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val (_, secretHits, _) = com.apk.claw.android.octopus_mobile.safety.PrivacyScanner.fullCheck(
                "tool:$name args:$argsText"
            )
            if (secretHits.isNotEmpty()) {
                val desc = secretHits.map { it.description }.toSet().joinToString(", ")
                eventBus?.publish(EventBus.ToolBlockedEvent(name, "secret_detected: $desc", "safety"))
                return audited(ToolResult.error("安全拦截: 检测到敏感信息: $desc", ToolErr.BLOCKED), blockedBy = "safety")
            }
        }

        // ── 护栏预检（只读，重复失败 / 同工具失败达阈值则拦截）──
        val preCheck = guardrail.precheck(name, params)
        if (preCheck.shouldHalt) {
            eventBus?.publish(EventBus.ToolBlockedEvent(name, preCheck.message, "guardrail"))
            return audited(ToolResult.error("护栏拦截: ${preCheck.message}", ToolErr.BLOCKED), blockedBy = "guardrail")
        }

        // ── 执行工具 ──
        val result = try {
            tool.executeWithWaitAfter(params, cancellationToken)
        } catch (e: Exception) {
            circuitBreaker?.record(success = false)
            // requireString/requireInt 缺参/类型错都抛 IllegalArgumentException → 归类 INVALID_PARAM,
            // Agent 据此知道该改参数而非原样重试;其余按内部异常。
            val code = if (e is IllegalArgumentException) ToolErr.INVALID_PARAM else ToolErr.INTERNAL
            ToolResult.error("Tool execution failed: ${e.message}", code)
        }

        // ── 护栏观察结果（每次调用只在此处记录一次，避免重复计数）──
        val finalResult: ToolResult = if (!result.isSuccess) {
            val failCheck = guardrail.observe(name, params, result.data ?: result.error, failed = true)
            if (failCheck.action == GuardrailAction.WARN) {
                // 警告但不阻止后续执行：保持失败语义，仅把警告附加到错误信息(保留原 errorCode/行号)
                ToolResult.error("${result.error} [⚠️ ${failCheck.message}]", result.errorCode ?: ToolErr.INTERNAL, result.errorLine)
            } else {
                result
            }
        } else {
            guardrail.observe(name, params, result.data, failed = false)
            result
        }

        // ── 断路器记录结果 ──
        circuitBreaker?.record(success = finalResult.isSuccess)

        // ── 自进化打分（无论是否 WARN 都记录）──
        turnScorer?.record(name, success = finalResult.isSuccess, reason = finalResult.data ?: finalResult.error ?: "")

        return audited(finalResult)
    }
}
