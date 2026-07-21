# Octopus Mobile 项目分析报告

> 综合 10 份子系统分析与 4 份横切审计的权威评估。结论以当前**未提交工作树**为准 —— 多项安全修复处于已修改未提交状态,独立的 `AUDIT_REPORT.md`(70 项发现)在若干关键点上已经**过时**,本报告对此做了显式校正。
>
> **方法与边界(已核验):** 本报告基于**纯静态代码阅读**(文件/行号/调用图),未运行 App、未跑测试套件(50 个 JVM 单测计为名义值,未确认在当前未提交树上全绿)。"无生产调用方/死代码"类结论以 grep 调用图为据,不排除反射/DI 间接调用的极小可能。以下三处经主控二次核验并已校正:① 规模口径(见下表);② **FULL_POWER 默认是关的** —— `KVUtils.isAdvancedAutomationMode()` 默认 `false`(`KVUtils.kt:441`),须用户在 TrustCenter 手动开启,代码注释将其标注为"闲置/群控机"用途,但它**并非默认部署形态**,故 P0-1/P0-3 仅在用户主动开启后生效;③ 入站母体 `tool/execute` **确经** `withUntrustedSource{}` 闸门(`ToolCallDispatcher.kt:110`),因此默认 APPROVAL 模式下 P0-2 的 MITM 注入会被高危来源闸门拦截 —— P0-2 与 P0-1 是**叠加关系**(只有开了 FULL_POWER 才真正失守)。

---

## 1. 一句话定位 + 项目概览

**一句话定位:** Octopus Mobile 是一款"AI 驱动的 Android 全自动化操控应用"—— 通过 LLM ReAct 智能体 + 无障碍服务 + Shizuku shell 提权,把手机变成一只可被本地大脑、远程"母体(octopus-agent)"或任意 IM 聊天平台命令驱动的"触手(tentacle)",并自带一套服务端账号/积分/会员/LLM 中转计费后端。

| 维度 | 现状 |
|---|---|
| **规模** | 实测:Kotlin 全量 **56,908 LOC**(main 49,734 + test 7,174)+ Java 遗留 **5,882 LOC** + Python 中转服务端单文件 **2,442 LOC** ≈ 65k 总行 / 573 个 git 跟踪文件 |
| **技术栈** | Kotlin 2.1.20 / Jetpack Compose(混 ~36 个遗留 Activity)/ LangChain4j over OkHttp / NanoHTTPD / MMKV / Shizuku 13.1.5 / 系统 WebView(Chromium 内核,0 包体) / FastAPI + SQLite |
| **工具链** | AGP 9.1.0 + Gradle 9.3.1(前沿大版本)、compileSdk/targetSdk 36、minSdk 28、版本目录、CI + CodeQL + Dependabot |
| **测试** | 50 个真实 JVM 单测(~571 @Test / ~1,220 断言)+ pytest 116 函数(服务端money-path) |
| **整体成熟度** | **developing(发展中,偏向成熟)** —— 核心决策层(智能体循环、工具系统、安全闸门、计费)质量高且经过清理;但大量"移植自桌面母体"的子系统是**已实现但未接线(dead/aspirational)**的占位代码,文档叙事与实际接线之间存在系统性落差 |

**总体判断:** 一个工程密度高、安全意识在持续补强、但"宣传面 > 实际接线面"的成熟原型。可作为个人/可信网络下的自用工具运行,距离无人值守/群控生产部署仍有明确的安全与可靠性缺口。

---

## 2. 整体架构评估

### 分层与关键设计决策

