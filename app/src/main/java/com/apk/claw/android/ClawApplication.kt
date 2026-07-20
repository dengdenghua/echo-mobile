package com.apk.claw.android

import android.content.res.Configuration
import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.base.BaseApp
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.crash.CrashReporter
import com.apk.claw.android.octopus_mobile.ApiKeyPool
import com.apk.claw.android.octopus_mobile.BrainModeSelector
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.DeviceDiscoveryManager
import com.apk.claw.android.octopus_mobile.DeviceRegistry
import com.apk.claw.android.octopus_mobile.EvolutionMetrics
import com.apk.claw.android.octopus_mobile.ExperienceLedger
import com.apk.claw.android.octopus_mobile.InteractionLedger
import com.apk.claw.android.octopus_mobile.SkillManifest
import com.apk.claw.android.octopus_mobile.UsageStats
import com.apk.claw.android.octopus_mobile.TurnScorer
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.plugin.PluginManager
import com.apk.claw.android.server.RemoteConsoleGateway
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.DeviceUtils
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.blankj.utilcode.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 入口
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
open class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel

        /**
         * 方案 F 决策层切换器 —— 全局单例.
         * 委托给 AppViewModel.brainSelector
         */
        @JvmStatic
        val brainSelector: BrainModeSelector
            get() = appViewModelInstance.brainSelector!!

        /** 当前加载的 SKILL.md（启动时一次性加载，缓存用） */
        @JvmStatic
        val skills: List<com.apk.claw.android.octopus_mobile.SkillSpec>
            get() {
                val mobileSkills = SkillManifest.loadFromAssets(instance, "skills/mobile")
                // TV 设备额外加载 TV 专属 Skill（D-pad 按键等）
                return if (DeviceUtils.isTvDevice(instance)) {
                    val tvSkills = SkillManifest.loadFromAssets(instance, "skills/tv")
                    mobileSkills + tvSkills
                } else {
                    mobileSkills
                }
            }
    }

    /** Application 级单例：设备注册表 + 设备发现管理器 */
    val deviceRegistry = DeviceRegistry()
    lateinit var deviceDiscoveryManager: DeviceDiscoveryManager
        private set
    /** 插件管理器 */
    lateinit var pluginManager: com.apk.claw.android.plugin.PluginManager
        private set

    /** 事件总线（模块间解耦通知） */
    val eventBus = EventBus()

    override fun onCreate() {
        super.onCreate()
        instance = this
        initializeApp()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 配置变化（如插拔显示器、切换 UiMode）可能改变 TV 判定，失效缓存以便重新探测
        DeviceUtils.invalidateTvCache()
    }

    /**
     * 全量初始化（MMKV 原生库、前台服务、Shizuku、通道等）。
     * 单元测试用 TestClawApplication 覆写为空实现，跳过 JVM 上不可用的原生依赖。
     */
    protected open fun initializeApp() {
        // 崩溃兜底上报:必须在本函数最早一行安装 —— 下面紧跟着的同步初始化链本身也曾被审计
        // 认定为潜在崩溃风险点,越早装、覆盖面越全（含此链自身崩溃）。
        CrashReporter.install(this)
        XLog.setDEBUG(BuildConfig.DEBUG)
        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        // LLM API Key 池初始化(读取持久化的多 key + 统计)。
        // 必须在 KVUtils 之后;在 Agent initialize 之前,使后续 chatWithRetry 可 acquireKey。
        runCatching { ApiKeyPool.init() }.onFailure { XLog.e(TAG, "ApiKeyPool init failed", it) }
        // 主题：由 OctopusTheme 在 Compose 入口根据系统暗色模式 + 用户偏好同步，
        // 不再在此处手动初始化（避免与 Theme.kt 的 SideEffect 冲突）。
        // 旧的 KEY_LIGHT_THEME 偏好会被 getThemeMode() 自动迁移。
        deviceDiscoveryManager = DeviceDiscoveryManager(this, deviceRegistry)
        pluginManager = com.apk.claw.android.plugin.PluginManager(this)

        // 设备类型自动检测：TV 盒子 vs 手机
        val deviceType = if (DeviceUtils.isTvDevice(this)) {
            ToolRegistry.DeviceType.TV
        } else {
            ToolRegistry.DeviceType.MOBILE
        }
        ToolRegistry.getInstance().registerAllTools(deviceType)
        // 注入 Application Context 供审批弹窗使用
        ToolRegistry.getInstance().appContext = this
        // 加载插件生态:dex 工具(assets 签名)+ 非 dex 的 browser-script/tool/mini-app。
        // assets-only fail-closed,无插件时为廉价 no-op。详见 PLUGIN_ECOSYSTEM.md。
        runCatching { pluginManager.loadAll() }.onFailure { XLog.e(TAG, "pluginManager.loadAll failed", it) }
        XLog.e(TAG, "ClawApplication initialized | device=${DeviceUtils.getDeviceDescription(this)} | tools=${ToolRegistry.getInstance().getAllTools().size}")

        runCatching { ExperienceLedger.init(filesDir) }.onFailure { XLog.e(TAG, "ExperienceLedger init failed", it) }
        runCatching { InteractionLedger.init(filesDir) }.onFailure { XLog.e(TAG, "InteractionLedger init failed", it) }
        runCatching { TurnScorer.init(filesDir) }.onFailure { XLog.e(TAG, "TurnScorer init failed", it) }
        runCatching { EvolutionMetrics.load() }.onFailure { XLog.e(TAG, "EvolutionMetrics load failed", it) }
        runCatching { UsageStats.load() }.onFailure { XLog.e(TAG, "UsageStats load failed", it) }

        // Shizuku 增强层初始化（监听 Binder 到达/死亡）
        ShizukuManager.init()
        XLog.i(TAG, "Shizuku initialized: installed=${ShizukuManager.isShizukuInstalled(packageManager)}")

        // App 前后台感知:前台(任一界面可见)时抑制「准备中…」悬浮控制条 —— 对话页内已有内嵌事件流
        // (工具卡片 + 思考进度);仅当退到后台(Agent 跳去操作别的 App)才显示浮条做停止兜底。
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    com.apk.claw.android.floating.LiveControlOverlay.suppressed = true
                }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    com.apk.claw.android.floating.LiveControlOverlay.suppressed = false
                }
            },
        )

        // 网络日志输出到文件（调试时设为 true）
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // 轻量初始化（主线程）
        appViewModelInstance.initCommon()
        // 种下/更新内置技能(产品设计工作流 / 手机自动化编排);放启动处,不污染 buildPromptSection 读路径。
        runCatching { com.apk.claw.android.octopus_mobile.skill.PromptSkillStore.ensureSeeded() }
        if (!ForegroundService.isRunning()) {
            val started = ForegroundService.start(this)
            if (!started) {
                XLog.e(TAG, "ForegroundService start blocked by system")
            }
        }
        // 15 分钟守护(FGS 被杀后重拉)必须无条件调度:原来只在 hasLlmConfig(=BYO key)时
        // 经 onAppInitialized 调度,平台中转登录用户这条防线从未生效过。
        runCatching { com.apk.claw.android.service.KeepAliveJobService.schedule(this) }

        // ── 方案 F · 启动 Octopus Mobile 决策层 ──
        initOctopusMobile()
        RemoteConsoleGateway.connect()

        // MCP server 恢复:后台线程重连所有 autoConnect=true 的 server(子进程启动 + JSON-RPC
        // 握手可能耗时,避免阻塞主线程)。失败不阻塞 App 启动。
        Thread({
            runCatching {
                com.apk.claw.android.tool.mcp.McpServerConfigStore.restoreAll()
            }.onFailure { XLog.e(TAG, "MCP restoreAll failed", it) }
        }, "mcp-restore").start()

        Thread({
            if (KVUtils.hasLlmConfig()) {
                appViewModelInstance.initAgent()
                appViewModelInstance.afterInit()
            }
        }, "app-async-init").start()

        // 崩溃兜底补传：后台协程扫描上次启动遗留的本地崩溃文件并上传，不阻塞启动/UI 线程。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            CrashReporter.uploadPending(this@ClawApplication)
        }
    }

    /**
     * 初始化 Octopus Mobile 模块 —— 委托给 AppViewModel.
     */
    private fun initOctopusMobile() {
        appViewModelInstance.initOctopusMobile()
    }

    /**
     * 探测 Runtime 连通性（用于 StartupModeResolver 决策）.
     */
    fun isRuntimeReachable(): Boolean {
        val url = KVUtils.getOctopusRpcUrl().ifEmpty { return false }
        val httpUrl = url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            .substringBeforeLast("/")
        val conn = try {
            java.net.URL(httpUrl).openConnection() as java.net.HttpURLConnection
        } catch (e: Exception) {
            XLog.d(TAG, "Runtime not reachable: ${e.message}")
            return false
        }
        return try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            code in 200..499
        } catch (e: Exception) {
            XLog.d(TAG, "Runtime not reachable: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    /**
     * 监听网络恢复，自动重新初始化通道。
     * 解决开机自启动时无网络导致通道初始化失败的问题，以及运行中断网恢复后通道重连。
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "网络恢复(${networkType?.name})，检查并重连断开的通道")
                        ChannelManager.reconnectIfNeeded()
                        RemoteConsoleGateway.connect()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "网络断开")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

}
