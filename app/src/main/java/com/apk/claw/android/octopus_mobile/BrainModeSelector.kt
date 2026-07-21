package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.util.Log
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 方案 F 决策层切换器 (BrainModeSelector).
 *
 * 两层决策：
 *  1. **远程/本地模式**：母体可达 → EXECUTOR_ONLY，否则 → LOCAL_FALLBACK
 *  2. **浏览器/手机意图**：用户说"打开网页"→自动切浏览器引擎，"打开 App"→手机自动化
 *
 * 切换是无缝的 —— 用户感觉不到.
 *
 * 关键设计（方案 F）：
 *  - **单一 SKILL.md 源**：30 个 SKILL.md 既喂本地 LLM，也喂远程母体
 *  - **零框架依赖**：本地 LLM 客户端 + ReAct 循环都是自写 < 600 行
 *  - **30s 健康检查**：自动检测母体可达性，挂了切本地
 *  - **工具名前缀剥离**：`android.tap` → `tap`（对齐 ToolRegistry 短名）
 *  - **意图自动分类**：基于关键词规则，零 LLM 调用，μs 级
 *
 * ⚠️ 已废弃（PROJECT_ANALYSIS P2 死代码清理,2026-07）：
 *     本类被 AppViewModel/TaskOrchestrator/ClawApplication 实例化并读取 currentMode()/currentDomain()
 *     仅用于日志输出,实际"出站任务委派给母体"的路由裁决在 `TaskOrchestrator.startNewTask` 中
 *     仍是字面 `TODO`,从未真正生效。保留以避免破坏编译,后续应整体重构或删除。
 */