```
┌─────────────────────────────────────────────────────────────────────┐
│  入口层(3 个控制入口汇聚到同一执行基座)                              │
│  ① 本地/UI:Compose Shell → ChatAgentBridge                           │
│  ② IM 通道:6 平台 → ChannelManager → ChannelAccessControl(ACL)      │
│  ③ 远程母体:WebSocket(ws://)→ OctopusMobileClient                  │
│  ④ LAN HTTP:ConfigServer :9527(/console /mcp /api/control)          │
└───────────────────────────────┬─────────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  编排层  AppViewModel(组合根/上帝对象)→ TaskOrchestrator             │
│          TaskQueue(优先级 + 抢占)· ReflexRouter(关键词零-LLM 快路)  │
└───────────────────────────────┬─────────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  智能体层  DefaultAgentService(Observe→Think→Act→Verify,1004 LOC)   │
│           LlmClient(OpenAI 兼容 / Anthropic)· 上下文压缩 · 死循环检测 │
│           · VLM 目标自校验 · 崩溃恢复检查点                            │
└───────────────────────────────┬─────────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ★ 唯一受控收口 ★  ToolRegistry.executeTool(~150 LOC,7 道闸门串联) │
│  enable→断路器→权限策略→[不可信源×高危]闸门→SafetyGate→guardrail→审计 │
└───────────────────────────────┬─────────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  执行基座  ClawAccessibilityService(手势/节点树/截屏)               │
│           ShizukuShellService(shell-UID 提权,1041 LOC)              │
│  持久化   ForegroundService(自重启)· KeepAlive · Boot · 定时器       │
└─────────────────────────────────────────────────────────────────────┘
```

### 三种启动模式(关键真相:设计 vs 实际)

设计上 `StartupMode` 定义了 **LOCAL_ONLY / RPC_ONLY / DUAL** 三模式,但:

- `StartupModeResolver.resolve()` 与 `ClawApplication.isRuntimeReachable()` **零生产调用方**(仅单测 + 注释引用),属死代码/愿景代码。
- 实际的"本地 vs 远程大脑"切换由 **`BrainModeSelector`** 承担(每 30s 健康检查),但 **`BrainModeSelector.decide()` 同样无生产调用方** —— `TaskOrchestrator.startNewTask` 只记日志,远程委派是一个字面 `TODO`(`TaskOrchestrator.kt:307`)。
- **唯一真正活跃的远程方向是"入站"**:母体经 WebSocket 下发 `tool/execute`,经 `ToolCallDispatcher` 走 `withUntrustedSource{}` 安全闸门执行。"出站任务委派给母体"的故事尚未实现。

**结论:头条卖点"本地/远程大脑无缝切换"在生产任务路径上是愿景态;只有入站远程工具控制是真实可用的。**

### 数据/控制流要点

- 入站任务:`channel 消息 → ACL 授权 → TaskOrchestrator.startNewTask → ReflexRouter 快路(命中则零 LLM)→ 否则 DefaultAgentService 循环`。
- 抢占模型实为"取消并重跑":`pauseCurrentTask` 会 `shutdown()` 智能体并发送"任务已取消"文案,恢复时从零重启,**无上下文保留**。
- 关键设计风险:`AppViewModel.initOctopusMobile()`(~110 行单 try/catch)构造 ~15 个子系统并向 `ToolRegistry` 注入 6 个全局可变静态(safetyGate/turnScorer/circuitBreaker/eventBus/guardrail)—— **接线顺序即承重墙**,任一处失败只记日志,系统静默降级运行。

---

## 3. 子系统健康度矩阵

