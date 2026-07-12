# Octopus Mobile · 项目长期记忆

## 项目定位
AI 驱动的 Android 全自动化操控应用。LLM ReAct 智能体 + 无障碍服务 + Shizuku shell 提权，把手机变成可被本地大脑、远程"母体(octopus-agent)"或 IM 聊天平台命令驱动的"触手(tentacle)"。自带服务端账号/积分/会员/LLM 中转计费后端。

## 规模与技术栈
- 规模: Kotlin ~56.9k LOC + Java 遗留 ~5.9k + Python 中转 ~2.4k ≈ 65k 行 / 573 git 文件
- 技术栈: Kotlin 2.1.20 / Jetpack Compose(混 ~36 遗留 Activity) / LangChain4j 1.12.2 over OkHttp / NanoHTTPD / MMKV / Shizuku 13.1.5 / 系统 WebView / FastAPI+SQLite
- 工具链: AGP 9.1.0 + Gradle 9.3.1, compileSdk/targetSdk 36, minSdk 28, versionCode 11 / versionName 1.0.0
- applicationId: com.octopus.mobile, namespace: com.apk.claw.android

## 五层架构(承重墙)
1. 入口层(4 入口): 本地UI(Compose Shell) / IM 通道(钉钉飞书QQ Discord TG 微信) / 远程母体(WebSocket 入站) / LAN HTTP(:9527)
2. 编排层: AppViewModel(组合根/上帝对象) → TaskOrchestrator(优先级队列+抢占)
3. 智能体层: DefaultAgentService(ReAct 观察→思考→行动→验证, 1004 LOC, maxIterations 40)
4. ★ 唯一受控收口: ToolRegistry.executeTool 7 道闸门(enable→断路器→权限策略→源×高危→SafetyGate→guardrail→审计HMAC), 所有执行路径必经
5. 执行基座: ClawAccessibilityService(手势/节点树/截屏) + ShizukuShellService(shell-UID 提权)

## 关键设计亮点
- 唯一工具收口 + withUntrustedSource{} ThreadLocal 源信任模型(6 个不可信入口全包裹)
- 智能体三重防重复(幂等/屏幕相似度≥0.97/指纹循环检测) + 崩溃检查点
- Routines/ActionCache 按锚文本而非死坐标回放
- 手写 H264Decoder+BitReader(SPS 解析, native-crash 安全 stop)
- 服务端计费预扣额度 BEGIN IMMEDIATE + 条件 UPDATE 防并发超扣
- 审计日志 HMAC 防篡改

## 工程门禁现状(已校正 PROJECT_ANALYSIS.md)
- detekt 已接入(棘轮 baseline 策略, detekt-baseline.xml, detekt.yml), 报告"无静态门禁"结论已过时
- JaCoCo 已接入(jacocoTestReport 任务, debug 开启 enableUnitTestCoverage)
- lint 棘轮 baseline(lint-baseline.xml)
- GeckoView 已移除(-180MB), mpv 已移除(-25MB), 浏览器改系统 WebView
- HTTP 已统一为 OctoHttp.shared, 无 GlobalScope
- 签名密钥优先 env(CI 注入)其次 local.properties

## 主要风险(需优先处置)
- P0-1 FULL_POWER 模式一键关 4 道防护(默认关, opt-in; 一开即复活"对话=完全控制")
- P0-2 母体 WS 明文 ws://, token 明文, 无 TLS/证书固定(注入经来源闸门兜底, 叠加 FULL_POWER 才真失守)
- P0-4 群聊 ACL 按会话授权 + TOCTOU 竞态(群内任意成员可控)
- P0-5 Shizuku shell-UID 提权(产品固有, 不可修复只能收窄)
- P1-4 服务端账本不平(gift/daily 不写 credit_transactions)
- P1-5 抢占=取消重跑无上下文保留

## 文档与现实的系统性落差(阅读者最易被误导处)
- "本地/远程大脑无缝切换": BrainModeSelector.decide 整条无生产调用方(愿景代码)
- "三层自进化 + canary": L3 deepEvolve/CanaryManager 整体死代码
- StartupModeResolver / ConnectionStateMachine(完整却未用) / MpvController(死桩)
- CODE_WIKI 与 AUDIT_REPORT.md 部分结论已过时

## 关键文件位置
- PROJECT_ANALYSIS.md: 权威综合分析(10 子系统 + 4 横切审计)
- AUDIT_REPORT.md: 70 项安全发现(部分过时)
- app/src/main/java/com/apk/claw/android/: 主源码(agent/channel/tool/service/ui...)
- server/: Python 中转服务端(FastAPI+SQLite)
- gradle/libs.versions.toml: 版本目录