@Deprecated(
    "BrainModeSelector 的本地/远程路由裁决从未接线,仅用于日志。详见类注释与 PROJECT_ANALYSIS P2。",
    level = DeprecationLevel.WARNING,
)
class BrainModeSelector(
    private val context: Context,
    private val rpcClient: OctopusMobileClient,
    private val toolRegistry: ToolRegistry,
) {
    private val tag = "BrainModeSelector"

    /** 当前模式（线程安全） */
    private val currentMode = AtomicReference(BrainMode.EXECUTOR_ONLY)

    /** 当前活跃领域（浏览器 / 手机 / 混合） */
    private val currentDomain = AtomicReference(AutomationDomain.MOBILE)

    /** 缓存加载的 30 SKILL.md（首次加载后不变） */
    private val skillsCache: List<SkillSpec> by lazy {
        SkillManifest.loadFromAssets(context, "skills/mobile")
    }

    /** 模式变化回调（用于 UI 展示） */
    var onModeChanged: ((BrainMode) -> Unit)? = null

    /** 领域变化回调（用于 UI 展示当前是浏览器还是手机） */
    var onDomainChanged: ((AutomationDomain) -> Unit)? = null

    /** 后台健康检查协程 */
    private var healthCheckJob: Job? = null

    /** 启动健康检查循环. */
    fun start(scope: CoroutineScope, checkIntervalMs: Long = 30_000) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch(Dispatchers.IO) {
            while (true) {
                checkAndSwitch()
                delay(checkIntervalMs)
            }
        }
    }

    /** 主动检测一次（外部触发，比如心跳超时时） */
    fun checkAndSwitch() {
        val rpcReachable = rpcClient.currentState() == ConnectionState.ONLINE
        // 母体不可达 → 切本地（即使没有 LLM 配置，也切到 LOCAL_FALLBACK 以触发"无 LLM 配置"的提示路径）
        val newMode = if (!rpcReachable) BrainMode.LOCAL_FALLBACK else BrainMode.EXECUTOR_ONLY
        if (currentMode.get() != newMode) {
            val old = currentMode.getAndSet(newMode)
            Log.i(tag, "mode switched: $old -> $newMode")
            onModeChanged?.invoke(newMode)
        }
    }

    /** 主动切换. */
    fun forceMode(mode: BrainMode) {
        if (currentMode.get() != mode) {
            val old = currentMode.getAndSet(mode)
            Log.i(tag, "force mode: $old -> $mode")
            onModeChanged?.invoke(mode)
        }
    }

    fun currentMode(): BrainMode = currentMode.get()

    /** 当前活跃领域 */
    fun currentDomain(): AutomationDomain = currentDomain.get()

    /** 当前加载的技能数（调试用） */
    fun skillCount(): Int = skillsCache.size

    /**
     * 切换活跃领域，自动配置 BrowserEngine.
     */
    private fun switchDomain(domain: AutomationDomain, intent: IntentClassifier.ClassificationResult) {
        val oldDomain = currentDomain.get()
        if (oldDomain == domain) return

        currentDomain.set(domain)
        Log.i(tag, "domain switched: $oldDomain -> $domain (confidence=${intent.confidence})")
        onDomainChanged?.invoke(domain)

        // 浏览器领域：自动设置最佳引擎
        if (domain == AutomationDomain.BROWSER || domain == AutomationDomain.MIXED) {
            val engine = BrowserEngineFactory.selectBest(context)
            toolRegistry.setBrowserEngine(engine)
            Log.i(tag, "browser engine set: ${engine.name} (antiBot=${engine.antiBotScore})")
        }

        // 手机领域：清除浏览器引擎（browser tools 不再注册）
        if (domain == AutomationDomain.MOBILE) {
            // 不清除 engine，只是不注册 browser tools
            // 如果用户后续切回浏览器，再重新设置
        }
    }

    /** 强制切换领域（外部调用，如 UI 手动切换） */
    fun forceDomain(domain: AutomationDomain) {
        switchDomain(domain, IntentClassifier.ClassificationResult(domain.toIntentType(), 1.0))
    }

    /**
     * 执行本地工具调用 —— 桥接 LightweightReAct ↔ Octopus Mobile ToolRegistry.
     *
     * 关键点：
     *  - LLM 看到的工具名是 `android.tap`（全名）
     *  - Octopus Mobile ToolRegistry 用的是 `tap`（短名）
     *  - 需要做前缀剥离
     */
    suspend fun executeLocalTool(call: ToolCall): ToolExecutionResult {
        val shortName = stripAndroidPrefix(call.name)
        // ToolRegistry.executeTool requires Map<String, Any> (non-null values).
        // ToolCall.args allows nulls, so filter them out before dispatch.
        val nonNullArgs: Map<String, Any> = call.args.filterValues { it != null }.mapValues { it.value!! }
        val result = toolRegistry.executeTool(shortName, nonNullArgs)
        return if (result.isSuccess) {
            ToolExecutionResult.Success(
                toolCallId = call.id,
                display = result.data ?: "(no data)",
                data = result.data
            )
        } else {
            ToolExecutionResult.Failure(
                toolCallId = call.id,
                display = result.error ?: "Unknown error",
                errorCode = -32603,
                errorMessage = result.error ?: "Unknown"
            )
        }
    }

    /** 把 `android.browser_navigate` 剥成 `browser_navigate`；无前缀原样返回. */
    private fun stripAndroidPrefix(name: String): String {
        return if (name.startsWith("android.")) name.removePrefix("android.") else name
    }

    fun stop() {
        healthCheckJob?.cancel()
    }
}

/** 决策模式. */
enum class BrainMode {
    /** 远程决策（默认）：通过 RPC 让母体决策 */
    EXECUTOR_ONLY,

    /** 本地决策（降级）：用轻量 LLM 客户端跑 ReAct 循环 */
    LOCAL_FALLBACK
}

/** 自动化领域. */
enum class AutomationDomain {
    /** 浏览器自动化（Web 页面操作） */
    BROWSER,

    /** 手机自动化（原生 App 操作） */
    MOBILE,

    /** 混合（可能同时涉及两者） */
    MIXED;

    fun toIntentType(): IntentClassifier.IntentType = when (this) {
        BROWSER -> IntentClassifier.IntentType.BROWSER
        MOBILE -> IntentClassifier.IntentType.MOBILE
        MIXED -> IntentClassifier.IntentType.MIXED
    }
}