| 子系统 | 成熟度 | 优势 | 主要隐患 |
|---|---|---|---|
| **App 编排与生命周期** | developing | 调度并发安全(单 `scheduleLock`、`isCurrentCallbackTask` 丢弃陈旧回调);always-on 加固(wake lock/前台服务/网络恢复重连) | `initOctopusMobile()` 上帝方法半接线静默降级;StartupMode 死代码误导;pause/resume 实为 cancel/re-run |
| **智能体循环 & LLM 客户端** | **mature** | 智能重试分类(空响应作可重试);三重防重复动作(幂等/屏幕未变/指纹循环);VLM 目标自校验 fail-open;崩溃检查点 | **流式 `latch.await()` 无超时**(模型半挂起→单线程永久阻塞);两个 LLM 客户端 95% 重复;VLM 校验在执行线程 `runBlocking` 阻塞且不可取消 |
| **工具系统 & 注册表** | **mature** | 唯一受控分发收口(所有路径必经);源信任模型真实接线;`PathGuard`/Shizuku 白名单纵深防御;审计 + 脱敏 | **风险分类是手维护硬编码名单,静默漂移**(`install_app` 死项;新工具默认 LOW = 既不过高危闸门也不审计);无结构化参数校验 |
| **IM 通道层(6 平台)** | developing | 统一 `ChannelHandler` 抽象;ACL 位置正确(授权先于 `startNewTask`)、null 发送者 fail-closed;通道故障隔离 | **ACL 身份模型脆弱**:群聊按会话(非按人)授权 → 群内任意成员可控设备;TOFU 首发者夺权;`lastSenderId` TOCTOU 竞态;消息正文明文 INFO 日志(可能含 OTP) |
| **触手核心(远程控制/routines/skills)** | developing | 入站远程控制真实且安全闸门化;重连全抖动退避;Routines/ActionCache 按锚文本而非死坐标重放、安全回退 | **`BrainModeSelector.decide` 整条本地/远程路由无生产调用方**;握手从任意入站帧推断 ONLINE(无真 hello-ack);`ConnectionStateMachine` 完整却完全未用;出站 JSON 手写字符串拼接(Phase 0 占位) |
| **屏幕流 & 媒体采集** | developing | `H264Decoder` 手写却健壮(完整 SPS 解析、有界背压、native-crash 安全 stop);采集侧资源回收严谨;MJPEG 有界(Semaphore 2) | 单一共享 100ms 节流导致截图/流互相 503;`ScreenStreamer.treeDelta` **名不副实**(每次全量树);`pendingEvent` 死字段且持有已回收事件;全部像素经无障碍截屏(被 OEM ~1/s 限流);`MpvController` 确认死桩 |
| **自进化/神经/主动引擎** | developing | lesson 路径是唯一全闭环(TurnScorer 真实接每次工具调用 → 注入系统提示词);成本纪律好(B1 零-LLM、B2 双门限、B3 手动+dryRun);主动引擎尊重安全层且默认关 | **头条"自动复制短信验证码"双重损坏**(工具名 `set_clipboard` 错→应为 `clipboard`,且 text="");`CanaryManager` 整体死代码;`deepEvolve`(B3 自我改进)无调用方;`onSms/Battery/Foreground` 均无触发 |
| **安全 & 权限闸门** | developing | **真实单一收口**且每道闸门确实被调用;`PathGuard`/`CircuitBreaker`/HIGH_RISK 阻断已接线(审计的"死代码"结论已过时);ApprovalFlow fail-closed;审计日志 HMAC 签名防篡改 | **`SafetyGate` 以 judge=null 构造**(LLM 宪法判官从不运行);**FULL_POWER 模式一键关闭 4 道防护**且可远程配置翻转;`UrlGuard` 仅接 1 处(NavigateTool 仍可 SSRF 到本机 :9527);中危工具从不过源闸门 |
| **Python 中转服务 & 账号计费** | developing | 预扣额度模型真防并发超扣(`BEGIN IMMEDIATE`+条件 UPDATE);Stripe webhook 正确加固(HMAC+重放窗+幂等);密钥服务端保管、JWT 强制;money-path pytest 覆盖最佳 | **账本不平**:gift/daily 额度消费/退款不写 `credit_transactions`,审计无法对账;单文件单 worker 进程内态(限流/中继 multi-worker 失效);SQLite 为金钱状态唯一存储,扩展硬天花板 |
| **UI/服务/无障碍/Shizuku** | developing | 执行基座是真正的"手";Shizuku 优先(同步、穿透 FLAG_SECURE);ConfigServer token 门控 + 常量时间比较;前台服务激进自重启 | **Shizuku shell-UID 提权是最大风险集中点**;**注入过滤器自相矛盾**(`getStorageOverview`/`findDuplicateFiles` 含 `&&`/`;` 被自身过滤器永久拒绝→功能形同虚设);审计源取自可伪造的 `X-Forwarded-For`;UI 双架构(Compose + ~36 遗留 Activity);`ChatScreen.kt` 1743 LOC 上帝屏 |

---

## 4. 核心亮点(真正建得好的部分)

1. **唯一受控工具收口 + 真实源信任模型。** `ToolRegistry.executeTool` 是所有执行路径(智能体/LAN/MCP/调试/主动规则)的必经收口,7 道闸门逐一真实调用,新增调用方无法绕过。6 个不可信入口全部以 `withUntrustedSource{}`(ThreadLocal 深度计数,finally 还原)正确包裹 —— 这是整个项目最扎实的工程。

