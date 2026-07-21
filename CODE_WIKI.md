# Octopus Mobile · Code Wiki

> 本文档是对 octopus-mobile 仓库的结构化代码百科，涵盖项目整体架构、模块职责、关键类与函数、依赖关系及运行方式。
> 代码版本对应 `versionName 0.0.7`（applicationId `com.octopus.mobile`）。

---

## 目录

1. [项目概述](#1-项目概述)
2. [项目整体架构](#2-项目整体架构)
3. [目录结构](#3-目录结构)
4. [主要模块职责](#4-主要模块职责)
5. [关键类与函数说明](#5-关键类与函数说明)
6. [依赖关系](#6-依赖关系)
7. [项目运行方式](#7-项目运行方式)
8. [实现状态速查](#8-实现状态速查)

---

## 1. 项目概述

Octopus Mobile 是一款 **AI 驱动的 Android 自动化应用**。用户通过即时通讯渠道（钉钉 / 飞书 / QQ / Discord / Telegram / 微信）发送自然语言指令，由 LLM Agent 理解意图后自主操控 Android 设备（手机或 TV 盒子）。

核心特性：
- **Agent 循环**：观察 → 思考 → 行动 → 验证（Observe→Think→Act→Verify），基于 LangChain4j 桥接 LLM。
- **多渠道接入**：6 种 IM 平台统一抽象，支持优先级任务队列与抢占。
- **可插拔 LLM 后端**：OpenAI 兼容 / Anthropic，流式与非流式，OkHttp 适配 Android。
- **工具系统**：通过无障碍服务（AccessibilityService）执行手势、读屏、截图；Shizuku 提供 shell 级增强。
- **Octopus Mobile 触手层**：将设备变为 octopus-agent Runtime 的物理触手。**入站**远程控制(母体下发 `tool/execute`)已真实接线并经安全闸门;屏幕串流「可用但未完;**出站**任务委托给 Runtime 尚未接线(见 §2.3 步骤 6 与 README 实现状态表)。
- **安全护栏**：SafetyGate（密钥正则扫描;LLM 宪法判官以 judge=null 构造,当前不运行）、ToolCallGuardrail、断路器、审批流、来源信任闸门。
- **自进化**：TurnScorer 打分 + EvolutionEngine 反思，教训持久化并注入系统提示词(**B1/B2 活跃;B3 deepEvolve 已废弃——函数从未存在,历史文档误称,实际仅有 deepReflect**)。

---

## 2. 项目整体架构

### 2.1 系统架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                      消息渠道 (IM Channels)                       │
│   钉钉  │  飞书  │  QQ  │  Discord  │  Telegram  │  微信          │
└────────────────────────────┬────────────────────────────────────┘
                             │ inbound message
                             ▼
                    ┌─────────────────┐
                    │ ChannelManager  │  消息路由与分发 + ACL 鉴权
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │TaskOrchestrator │  优先级任务队列 + 抢占 + Home 重置
                    │  + ReflexRouter │  关键词反射（跳过 LLM）
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
       ┌──────▼──────┐  ┌────▼─────┐  ┌─────▼──────┐
       │DefaultAgent │  │BrainMode │  │  Octopus   │
       │  Service    │  │ Selector │  │MobileClient│
       │ (本地 LLM)  │  │(本地/远程)│  │ (触手 RPC) │
       └──────┬──────┘  └────┬─────┘  └─────┬──────┘
              │              │              │
              │   ┌──────────┘              │
              ▼   ▼                         ▼
        ┌──────────────┐            ┌───────────────┐
        │ ToolRegistry │            │  octopus-agent │
        │  (安全管线)   │            │    Runtime     │
        └──────┬───────┘            └───────────────┘
               │
       ┌───────▼────────────────────────────┐
       │  ClawAccessibilityService          │
       │  ShizukuShellService               │
       │  (手势/UI树/截图/shell增强)         │
       └────────────────────────────────────┘
               │
               ▼
          Android 设备 / 远程设备
```

### 2.2 三种运行模式（StartupMode）—— ⚠️ 已废弃

> **已删除（PROJECT_ANALYSIS P2 死代码清理,2026-07）**：`StartupMode.kt` 枚举与 `StartupModeResolver` 在生产代码中无任何实际使用方(仅 import 与注释引用),已整体删除。`ClawApplication.isRuntimeReachable()` 同步删除。下表保留仅供历史追溯。

| 模式 | 行为 | 适用场景 |
|------|------|----------|
| `LOCAL_ONLY` | 纯本地 Agent，不连接 Runtime | 离线、隐私敏感 |
| `RPC_ONLY` | 纯执行器，LLM 在 Runtime 端，Runtime 不可达则拒绝启动 | 受控集群 |
| `DUAL`（默认） | Runtime 优先，离线无缝降级为本地 | 通用 |

### 2.3 核心执行流程

1. 用户通过 IM 渠道发送自然语言消息
2. `ChannelHandler` 接收 → `ChannelManager.dispatchMessage` → `ChannelSetup` 监听器
3. `ChannelSetup` 校验无障碍服务 + `ChannelAccessControl` 鉴权（TOFU 白名单）
4. `TaskOrchestrator.startNewTask`：先尝试 `ReflexRouter` 关键词反射；否则入优先级队列
5. 任务执行前按 Home 键重置设备状态
6. 进入 `DefaultAgentService` 本地 Agent 循环。
   > ⚠️ **实际接线状态：本地执行是唯一活跃路径。** `BrainModeSelector.decide` 已构造并注入 `TaskOrchestrator`，但其本地/远程裁决目前**仅记录日志**，`startNewTask` 中的远程委托是一个字面 `TODO`（见 `TaskOrchestrator.kt`）。"出站任务委托给 Runtime"尚未接线；真正活跃的远程方向是**入站**——母体经 WebSocket 下发 `tool/execute`（走 `withUntrustedSource{}` 安全闸门）。参见 README 实现状态表。
7. Agent 循环：构建系统提示词 → 调用 LLM → 提取工具调用 → `ToolRegistry.executeTool`（经过安全管线）→ 结果反馈 → 上下文压缩 → 死循环检测 → 直到 `finish` 或达上限
8. 结果通过同一渠道回复用户；触发自进化反思 + 记忆提取

---

## 3. 目录结构

```
octopus-mobile/
├── app/                              # Android 主模块
│   ├── src/main/java/com/apk/claw/android/
│   │   ├── ClawApplication.kt        # Application 入口
│   │   ├── AppViewModel.kt           # 全局 ViewModel（组件编排）
│   │   ├── TaskOrchestrator.kt       # 任务编排器
│   │   ├── OctopusConnectionManager.kt
│   │   ├── OctopusLifecycleManager.kt
│   │   ├── agent/                    # Agent 循环 + LLM 客户端
│   │   ├── tool/                     # 工具抽象层 + 注册中心 + 实现
│   │   ├── channel/                  # 6 种消息渠道
│   │   ├── octopus_mobile/           # 触手 RPC 层（远程控制/自进化/安全）
│   │   ├── service/                  # 无障碍/前台/保活/定时服务
│   │   ├── account/                  # 账号/计费/LLM 路由
│   │   ├── server/                   # 局域网配置 HTTP 服务器
│   │   ├── shizuku/                  # Shell 级权限增强
│   │   ├── media/                    # 媒体扫描/播放/网盘
│   │   ├── navigation/               # UI 导航知识图谱
│   │   ├── plugin/                   # DexClassLoader 插件
│   │   ├── cast/                     # 外接屏/投屏
│   │   ├── floating/                 # 悬浮球/直播控制条
│   │   ├── base/                     # BaseActivity/BaseApp
│   │   ├── utils/                    # 工具类（KVUtils/XLog/...）
│   │   ├── widget/                   # 自定义 UI 组件
│   │   └── ui/                       # Activity（splash/home/settings/...）
│   ├── src/main/assets/              # skills/*.md + web 控制台 HTML/JS
│   ├── src/main/res/                 # 资源（布局/drawable/values/多语言）
│   └── src/test/                     # 单元测试
├── server/                           # Python FastAPI 后端（账号/计费/中转）
├── examples/                         # 协议层 demo
├── scripts/dev-windows/              # Windows 开发辅助脚本
├── gradle/libs.versions.toml         # 依赖版本目录
└── build.gradle.kts                  # 根构建脚本
```

---

## 4. 主要模块职责

### 4.1 `agent/` — Agent 核心引擎

负责 LLM 驱动的自主任务执行循环。

| 文件 | 职责 |
|------|------|
| `AgentService.kt` | Agent 接口契约：`initialize / executeTask / cancel / shutdown` |
| `DefaultAgentService.kt` | 核心实现：observe→think→act→verify 循环、重试、压缩、死循环检测、VLM 弹窗处理 |
| `AgentConfig.kt` | 配置数据类 + `LlmProvider` 枚举 + Builder |
| `AgentCallback.kt` | 循环事件回调接口 |
| `AgentServiceFactory.kt` | 工厂单例 |
| `TaskQueue.kt` | 优先级任务队列（4 级优先级 + 抢占） |
| `llm/LlmClient.kt` | 后端无关 LLM 接口 |
| `llm/LlmClientFactory.kt` | 按 provider 选择 OpenAI / Anthropic |
| `llm/OpenAiLlmClient.kt` / `AnthropicLlmClient.kt` | LangChain4j 适配器 |
| `langchain/LangChain4jToolBridge.java` | BaseTool → ToolSpecification 转换 |
| `langchain/http/OkHttpClientBuilderAdapter.java` | OkHttp 替代 JDK HttpClient（Android 兼容） |

### 4.2 `tool/` — 工具系统

定义 Agent 可调用的原子能力，并集中管控安全。

| 文件 | 职责 |
|------|------|
| `BaseTool.kt` | 工具抽象基类（参数解析、`wait_after` 钩子、双语描述） |
| `ToolParameter.kt` | 参数数据类（name/type/description/isRequired） |
| `ToolResult.kt` | 结果数据类（success/error/successWithImage） |
| `ToolRegistry.kt` | 单例注册中心 + 安全执行管线 |
| `impl/` | 工具实现（通用/手机/TV/浏览器/系统/文件/媒体） |

### 4.3 `channel/` — 消息渠道

将 6 种异构 IM SDK 统一为一致的接入抽象。

| 文件 | 职责 |
|------|------|
| `ChannelHandler.kt` | 统一渠道接口 |
| `ChannelManager.kt` | 单例注册表 + 消息分发 + 出站多路复用 |
| `ChannelSetup.kt` | 启动引导（读配置 → 注册监听 → 路由到 TaskOrchestrator） |
| `ChannelAccessControl.kt` | 发送者 ACL（TOFU 白名单） |
| `dingtalk/` `feishu/` `qqbot/` `discord/` `telegram/` `wechat/` | 各渠道适配器 |

### 4.4 `octopus_mobile/` — 触手 RPC 层

将设备变为 octopus-agent Runtime 的物理触手，是项目最庞大、最核心的扩展层。

| 子领域 | 关键文件 | 职责 |
|--------|----------|------|
| RPC 协议 | `Protocol.kt` `OctopusMobileClient.kt` | JSON-RPC 2.0 + WebSocket 通道 |
| 连接管理 | ~~`ConnectionStateMachine.kt`~~ ~~`StartupMode.kt`~~ | ⚠️ 均已删除：ConnectionStateMachine 文件从未存在(仅文档残留),StartupMode 枚举无生产调用方 |
| 决策层 | `BrainModeSelector.kt`(已废弃) `IntentClassifier.kt` | ⚠️ BrainModeSelector 路由裁决未接线,仅用于日志;IntentClassifier 仍活跃 |
| 本地 ReAct | `LightweightLlmClient.kt` `LightweightReAct.kt` | 无 LangChain 的轻量 ReAct 循环 |
| 工具分发 | `ToolCallDispatcher.kt` `RemoteActions.kt` | Runtime→设备 / 设备→远程设备 |
| 屏幕串流 | `ScreenStreamer.kt` `H264Decoder.kt` | 增量屏幕上报 + PC 远程桌面解码 |
| 设备发现 | `DeviceRegistry.kt` `DeviceDiscoveryManager.kt` `DeviceRemoteControl.kt` | LAN UDP 发现 + HTTP 控制 |
| 技能 | `SkillManifest.kt` `SkillExporter.kt` `SemanticSkillRanker.kt` | SKILL.md 解析/导出/语义排序 |
| 例程 | `RoutineStore.kt` `ActionRecorder.kt` `ActionCache.kt` `DemoRecorder.kt` | 录制-重放 + 参数化 |
| 自进化 | `evolution/EvolutionEngine.kt` `TurnScorer.kt` `LessonStore.kt` | B1/B2/B3 自改进 |
| 记忆 | `memory/MemoryStore.kt` `memory/ContextCompressor.kt` | 跨会话记忆 + 上下文压缩 |
| 安全 | `safety/SafetyGate.kt` `safety/PermissionModeManager.kt` `safety/CircuitBreaker.kt` 等 | 多层护栏 |
| 神经 | `nerves/EventBus.kt` `nerves/reflex/ReflexRouter.kt` | 事件总线 + 关键词反射 |
| 主动 | `proactive/NotificationRelayService.kt` `proactive/ProactiveRuleEngine.kt` | 状态触发自动行动 |
| 视觉 | `VisionAnalyzer.kt` `PopupDetector.kt` `GoalVerifier.kt` | VLM 分析/弹窗/目标校验 |

### 4.5 `service/` — 系统服务

| 文件 | 职责 |
|------|------|
| `ClawAccessibilityService.kt` | 设备交互核心：手势/UI树/截图/按键（Shizuku 优先 + 回退） |
| `ForegroundService.kt` | 前台保活通知服务 |
| `KeepAliveJobService.kt` | 15 分钟周期看门狗 |
| `BootReceiver.kt` | 开机自启 |
| `RoutineScheduler.kt` / `RoutineAlarmReceiver.kt` | 例程定时闹钟 |

### 4.6 `account/` — 账号与 LLM 路由

| 文件 | 职责 |
|------|------|
| `LlmRouting.kt` | **LLM 路由决策**：平台中转（默认）vs BYO 自带模型（会员）vs 本地回退 |
| `AccountRepository.kt` | 账号操作统一入口 |
| `HttpAccountGateway.kt` / `MockAccountGateway.kt` | 后端实现 / Mock |
| `AccountStore.kt` | MMKV 持久化会话 |
| `AccountConfig.kt` | 后端配置 + 模型分层（flash/premium） |

### 4.7 `server/` — 局域网配置服务器

基于 NanoHTTPD 的 :9527 HTTP 服务，支持 PC 浏览器配置设备。

| 文件 | 职责 |
|------|------|
| `ConfigServer.kt` | HTTP 服务（Token 鉴权、CORS、HTML/JSON 路由） |
| `ConfigServerManager.kt` | 生命周期管理（WiFi IP 绑定、端口重试） |
| `RemoteConsoleGateway.kt` | 出站 WebSocket 到官网（NAT 友好远程控制） |
| `routes/ChannelRouteHandler.kt` | `/api/channels` `/api/llm` 端点（脱敏） |
| `routes/DebugRouteHandler.kt` | debug 构建工具调试控制台 |

### 4.8 其他模块

| 模块 | 职责 |
|------|------|
| `shizuku/` | Shell 级权限增强（当前受限：`exec` 回退到 app 进程 `Runtime.exec`） |
| `media/` | 本地/WebDAV/网盘媒体扫描 + mpv 播放（mpv 当前为 stub） |
| `navigation/` | UI 导航知识图谱（录制 + A* 寻路 + 状态指纹） |
| `plugin/` | DexClassLoader 动态加载（fail-closed：仅信任 APK 内置 assets） |
| `cast/` | 外接屏 Presentation 工作台 |
| `floating/` | 悬浮球（语音+无障碍三合一）+ 直播控制条 |
| `base/` | BaseActivity（屏幕适配）+ BaseApp（全局 ViewModelStore） |
| `utils/` | KVUtils（MMKV+加密SP）+ XLog + KotlinEx |
| `server/`（根目录 Python） | FastAPI 后端：账号/计费/LLM 中转/远程控制台 |

---

## 5. 关键类与函数说明

### 5.1 入口与编排

#### `ClawApplication` — [ClawApplication.kt](app/src/main/java/com/apk/claw/android/ClawApplication.kt)
Application 入口。`initializeApp()` 完成全量初始化：
```kotlin
protected open fun initializeApp() {
    KVUtils.init(this)
    ToolRegistry.getInstance().registerAllTools(deviceType)  // 按设备类型注册工具
    ShizukuManager.init()
    initOctopusMobile()       // 初始化触手层
    RemoteConsoleGateway.connect()
    // 异步初始化 Agent（需 LLM 配置）
}
```
全局单例：`brainSelector`、`skills`（启动时加载 SKILL.md）。

#### `AppViewModel` — [AppViewModel.kt](app/src/main/java/com/apk/claw/android/AppViewModel.kt)
全局 ViewModel，编排所有 Octopus Mobile 组件生命周期。`initOctopusMobile()` 创建：
- `OctopusMobileClient` / `BrainModeSelector` / `ToolCallDispatcher`
- `HeartbeatReporter` / `ScreenStreamer` / `DualConfigWriter`
- `SafetyGate` / `TurnScorer` / `CircuitBreaker`（接入 ToolRegistry）
- `EvolutionEngine` / `LessonStore` / `MemoryStore`（接入 TaskOrchestrator）

`getAgentConfig()` 通过 `LlmRouting.effective()` 决定 LLM 后端，并注入教训/记忆后缀。

#### `TaskOrchestrator` — [TaskOrchestrator.kt](app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt)
任务编排器，持有 `TaskQueue` 与 `AgentService`。核心方法：
```kotlin
fun startNewTask(channel, task, messageID, priority, isBackground)
```
- 先尝试 `ReflexRouter.tryMatchText(task)` 关键词反射（命中直接执行工具，跳过 LLM）
- 否则入队 + 抢占调度 + `executeCurrentTask()`
- `executeCurrentTask()` 按 Home 重置 → `agentService.executeTask(task, callback, untrusted=true)`
- 回调实现消息聚合缓冲（thinking+toolResult 攒一条发送）
- 任务完成后触发 `triggerPostTaskReflect()`（B1 打分 + B2 反思）+ 记忆提取

### 5.2 Agent 引擎

#### `DefaultAgentService` — [agent/DefaultAgentService.kt](app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt)
Agent 循环核心。关键设计：

| 机制 | 实现 |
|------|------|
| 循环 | `runAgentLoop`：`while(shouldContinue) { iteration++; runSingleIteration }` |
| 系统提示词 | `buildInitialMessages`：systemPrompt + 设备上下文 + dynamicPromptSuffix（教训）+ memoryPromptSuffix（记忆） |
| LLM 重试 | `chatWithRetry`：3 次尝试，指数退避 1s→2s→4s + 抖动；401/403/quota 不重试 |
| 流式 | `config.streaming` 时 token 经 `StreamingListener.onPartialText` → `callback.onContent` |
| 工具执行 | `executeSingleTool`：弹窗预处理 → 状态快照 → `execTool`（untrusted 走来源闸门）→ 校验（相似度≥0.97 提示未生效） |
| 上下文压缩 | `compressHistoryForSend`：仅保留最近 `get_screen_info`，保护最近 3 轮，旧轮摘要；超长再截断 |
| 死循环检测 | 4 轮滑动窗口 `(screenHash, toolCall)` 指纹；3 次警告强制结束 |
| 弹窗处理 | `get_screen_info` 返回 `SYSTEM_DIALOG_BLOCKED` → VLM 截图分析（max 720px, JPEG 50%） |
| 完成条件 | `finish` 工具成功 → `onComplete`；达 `maxIterations`(默认 80) → `onError` |

```kotlin
private fun AgentLoopState.executeSingleTool(toolRequest, callback): ToolHandleResult {
    // ... 弹窗预处理 + 校验快照
    val rawResult = execTool(toolName, params)   // untrustedRun → withUntrustedSource
    val result = if (uiAction && rawResult.isSuccess) {
        // 校验：操作前后状态相似度 ≥ 0.97 则提示"可能没生效"
    } else rawResult
    if (toolName == "finish" && result.isSuccess) {
        callback.onComplete(iterations, result.data, totalTokens); return TERMINATE
    }
    recordFingerprint(toolName, toolArgs, result)  // 死循环检测
    appendToolResult(toolRequest, result)
}
```

#### `AgentConfig` — [agent/AgentConfig.kt](app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt)
```kotlin
data class AgentConfig(
    val apiKey: String, val baseUrl: String, val modelName: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 80, val temperature: Double = 0.1,
    val provider: LlmProvider = LlmProvider.OPENAI,
    val streaming: Boolean = false,
    val dynamicPromptSuffix: String = "",   // 教训注入
    val memoryPromptSuffix: String = "",    // 记忆注入
    val enableVision: Boolean = true,       // VLM 弹窗分析
)
```

#### `TaskQueue` — [agent/TaskQueue.kt](app/src/main/java/com/apk/claw/android/agent/TaskQueue.kt)
优先级队列（`PriorityBlockingQueue`，max 10），`TaskPriority {LOW,NORMAL,HIGH,URGENT}`。
- `shouldPreempt(newTask, currentTask)`：仅前台任务间、新任务严格更高优先级才抢占
- 抢占：`pauseCurrentTask` → 重建 AgentService → 通知被取消

#### `LlmClientFactory` — [agent/llm/LlmClientFactory.kt](app/src/main/java/com/apk/claw/android/agent/llm/LlmClientFactory.kt)
```kotlin
fun create(config: AgentConfig): LlmClient = when (config.provider) {
    LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
    LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
}
```
两个客户端结构一致，仅 LangChain4j builder 不同。流式用 `CountDownLatch` 阻塞至完成。

### 5.3 工具系统

#### `BaseTool` — [tool/BaseTool.kt](app/src/main/java/com/apk/claw/android/tool/BaseTool.kt)
```kotlin
abstract class BaseTool {
    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: Map<String, Any>): ToolResult
    abstract fun getDescriptionEN(): String
    abstract fun getDescriptionCN(): String
    // getParametersWithWaitAfter(): 动作工具追加 wait_after 参数（上限 10s）
    // executeWithWaitAfter(): execute() + 成功后 sleep
    // 参数解析助手: requireString/requireInt/optionalLong/validateCoordinates
}
```

#### `ToolRegistry` — [tool/ToolRegistry.kt](app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt)
单例。`registerAllTools(DeviceType)` 按设备类型注册：
- 通用：`get_screen_info` `find_node_info` `take_screenshot` `input_text` `open_app` `press_back/home` `finish` 等
- 手机：`tap` `long_press` `swipe` `scroll_to_find` `click_by_text/id`
- TV：D-pad `dpad_up/down/left/right/center` `press_menu` `volume_up/down`
- 浏览器：`browser_navigate/click/type/get_dom/screenshot/evaluate/install_extension`
- 系统：`launch_freeform` `resize_window` `file_ops` `browse_files` `search_files` `backup_app` `navigate` `media_player` `look_at_screen` `tap_by_vision`

**`executeTool(name, params)` 安全管线（按顺序）**：
```
1. 工具存在性 + 风险评级
2. 用户禁用检查
3. 断路器（60s 窗口 10 失败/60 调用 → 熔断 30s）
4. 权限策略（APPROVAL vs FULL_POWER）
5. 来源信任闸门 + 审批流（高危工具 + untrusted 来源）
6. SafetyGate（PII/密钥正则 + LLM 宪法裁判）
7. Guardrail 预检（重复失败/无进展）
8. 执行 BaseTool.executeWithWaitAfter
9. Guardrail 观察（失败时追加警告）
10. 断路器记录 + TurnScorer 记录 + 审计日志
```

#### 关键工具实现

| 工具文件 | 工具 | 说明 |
|----------|------|------|
| `impl/HelloWorldTools.kt` | `hello_world` `current_time` `device_info` | 示例工具（教学） |
| `impl/mobile/VisionMarkersTool.kt` | `tap_by_vision` | Set-of-Marks 视觉定位：截图叠加编号框 → VLM 选号 → 精确点击节点中心 |
| `impl/LookAtScreenTool.kt` | `look_at_screen` | 截图送 VLM 分析（游戏/画布等无障碍树失效场景） |
| `impl/NavigateTool.kt` | `navigate` | UI 导航知识图谱：录制/A*寻路/状态检测/重规划 |
| `impl/browser/BrowserTools.kt` | `browser_*` (7个) | 系统 WebView 浏览器自动化（URL scheme 白名单 + UrlGuard SSRF 防护） |
| `impl/FileOpsTool.kt` | `file_ops` | 文件操作（PathGuard 限制 /sdcard 沙箱） |
| `impl/MediaTools.kt` | `media_player` | mpv 控制 + 媒体扫描 + 网盘（mpv stub 时播放类报错） |
| `impl/EchoUniverseTools.kt` | `echo_observe/act/bind` | Echo 虚拟世界感知/行动 |

### 5.4 渠道系统

#### `ChannelHandler` 接口 — [channel/ChannelHandler.kt](app/src/main/java/com/apk/claw/android/channel/ChannelHandler.kt)
```kotlin
interface ChannelHandler {
    val channel: Channel
    fun init(); fun disconnect(); fun reinitFromStorage()
    fun sendMessage(content: String, messageID: String)
    fun sendImage(imageBytes: ByteArray, messageID: String)
    fun flushMessages() {}
    fun getLastSenderId(): String? = null       // ACL 稳定标识（非 messageID）
    fun restoreRoutingContext(targetUserId: String) {}  // 定时任务回推
}
```

#### `ChannelAccessControl` — [channel/ChannelAccessControl.kt](app/src/main/java/com/apk/claw/android/channel/ChannelAccessControl.kt)
TOFU 白名单：首个发送者自动绑定为 owner；后续未在白名单者 `DENY`（fail-closed）。这是防止陌生人 DM 控制手机的安全闸。

### 5.5 触手 RPC 层

#### `OctopusMobileClient` — [octopus_mobile/OctopusMobileClient.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/OctopusMobileClient.kt)
WebSocket 客户端。`connect()` 发送 `device/hello`（含设备元数据）。入站分发：
- `tool/execute` → `onToolExecute` 回调（`ToolCallDispatcher` 消费）
- `task/result` / `task/error` → 完成 `pendingTasks` 的 `CompletableDeferred`
- `config/sync_pull_response` → `DualConfigWriter` 处理
- 二进制帧 → `onPcFrame`（PC 远程桌面）

出站 `executeRemoteTask(task)`：60s 超时等待结果。断连时 `failPendingTasks` 立即失败所有在途任务。

#### `ToolCallDispatcher` — [octopus_mobile/ToolCallDispatcher.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/ToolCallDispatcher.kt)
Runtime → 设备工具执行：`stripAndroidPrefix`（`android.tap`→`tap`）→ `ToolRegistry.withUntrustedSource { executeTool }`（Runtime 来源视为 untrusted）→ `sendToolResult`。

#### `BrainModeSelector` — [octopus_mobile/BrainModeSelector.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/BrainModeSelector.kt) — ⚠️ 已废弃
30s 健康检查：Runtime `ONLINE` → `EXECUTOR_ONLY`；否则 `LOCAL_FALLBACK`。`decide(task)`：
1. `IntentClassifier.classify`（关键词 BROWSER/MOBILE/MIXED）
2. 浏览器域自动选择 `BrowserEngineFactory.selectBest`
3. 远程：`rpcClient.executeRemoteTask`；失败降级本地
4. 本地：`LightweightReAct.run`（轻量 ReAct 循环）

> ⚠️ **已废弃（PROJECT_ANALYSIS P2 死代码清理,2026-07）**：上述 `decide(task)` 路由流程从未真正接线。`BrainModeSelector` 被 AppViewModel 实例化、被 TaskOrchestrator 读取 `currentMode()`/`currentDomain()`,但读取结果仅用于日志输出,`startNewTask` 中的远程委托是字面 `TODO`。类已加 `@Deprecated` 注解,保留以避免破坏编译。

#### `LightweightReAct` — [octopus_mobile/LightweightReAct.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/LightweightReAct.kt)
无 LangChain 的 ReAct 循环：语义排序技能 → 每步压缩历史 → LLM 调用 → 工具执行 → 4 轮指纹死循环检测 → `GoalVerifier` VLM 目标校验（fail-open，1 次修复机会）。

#### `DualConfigWriter` — [octopus_mobile/DualConfigWriter.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/DualConfigWriter.kt)
MMKV ↔ Runtime 配置双写。**安全 blocklist**：Runtime URL/authToken/LLM key/渠道密钥/安全策略开关永不接受远程写入（防恶意 Runtime 改写端点）。

#### `EvolutionEngine` — [octopus_mobile/evolution/EvolutionEngine.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/evolution/EvolutionEngine.kt)
三层自进化：
- **B1**（免费，✅ 活跃）：`TurnScorer` 启发式打分 + 趋势
- **B2 deepReflect**（廉价，✅ 活跃）：单次 LLM 调用评估近 N 轮 → JSON 裁决 → 自动存教训
- **B3 deepEvolve**（昂贵，手动）：提 K 候选 → LLM 评判 → 应用胜出者
  > ⚠️ **B3 已废弃（PROJECT_ANALYSIS P2 死代码清理,2026-07）**：`deepEvolve` 函数在 EvolutionEngine.kt 中**从未存在**(历史文档误称,实际仅有 `deepReflect`)。`CanaryManager` 已加 `@Deprecated`,灰度晋级写入端从未接线,仅 `EvolutionActivity` 只读调用 `listAll()`。只有 B1/B2 的教训闭环是真正活跃的。

教训写入 `LessonStore`，经 `TaskOrchestrator` 注入 `AgentConfig.dynamicPromptSuffix`。

### 5.6 服务层

#### `ClawAccessibilityService` — [service/ClawAccessibilityService.kt](app/src/main/java/com/apk/claw/android/service/ClawAccessibilityService.kt)
设备交互核心（单例 `getInstance()`）：
- 手势：`performTap/LongPress/Swipe`（Shizuku 优先，回退 `dispatchGesture` + `CountDownLatch`）
- UI树：`screenTree`（过滤有意义节点）/ `screenTreeFull` / `findNodesByText/Id` / `clickNode`
- 截图：`takeScreenshot`（API 30+ `AccessibilityService.takeScreenshot`，回退 Shizuku `screencap`）
- 全局动作：`pressBack/Home` `openRecentApps` `lockScreen` `unlockScreen`
- 事件转发：`onAccessibilityEvent` → `ScreenStreamer.dispatchEvent` + `DemoRecorder.ingest`

### 5.7 账号与 LLM 路由

#### `LlmRouting` — [account/LlmRouting.kt](app/src/main/java/com/apk/claw/android/account/LlmRouting.kt)
```kotlin
fun effective(): EffectiveLlm
```
决策优先级：
1. **BYO**：`modelSource=="byo"` && 会员有效 && 积分耗尽 && 已配置自有 key
2. **平台中转**（默认）：`relayConfigured` && 已登录 → token 作 API key，服务端扣积分
3. **本地回退**：relay 未配置/未登录 → 用 `KVUtils` 本地 LLM 配置（不破坏现有行为）

---

## 6. 依赖关系

### 6.1 模块间依赖

```
ClawApplication
  ├─→ AppViewModel ──→ TaskOrchestrator ──→ AgentService ──→ LlmClient
  │        │              │                    │               │
  │        │              ├─→ ReflexRouter     └─→ ToolRegistry
  │        │              ├─→ EvolutionEngine        │
  │        │              └─→ MemoryStore            ├─→ SafetyGate/Guardrail/CircuitBreaker
  │        ├─→ OctopusMobileClient ─→ ToolCallDispatcher ─→ ToolRegistry
  │        ├─→ BrainModeSelector ─→ LightweightReAct ─→ ToolRegistry
  │        ├─→ ChannelSetup ─→ ChannelManager ─→ 6×ChannelHandler
  │        └─→ ConfigServerManager ─→ ConfigServer
  ├─→ ToolRegistry ──→ ClawAccessibilityService / ShizukuShellService
  ├─→ ShizukuManager
  └─→ ForegroundService / KeepAliveJobService / BootReceiver
```

### 6.2 关键依赖（`gradle/libs.versions.toml`）

**AI / Agent**
| 依赖 | 版本 | 用途 |
|------|------|------|
| LangChain4j (core/openai/anthropic) | 1.12.2 | Agent 编排、工具定义、LLM 集成 |

**消息渠道**
| 依赖 | 版本 | 用途 |
|------|------|------|
| DingTalk Stream Client | 1.3.12 | 钉钉渠道 |
| Feishu OAPI SDK | 2.5.3 | 飞书渠道 |

**网络**
| 依赖 | 版本 | 用途 |
|------|------|------|
| OkHttp | 4.12.0 | HTTP 客户端（LLM/RPC/渠道） |
| Retrofit | 2.11.0 | REST API 客户端 |
| NanoHTTPD | 2.3.1 | 局域网配置 & 调试服务器 |
| ok2curl | — | curl 日志 |

**存储 & 工具**
| 依赖 | 版本 | 用途 |
|------|------|------|
| MMKV | 2.3.0 | 高性能 KV 存储 |
| Gson | 2.13.2 | JSON 序列化 |
| ZXing | 3.5.3 | 二维码 |
| UtilCode | 1.31.1 | Android 工具函数 |
| security-crypto | — | EncryptedSharedPreferences |

**系统能力**
| 依赖 | 版本 | 用途 |
|------|------|------|
| ~~GeckoView~~ | ~~151.0~~ | **已移除**(瘦 -180MB),改用系统 WebView(Chromium 内核,0 包体) |
| Shizuku (api/provider) | 13.1.5 | Shell 级权限增强 |

**UI**
| 依赖 | 版本 | 用途 |
|------|------|------|
| Jetpack Compose (BOM) | — | 现代 UI |
| Glide | 5.0.5 | 图片加载 |
| Coil Compose | — | Compose 图片 |
| EasyFloat | 2.0.4 | 悬浮窗 |
| MultiType | 4.3.0 | RecyclerView 多类型 |

**测试**
| 依赖 | 用途 |
|------|------|
| JUnit / Robolectric / MockWebServer / Mockito | 单元测试 |

### 6.3 后端依赖（`server/requirements.txt`）
Python FastAPI + Uvicorn + httpx + sqlite3（标准库）。无 PyJWT（自实现 HS256）。

---

## 7. 项目运行方式

### 7.1 环境要求

- **JDK 17+**
- Android Studio（Ladybug+）
- Android SDK 36（编译/目标），min SDK 28（Android 9+）
- （可选）Shizuku App 用于 shell 级增强

### 7.2 编译

```bash
git clone https://github.com/octopus-agent/octopus-mobile.git
cd octopus-mobile

# Debug 构建（按 ABI 分包：arm64-v8a / armeabi-v7a）
./gradlew assembleDebug

# Release 构建（需 local.properties 配置签名）
./gradlew assembleRelease
```

产物：`OctopusMobile_v0.0.7_<abi>_<timestamp>.apk`

### 7.3 安装与配置

1. **安装** APK 到 Android 9+ 设备
2. **授权**（首页依次开启）：
   - 无障碍服务（`ClawAccessibilityService`）
   - 通知权限、悬浮窗、电池白名单、文件访问
   - 通知监听（主动 Agent，可选）
3. **配置 LLM**（设置 > LLM Config）：
   - 方式 A：登录平台账号 → 使用平台中转（默认，扣积分）
   - 方式 B：自带 API Key（BYO，需会员）→ 填 baseUrl / apiKey / model
4. **配置渠道**：至少一个 IM 渠道（钉钉/飞书/QQ/Discord/Telegram/微信）
5. **发送消息**即可控制设备

### 7.4 局域网配置（PC 浏览器）

设置中开启 LAN Config → PC 访问 `http://<设备IP>:9527/?token=< authToken>`：
- `/api/channels` GET/POST：读取/更新渠道凭证（GET 脱敏）
- `/api/llm` GET/POST：读取/更新 LLM 配置
- `/console`：Web 聊天控制台（debug 构建额外提供 `/debug.html`）

### 7.5 远程控制台（官网）

1. 官网登录 → 远程控制台 → 生成配对码
2. App 设置 → 设备控制 → 输入配对码认领
3. App 主动连接 `wss://域名/remote/device/ws`
4. 官网控制台通过 WebSocket 转发指令（tap/swipe/text/key 等）

### 7.6 触手模式（连接 octopus-agent Runtime）

设置 Runtime URL（`ws://<host>:8765`）+ Auth Token → `OctopusMobileClient` 自动连接：
- 设备上报：心跳（30s）、屏幕变化（增量 hash）、技能列表
- Runtime 下发：`tool/execute`（远程工具调用）、`config/sync_pull`（配置同步）
- 模式：DUAL（默认，离线降级）/ LOCAL_ONLY / RPC_ONLY

### 7.7 单元测试

```bash
./gradlew :app:testDebugUnitTest

# 指定测试
./gradlew :app:testDebugUnitTest --tests "*HelloWorldToolsTest*"
```
覆盖：AgentConfig、DefaultAgentService、TaskQueue、IntentClassifier、ToolRegistry、ShizukuShellService、ConfigServer 等（ConnectionStateMachine 引用为文档残留,文件从未存在）。

### 7.8 后端部署（可选）

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # 填 MIMO_API_KEY 等
uvicorn app:app --host 127.0.0.1 --port 8081
# nginx 反代 80/443 → 127.0.0.1:8081
```
默认全 Mock，本地直接跑通；生产配 `MIMO_API_KEY` / 支付 / 短信 env。

---

## 8. 实现状态速查

| 模块 | 状态 | 备注 |
|------|------|------|
| Agent 循环 / 压缩 / 死循环检测 | ✅ 完整 | 核心链路稳定 |
| 安全护栏 + SafetyGate | ✅ 已接入 | 每次工具调用前置预检 + 结果观察 |
| 消息渠道（6 种） | ✅ 完整 | 优先级队列 + 抢占 |
| 自进化 L1/L2 | ✅ 运行中 | 教训持久化注入提示词 |
| 触手 RPC 层 | ✅ 启动全量接线 | ClawApplication → initOctopusMobile() |
| 无障碍服务 | ✅ 完整 | 节点 recycle / latch 待优化 |
| LLM 路由（平台/BYO/本地） | ✅ 完整 | LlmRouting.effective() |
| 浏览器自动化 | ✅ 完整 | 系统 WebView(Chromium 内核),evaluateJavascript / click / type / get_dom / screenshot 均可用 |
| Shizuku shell 提权 | ⚠️ 受限 | exec 回退 app 进程；需 IUserService 绑定才能 shell UID |
| 投屏 / 外接屏工作台 | ⚠️ 可用不完整 | 检测/渲染/REST 通；窗口跟踪未实现 |
| 自进化 L3 deepEvolve / Canary | ⚠️ 已废弃 | deepEvolve 函数从未存在;CanaryManager 已加 @Deprecated,仅 EvolutionActivity 只读展示 |
| 插件系统 | 💤 休眠 | 仅 assets 内置可加载；外部 dex fail-closed |
| mpv 媒体播放 | ⚠️ 已废弃(@Deprecated) | Stub: IS_AVAILABLE=false,所有方法 noop;MediaTools/PlayerActivity 仍引用以保持编译 |

**图例**：✅ 完整可用 · ⚠️ 部分可用/受限 · 💤 已实现未接线 · 🔴 占位待实现

---

> 本 Wiki 基于代码现状生成，与 [README.md](README.md) / [README_CN.md](README_CN.md) 互为补充。详细扩展指引见 [EXTENDING.md](EXTENDING.md)，快速上手见 [START_HERE.md](START_HERE.md)。