2. **智能体循环的反脆弱设计。** 三重防重复动作(幂等警告 / `StateDetector` 屏幕相似度≥0.97 / `RoundFingerprint` 指纹循环检测)叠加;空响应作可重试错误而非误判完成;VLM 目标校验 fail-open;每轮崩溃检查点 —— 对一个 OS 随时可能 kill 的移动智能体是真正的差异化能力。

3. **Routines / ActionCache 的稳健回放。** 存锚文本/viewId 而非死坐标,任一步无法重定位即回退到完整智能体 —— 快路只会更快、绝不误点。是触手子系统中最成熟、UI 全接线、可纯 JVM 单测的部分。

4. **手写 `H264Decoder` + `BitReader`。** 从零的 SPS 解析(scaling list、frame cropping)恢复真实分辨率,有界 drop-oldest 背压,stop 前先 join worker 再 release codec(显式注释 MediaCodec native-crash 安全)—— 全仓工程密度最高、质量最好的单文件。

5. **服务端计费 money-path。** 预扣额度的 `BEGIN IMMEDIATE`+条件 UPDATE 真防并发超扣;Stripe webhook 的 HMAC 验签 + 重放窗 + 常量时间比较 + 幂等结算;密钥仅服务端持有。配套 pytest 是全仓覆盖最好的子系统。

6. **审计日志 HMAC 防篡改。** `ToolAuditLog` 用设备本地 SecureRandom 密钥对每条中/高危记录签名,读时校验并标记 tampered —— 比普通日志显著更难伪造。

---

## 5. 关键风险与技术债(P0/P1/P2)

### 先校正安全信任边界的真实状态

`AUDIT_REPORT.md` 的核心论点"能与智能体对话 = 完全控制设备,因为安全层是security theater"在当前工作树中已**实质性减弱但未完全关闭**。下面分清"已缓解"与"仍可利用"。

**✅ 自审计以来已真正缓解(审计结论现已过时):**
- R2 高危工具闸门**已接线**:`ToolRegistry.executeTool` 对"不可信源 × HIGH_RISK"在 APPROVAL 模式下默认 BLOCK/CONFIRM(`ApprovalFlow`)。
- `PathGuard` 已接入全部 4 个文件工具;`CircuitBreaker` 已构造并 check/record;`sanitizeShellArg` 真实使用;HIGH_RISK 标签真正驱动阻断。
- R1 通道 ACL 在 `startNewTask` 前强制执行,null 发送者 fail-closed。
- R5(UDP 明文 token 广播)、R6(0.0.0.0 绑定)、R7(Shizuku 注入)、R10/S1/S2/S3(服务端)、E1(调度 pause/resume)均已修复。

### P0 —— 仍可利用 / 高危(优先处置)

| # | 风险 | 位置 | 说明 |
|---|---|---|---|
| **P0-1** | **FULL_POWER 模式一键全解防护(opt-in,默认关)** | `PermissionPolicy.kt:57-66`、`KVUtils.kt:441` | 单个 `isAdvancedAutomationMode` 布尔(**默认 `false`**,经 TrustCenter 开关或 `PermissionModeManager.reload` 配置翻转)同时关闭:源闸门 + 高危审批 + SafetyGate 秘钥扫描 + /sdcard 沙箱。**校正:这不是默认态** —— 默认是安全的 APPROVAL。但代码注释将其branding为"闲置/群控机,释放最大能力",一旦用户为群控开启,即原样复活审计的"对话=完全控制"姿态,且文档声称的"3 项防护不可关闭"被此证伪。属**可修复的设计缺陷**(应拆分语义,见 P0 建议 1)。 |
| **P0-2** | **母体 WS 仍为明文 ws://**(默认模式下被来源闸门兜底) | `network_security_config.xml:11`、`OctopusMobileClient.kt:107`、`ToolCallDispatcher.kt:110` | 全局 `cleartextTrafficPermitted=true`;auth token 在 `device/hello` 包体内明文传输,无 TLS/证书固定/重放保护;`HELLO_SENT` 见任意帧即升 ONLINE,链路 MITM 可还原 token 并注入 `tool/execute`。**校正:** 注入的 `tool/execute` 确经 `withUntrustedSource{}` 闸门 —— 默认 APPROVAL 下高危工具被拦/需审批,故本项危害**被安全收口兜底**;仅当叠加 P0-1(FULL_POWER)时 `trustAllSources=true` 才真正失守。token 泄露本身(MITM 还原)仍是独立危害。 |
| **P0-3** | **SafetyGate 语义层从不运行 + FULL_POWER 下被跳过** | `AppViewModel.kt:189`、`ToolRegistry.kt:342` | `SafetyGate()` 以 judge=null 构造,LLM 宪法判官从不实例化;唯一生效的是秘钥正则阻断。**已修复**:FULL_POWER 下仍单独跑 PrivacyScanner 规则层(接入 `privacyScannerEnabled` 不可关闭字段)。 |
| **P0-4** | **群聊 ACL 按会话授权 + TOCTOU 竞态** | `DiscordChannelHandler.kt:38`、`TelegramChannelHandler.kt:99`、`ChannelManager:239` | Discord/Telegram 按 `lastChannelId/lastChatId`(会话级,非作者级)授权 → 群内任意成员通过 ACL;`dispatchMessage` 不携带 senderId,授权时回读可变实例字段,并发消息下可授权错误主体。TOFU 首发者夺权在公开可加入面上可被抢注。 |
| **P0-5** | **Shizuku shell-UID 提权是最大风险集中点** | `DeviceRouteHandler.kt:164`、`ShizukuShellService.kt` | 被提示注入的智能体或获得 LAN token 者可经 shell-UID 执行 input/settings/am/pm/文件删改 —— 产品固有,但风险面巨大。 |
| **P0-6** | ~~NavigateTool 仍可 SSRF~~ **已修复** | `BrowserTools.kt:59` | `BrowserNavigateTool` 已接入 `UrlGuard.check()`,阻止内网 IP / 云元数据 / 本地域名。 |
| ~~P0-7~~ | ~~GeckoView 扩展 drive-by 自动批准~~ **已废弃** | N/A | GeckoView 已整体移除(瘦 -180MB),浏览器改用系统 WebView。扩展能力改为自建注入式插件(evaluateJavascript + shouldInterceptRequest)。此项不再适用。 |

> **可修复 vs 设计固有(roadmap 须区分):** P0-1/2/3/4/6/7 是**可修复缺陷**(改语义、上 TLS、接 UrlGuard、改 ACL 主体、关扩展自批)。**P0-5(Shizuku shell-UID 提权)是产品固有能力,不可"修复"只能"接受/收窄"** —— 它是这只触手存在的意义本身;治理手段是默认 APPROVAL 闸门 + LAN token + 不开 FULL_POWER,而非移除。下文路线图不把 P0-5 计为"可关闭项"。

### P1 —— 可靠性与功能性正确性

| # | 风险 | 位置 |
|---|---|---|
| **P1-1** | ~~流式 LLM `latch.await()` 无超时~~ **已修复**:已抽出 `BaseLangChain4jLlmClient` 基类,`OpenAiLlmClient`/`AnthropicLlmClient` 只剩 `createChatModel/createStreamingChatModel` 差异(~30 行)。 | `BaseLangChain4jLlmClient.kt:78` |
| **P1-2** | ~~头条"自动复制短信验证码"双重损坏~~ **已修复**:改为 `ActionType.NOTIFY_USER` 提示用户手动复制,避免其他 App 读剪贴板导致验证码外泄。 | `ProactiveRuleEngine.kt:331-338` |
| **P1-3** | ~~Shizuku 自身命令被自己的注入过滤器永久拒绝~~ **已修复**:`findDuplicateFiles` 改用 `xargs` 替代 `-exec \;`;`getStorageOverview` 拆成两条独立 `exec()` 调用。 | `ShizukuShellService.kt:904,1024` |
| **P1-4** | **服务端账本不平**:gift/daily 额度消费不写 `credit_transactions`,用户交易记录与管理后台无法对账 | `server/app.py:1756-1805` |
| **P1-5** | **抢占=取消重跑**:pause 摧毁智能体并发"已取消"文案,resume 从零重启无上下文 | `TaskOrchestrator.kt:164-183` |
| **P1-6** | **单 worker 进程内态**:限流 `_rl`、远程中继 `RemoteRelayHub` 在内存,multi-worker 静默失效;无 JWT 撤销 | `server/app.py:417-454,496-584` |
| **P1-7** | VLM 目标/对话校验在执行线程 `runBlocking`,阻塞循环且取消无法中断在途网络调用 | `DefaultAgentService.kt:958` |

### P2 —— 死代码 / 上帝类 / 覆盖缺口

> **🧹 死代码清理已执行(2026-07-20,Task 9):** 本节首项所列死/愿景代码已按"无生产调用方则删,有调用方则 @Deprecated"原则处理。最终状态见下表。`ConnectionStateMachine.kt` 与 `EvolutionEngine.deepEvolve` 经核验**从未存在**(仅文档误称),`StartupModeResolver` 同样无此文件;`cerebrum/ThinkingMode.kt` 与 `StartupMode.kt` 已整文件删除;`BrainModeSelector` / `CanaryManager` / `MpvController` / `ReflexRouter.learnFromPattern` 因有生产调用方保留并加 `@Deprecated`;`ClawApplication.isRuntimeReachable()` 已删除。CODE_WIKI.md 已同步标注。EventBus 未用事件、上帝类、`runBlocking`、`!!` 等其他 P2 项不在本次清理范围。

- **大面积死/愿景代码(文档与现实的系统性落差):** `BrainModeSelector.decide` 整条本地/远程路由、`ConnectionStateMachine`(完整却未用)、`CanaryManager`(~251 LOC 全死)、`EvolutionEngine.deepEvolve`(B3)、`ReflexRouter.learnFromPattern`、`cerebrum/ThinkingMode`、`StartupModeResolver`、`MpvController`(确认死桩)、EventBus ~11 类事件中仅 3 类真正发布。**这是阅读者最容易被误导的地方 —— class 文档与 CODE_WIKI 描述的能力远超实际接线。**

#### P2 死代码清理最终状态(2026-07-20)

| 项 | 处理 | 说明 |
|---|---|---|
| `cerebrum/ThinkingMode.kt` | ✅ 已删除 | 无生产调用方,整文件删除(cerebrum 包随之空) |
| `StartupMode.kt` | ✅ 已删除 | 枚举无任何代码使用方(仅 import 与注释引用) |
| `ClawApplication.isRuntimeReachable()` | ✅ 已删除 | 仅自身定义 + PROJECT_ANALYSIS 引用,无生产调用方 |
| `BrainModeSelector.kt` | ⚠️ 保留+@Deprecated | AppViewModel 实例化、TaskOrchestrator 读取 currentMode/currentDomain(仅日志),删除会破坏编译 |
| `CanaryManager.kt` | ⚠️ 保留+@Deprecated | EvolutionActivity.kt:34 调用 listAll() 只读展示,删除会破坏编译 |
| `MpvController.kt` | ⚠️ 保留+@Deprecated | MediaTools.kt 与 PlayerActivity.kt 大量引用(IS_AVAILABLE=false,全 noop),删除会破坏编译 |
| `ReflexRouter.learnFromPattern` | ⚠️ 保留+@Deprecated | DefaultAgentService.kt:1416 在 open_app 频次≥3 时调用,删除会破坏编译 |
| `ConnectionStateMachine.kt` | ✅ 无需处理 | 文件从未存在(仅 HeartbeatReporter 注释与 README/CODE_WIKI 文档残留),已同步修正文档 |
| `EvolutionEngine.deepEvolve` | ✅ 无需处理 | 函数从未存在(历史文档误称,实际仅有 `deepReflect` 且活跃),已同步修正文档 |
| `StartupModeResolver` | ✅ 无需处理 | 文件从未存在(仅 ClawApplication 注释引用),isRuntimeReachable 已删,注释同步消失 |

- **风险分类硬编码名单静默漂移**:3 份名单(`NON_IDEMPOTENT_TOOLS` / wait_after 黑名单 / `ToolRiskPolicy` HIGH/MEDIUM)需手工同步,无测试守护,"因遗漏而不安全"是工具层主导风险。
- **上帝类**:`ChatScreen.kt` 1743 LOC / 25 composable、`BrowserActivity.kt` 1496、`ShizukuShellService.kt` 1041、`DefaultAgentService.kt` 1004。
- **26 处 `runBlocking`**(根因:`BaseTool.execute()` 非 suspend)集中在 `RemoteActions`(12)/`EchoUniverseTools`(7),有 ANR/线程池饥饿风险。
- **40 处 `!!`**,9 处在 `AppViewModel` init 期对可变可空单例强解 —— 竞态会以 NPE 崩溃而非优雅降级。
- **覆盖缺口(I/O 层)**:`tool/impl` 具体工具、6 平台通道入站解析器、on-device 路由处理器(`FileRouteHandler`/`ScreenHandler`)、整条 H264/屏幕流水线均**几乎/完全无测试** —— 这些都是有副作用或处理不可信输入的面,回归会静默发生。

---

## 6. 测试与构建状况

**测试(高于移动智能体均值的姿态):** 50 个真实 JVM 单测(~571 @Test / ~1,220 断言)+ pytest 116 函数。测试金字塔合理:风险最高的决策/策略核心(安全 guardrail、权限/风险策略、通道 ACL、ReAct 循环、任务队列/抢占、routine 持久化、服务端鉴权、money-path)都有**行为驱动、边界用例丰富**的覆盖(fail-closed、TOFU、抢占、stuck 检测、gson null-vs-zero、CSV 注入、JWT 篡改)。借助 KVUtils 内存回退技巧多数为快速纯 JVM。**短板**:I/O 层(具体工具、通道解析器、文件/屏幕路由、流水线)基本无测试;无 JaCoCo 覆盖度量;并发测试多为"不崩溃"冒烟;CI 不构建/不签 release。

**构建/发布(中等健康,两处必须先修):**
- 工具链现代(AGP 9.1 / Gradle 9.3.1 / Kotlin 2.1.20、版本目录、ABI splits、lint ratchet、CI+CodeQL+Dependabot),proguard/R8 功能正确(Coil keep 已处理)。
- **🔴 高危 1:签名密钥明文口令**在 `local.properties`(已 gitignore 且从未提交,但 `.keystore` 文件躺在仓库根)—— 应外置到 env/CI secret 并视情况轮换。
- **🔴 高危 2:release APK 体积** ~~根因 GeckoView `libxul.so`(151MB)~~ **已解决**:GeckoView 已移除(-180MB),改用系统 WebView(0 包体)。
- 次要:无 Gradle 依赖校验 + aliyun/JitPack 镜像(供应链弱点);依赖普遍陈旧(Dependabot 配了但 PR 未合);lint baseline 压了 348 个问题(42 个硬编码 /sdcard、133 个未用资源);`security-crypto` 用 alpha 版做数据加密。
- **代码质量整体好于旧审计**:标记债近零(4 个真 TODO、无 FIXME/HACK)、异常卫生好(287 catch 仅 3 空且合理)、HTTP 客户端已统一为 `OctoHttp.shared`、无 `GlobalScope`。**最高杠杆的流程缺口:无 detekt/ktlint 静态门禁**。

---

## 7. 优先改进建议(可执行路线图)

**P0 —— 安全信任边界(部署前必做)**
1. **重构 FULL_POWER 语义**:不应一个布尔同时关闭 4 道防护且可远程翻转。至少让 PrivacyScanner 秘钥扫描与 /sdcard 沙箱**无条件生效**(兑现文档承诺),FULL_POWER 仅放宽高危审批且需本地物理确认。
2. **母体 WS 上 TLS + 证书固定 + 握手 nonce/重放保护**;收紧 `network_security_config` 的 cleartext 白名单。
3. **ACL 改为按消息携带 senderId**(消息与发送者同行穿过 `dispatchMessage`),群聊按"作者"而非"会话"授权,引入配对码/扫码绑定替代 TOFU,并补 Settings UI 管理/清除允许列表。

**P1 —— 可靠性与正确性**
6. ~~流式 `latch.await(timeout)` 并将超时作可重试错误;合并两个 95% 重复的 LLM 客户端为一处。~~ **已修复**:已抽出 `BaseLangChain4jLlmClient` 基类。
7. ~~修复短信验证码规则(工具名 `clipboard` + 真正从短信体提取验证码 + 接线 `onSmsReceived`),或诚实下线该宣传功能。~~ **已修复**:改为 `NOTIFY_USER` 提示手动复制。
8. ~~修复 Shizuku 自拒命令(`getStorageOverview`/`findDuplicateFiles`)并补过滤器单测。~~ **已修复**:`xargs` 替代 `-exec \;`,拆分 `&&` 命令。
9. 服务端 gift/daily 消费/退款写入 `credit_transactions` 使账本可对账;明确单/多 worker 部署约束或外置限流/中继状态到 Redis。

**P2 —— 结构与流程**
10. **接入 detekt/ktlint 作为回归门禁**(最高杠杆);CI 增加 release 构建 + APK 体积门禁。
11. ~~**清理死/愿景代码**:或接线或删除 `CanaryManager`/`deepEvolve`/`ConnectionStateMachine`/`BrainModeSelector` 路由/`MpvController`,并同步修正 CODE_WIKI 与 `AUDIT_REPORT.md` 的过时结论。~~ **已执行(2026-07-20,Task 9)**:ThinkingMode/StartupMode/isRuntimeReachable 已删;BrainModeSelector/CanaryManager/MpvController/learnFromPattern 因有生产调用方加 @Deprecated;ConnectionStateMachine/deepEvolve/StartupModeResolver 经核验从未存在(文档误称),CODE_WIKI 已同步修正。详见 §5 P2 清理最终状态表。
12. 把 `BaseTool.execute()` 改为 suspend(消除结构性 `runBlocking` 与 ANR);加固 `AppViewModel` init 去除 `!!`;拆解 `ChatScreen.kt`/`BrowserActivity.kt`。
13. 用编译期校验把 3 份硬编码工具名单与实际注册集对齐(防"因遗漏而不安全")。
14. ~~缩小/模块化 GeckoView;外置并轮换签名密钥。~~ **GeckoView 已移除**;签名密钥仍需外置到 env/CI secret。

---

## 8. 结论(诚实总评)

**整体健康度:中等偏上的成熟原型;生产就绪度:个人/可信网络场景接近可用,无人值守/群控场景尚未就绪。**

Octopus Mobile 是一个**工程野心与实现质量都明显高于普通个人项目**的代码库:智能体循环、工具收口、安全闸门、计费 money-path 这几个承重子系统建得扎实,并且明显经历过多轮清理(共享 HTTP、近零标记债、良好异常卫生、安全 guard 已接线)。旧的 `AUDIT_REPORT.md`"安全层是 security theater"的论断在当前工作树中**已过时约一半** —— PathGuard、CircuitBreaker、高危闸门确已接线生效。

但两条主线削弱了它的成熟度:其一是**文档叙事与实际接线的系统性落差** —— "本地/远程大脑无缝切换""三层自进化 + canary""自动复制验证码"等头条能力,在生产路径上要么是无调用方的愿景代码,要么是确凿的功能 bug;其二是**安全信任边界在默认 APPROVAL 模式下成立,却在 FULL_POWER / 明文 ws / 群聊 ACL 这三处仍然可被攻破** —— 而 FULL_POWER 恰恰是产品主打的群控部署形态,会原样复活"对话即完全控制"的高危姿态。

**一句话裁决:这是一只技术上令人印象深刻、安全意识在持续补强、但"宣传面跑在接线面前面"的触手。** 作为可信网络下的个人自动化工具,它已可用且不乏亮点;但在它把 FULL_POWER 群控、明文母体链路与群聊 ACL 这三件事解决之前,任何"无人值守 / 公开通道 / 多设备群控"的生产部署都应被视为高风险。优先级很清晰:先封边界(P0),再补可靠性(P1),最后还技术债与诚实化文档(P2)。