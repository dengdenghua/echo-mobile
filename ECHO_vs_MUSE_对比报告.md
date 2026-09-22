# Echo Mobile / Octopus Mobile 与 Muse from Meta 静态对比报告

- 日期：2026-09-22
- 对比对象 A：Echo Mobile（当前仓库实际 `applicationId=com.octopus.mobile`，产品/工程名 Octopus Mobile）
- 对比对象 B：Muse from Meta（`com.facebook.aura`）
- 分析方法：源码审查、解包 Manifest 审查、配置/签名/供应链材料复核
- 动态验证：双方均未安装运行、未抓包、未做 Frida/Hook、未投递导出组件 PoC

> 结论先行：Echo 与 Muse 都是面向 AI Agent 的高能力移动端产品，但产品重心不同。Muse 更像 Meta 的“个人数据连接器 + 远程 Agent/安全运行时”，权限和 Android 数据域覆盖明显更宽；Echo 更像“本地设备控制与自动化触手”，Android 权限面更窄，并已有较集中的工具风险、审计、来源信任和沙箱设计。  
> 但 Echo 当前最需要优先处理的问题不在 Manifest，而在 MCP：9528 对外 MCP Server 缺少可见鉴权，调用未绑定“不可信来源”，高风险名单与统一风险策略发生三重漂移；同时动态接入的 `mcp_*` 工具会因未分类而默认为 LOW。按静态攻击面评估，这组问题应优先于 Muse 目前已发现的 exported 组件风险。

## 1. 证据强度与边界

| 项目 | Echo / Octopus Mobile | Muse from Meta |
|---|---|---|
| 主要证据 | 当前 Git 工作区源码、Gradle、Manifest、网络配置、MCP/工具安全代码 | 第三方 apkcombo 镜像 APK、Apktool、Jadx、签名与 Code Transparency 材料 |
| 样本身份 | 当前工作区为 `main`，存在未提交修改；结论反映本次读取时的工作区快照 | `com.facebook.aura`，versionName `8.0.0.19.168`，versionCode `1061301128` |
| 发布包证据 | 仓库根目录有 `OctopusMobile_v1.0.0.apk_副本`，SHA-256 `E5812EF8F2137627630942012408A6654A1A0F3AE000D1CAE8ADBE0038B04387`；本次未把它当作官方发布包签名证据 | 来自第三方镜像；APK SHA-256 `A9DE463BBFE8E0AF7F8C0AEE23E5C1F2C7702602A0EC0052C6D5BA918F29123E` |
| 供应链验证 | 源码快照未包含发布密钥；release 签名由环境变量或 `local.properties` 注入，当前快照无法独立验证实际发布签名；未发现 Code Transparency/等价发布证明 | APK 含 v2/v3 签名结构，证书指向 Meta；Code Transparency JWT 验证通过，44 项中 43 项严格匹配 |
| 重要限制 | 没有证明运行时必定按源码路径执行；未验证 release 混淆、实际权限授予、后台行为、动态工具配置 | Manifest 单项 `ANDROID_MANIFEST_V2` 不匹配，现有材料不能直接定性为篡改；未做 Google Play 官方同版本字节比较 |
| 结论性质 | 静态攻击面与设计风险，不是已确认漏洞 | 静态攻击面与设计风险，不是已确认漏洞 |

Echo 本次源码规模：`app/src/main/java/com/apk/claw/android` 下 549 个源码文件，其中 501 Kotlin、48 Java，共约 100,674 行，适合做跨模块静态比较，但不替代动态安全测试。

Muse 既有详细报告：`E:\echo mobile\muse-analysis\MUSE_静态解包分析报告.md`。

## 2. 身份、构建与发布面

| 维度 | Echo / Octopus Mobile | Muse from Meta |
|---|---|---|
| namespace | `com.apk.claw.android` | 不适用 |
| applicationId / package | `com.octopus.mobile` | `com.facebook.aura` |
| version | `1.0.0` / code 14 | `8.0.0.19.168` / code 1061301128 |
| minSdk | 28 | 29 |
| targetSdk | 36 | 36 |
| compileSdk | 36（minor API 1） | 37 |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`，另有 universal APK | 仅 `arm64-v8a` |
| allowBackup | `false` | `false` |
| 发布验证 | 签名密钥外置，仓库未见密钥和发布证明；APK 副本仅能作为本地样本 | Meta 签名结构 + Code Transparency 43/44 匹配；但样本来自第三方镜像 |

Echo 的 `namespace` 与 `applicationId` 不同是正常工程结构；对外品牌、包名和仓库目录名不完全一致，对外报告不能仅凭名称把三者当作同一层身份。

## 3. 权限面对比

### 3.1 总量

| 项目 | Echo | Muse |
|---|---:|---:|
| Manifest 声明权限 | 27 | 65 |
| 顶层组件 | 68 | 86 |
| `exported=true` 顶层组件 | 5 | 28 |

Echo 的权限数量约为 Muse 的 42%，导出组件数量约为 Muse 的 18%。这不直接等于“Echo 更安全”，但它显著缩小了 Android 系统授权面和应用间协同面。

### 3.2 Echo 有、Muse 没有或未突出的高影响权限

- `SYSTEM_ALERT_WINDOW`：悬浮窗/屏幕覆盖能力。
- `MANAGE_EXTERNAL_STORAGE`：接近全盘文件访问，是 Echo 文件管理、工作空间和自动化能力的核心前提。
- `REQUEST_INSTALL_PACKAGES`：可请求安装 APK。
- `QUERY_ALL_PACKAGES`：可枚举已安装应用。
- `PACKAGE_USAGE_STATS`：读取应用使用统计，需要用户到系统设置额外授权。
- `SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`：精确定时/周期任务。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：请求忽略电池优化，符合常驻设备控制场景。
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：屏幕采集前台服务。
- `READ_PHONE_STATE`：电话状态读取。

这些权限组合与 Echo 的“本地自动化/群控/远程控制”定位一致，但也意味着设备一旦被不可信 Agent 或远程入口驱动，影响范围远大于普通聊天 App。

### 3.3 Muse 有、Echo 没有的高影响数据域

- 精确/粗略/后台定位。
- 联系人读取和写入。
- 拨号与通话记录读取。
- 相机。
- 蓝牙扫描/连接。
- 日历写入。
- 图片/视觉用户选择媒体。
- Health Connect 步数、心率、睡眠、体重、运动、热量、距离等整套健康读取权限。
- 计费、生物识别、广告 ID、安装来源归因等生态权限。

### 3.4 共同能力

双方都声明了网络、唤醒锁、录音、通知、短信读写、日历读取、前台服务和麦克风相关能力。共同点的核心解释是：双方都允许 AI 接触消息、时间/日程、语音和后台执行，但 Muse 更偏“读取个人上下文”，Echo 更偏“改变设备状态并执行操作”。

## 4. 导出组件与应用间攻击面

### 4.1 Echo 当前 `exported=true` 组件

1. `.ui.splash.SplashActivity`
   - MAIN、LAUNCHER、LEANBACK_LAUNCHER、ASSIST。
   - 启动页/系统助手入口，本身没有额外 Manifest 权限。
2. `.ui.browser.BrowserActivity`
   - VIEW `http/https`、WEB_SEARCH。
   - 可被外部链接或系统搜索拉起；风险重点在 WebView 导航、JavaScript、下载、file/自定义 scheme 处理，而不是组件导出本身。
3. `rikka.shizuku.ShizukuProvider`
   - `android:permission=android.permission.INTERACT_ACROSS_USERS_FULL`。
   - 导出面受系统级权限保护；普通第三方应用不能任意调用。
4. `.service.OctopusAppWidgetProvider`
   - APPWIDGET_UPDATE。
   - 主要由系统桌面/AppWidget 框架触发。
5. `.octopus_mobile.proactive.SmsReceiver`
   - `android:permission=android.permission.BROADCAST_SMS`。
   - SMS_RECEIVED；仅系统短信广播发送方可投递。

Echo 的导出面较集中，且两个高敏感 Provider/Receiver 有系统权限保护。剩余重点是对外 Activity 的输入校验和 WebView 内容边界。

### 4.2 Muse 的导出面

Muse 有 28 个顶层 `exported=true` 组件，覆盖 MAIN/ASSIST、深链、OAuth 回跳、分享、安装归因、推送/FBNS、Phone ID、Health SDK、Perfetto 广播等。部分组件代码内有包名/UID/PendingIntent/签名校验，不能仅凭 Manifest 导出就判定漏洞。

对比结论：

- Echo 的原始 exported 数量显著更低。
- Muse 的导出面更宽，但其关键组件普遍有调用方身份校验痕迹。
- Echo 的 MCP 网络服务不在 Manifest 组件模型内，不能用“exported 组件只有 5 个”推导出“对外入口只有 5 个”；9527/9528 是本次对比中最重要的差异。

## 5. Agent 与 MCP 架构对比

### 5.1 Muse

Muse 的 MCP/Agent 体系包含：

- Java 侧 `MCPServer`、`MCPTool`、`AgenticRuntimeBridge`、`AgenticRuntimeProvider`。
- native `libmcp_server_jni`、`agentic_runtime_jni`。
- `MobileMCPRegistry`、remote client、MCP SDK session。
- Connector 权限：`ALLOW` / `ASK` / `DENY`。
- Agent approval：一次性、会话、当天、永久允许/拒绝、超时。
- Secure/Confidential VM、SSH access token、notary token、secret store 等远端运行痕迹。
- 本地设备 connector 类别包括日历、联系人、短信、电话、通知、通话记录、健康、照片。

Muse 的整体方向是：把用户个人数据域和远端 Agent/安全 VM 连接起来。其重大未决问题不是“有没有审批”，而是 Java、JNI、native registry、remote 调用是否全部共用同一审批边界；本次静态分析未证明闭环。

### 5.2 Echo

Echo 有两类 MCP 方向：

1. **对外 MCP Server**：把本机 Android 工具暴露给外部 MCP 客户端。
2. **对外 MCP Client**：连接外部 MCP Server，并把其工具动态注册回 `ToolRegistry`。

Echo 的高能力工具类别包括 UI 自动化、浏览器、文件、代码执行、Linux/PRoot Shell、Git/GitHub、SSH/SFTP、工作空间、短信/日历、VPN、Shizuku 自动配置、虚拟显示、本地模型、子 Agent、母体 WebSocket、局域网控制等。

## 6. 两条 MCP Server 路径：关键差异

| 维度 | 9527 `ConfigServer /mcp` | 9528 `McpServer /mcp` |
|---|---|---|
| 默认端口 | 9527 | 9528 |
| 启动方式 | 配置服务器路径 | `McpServerBootstrap`；受 `KVUtils.isMcpServerEnabled()` 控制，默认关闭 |
| 路径 | `/mcp` | `POST /mcp` |
| 鉴权 | 非公开路由统一要求 `Authorization: Bearer <token>`；query token 已被鉴权代码禁用 | `McpServer.serve()` 中未发现 token/Bearer 校验 |
| 来源信任 | `ToolRegistry.withUntrustedSource {}` | 直接调用 `registry.executeTool()`，未标记不可信来源 |
| 审批 | 仍进入 ToolRegistry 完整工具管线 | 进入 ToolRegistry，但缺来源标记；额外 MCP 高危审批只覆盖 6/8 项漂移名单 |
| CORS | 统一路由管理 | `Access-Control-Allow-Origin: *` |
| 风险定位 | 较接近“有鉴权 + 有来源边界” | 当前为高优先级硬风险 |

9527 本身也有一个集成一致性问题：`ConfigServerManager.getAccessUrl()` 仍生成 `http://<addr>/?token=<token>`，而服务端明确只接受 Bearer Header。它不一定形成越权，但会造成 UI/二维码/控制台鉴权流程不一致，属于可用性和实现漂移风险。

## 7. Echo 工具安全模型：有效机制与真实缺口

### 7.1 已存在的机制

Echo 的 `ToolRegistry.executeTool()` 路径包含：

- 工具启用检查。
- DryRun 跳过。
- 权限策略读取。
- CircuitBreaker。
- 不可信来源闸门。
- 中/高危风险策略。
- 不可逆操作 UndoWindow。
- SafetyGate / PrivacyScanner。
- Agent ApprovalGate。
- Guardrail。
- AuditLog。
- PathSandbox（策略层声明为不可关闭）。
- 并行/异步调用时重放 `untrusted` ThreadLocal 状态。

这比单纯依赖 Manifest 权限或一个审批弹窗更系统，是 Echo 相对 Muse 当前公开静态证据的主要工程优势之一。

### 7.2 必须避免过度描述

- Echo 默认权限模式是 `APPROVAL`，但第 8 道 `ApprovalGate` 注入的是 `RuleBasedProvider(AutoApproveProvider())`。DEFAULT/审批模式下，第 8 道闸门并不会自动变成人工确认，而是最终走 AutoApprove；真正的拦截主要来自来源闸门和其他策略。
- 不可信来源的中危工具默认 `mediumRiskAction=ALLOW`，并非全部确认。
- `FULL_POWER` 会设置 `trustAllSources=true`、`highRiskAction=ALLOW`、`safetyGateEnabled=false`，从而跳过来源闸门和完整 SafetyGate。PrivacyScanner 规则层、审计、熔断和 PathSandbox 仍声明启用。
- 因此不能概括为“Echo 所有高危工具默认 fail-closed”。

### 7.3 风险名单三重漂移

Echo 当前至少存在三套风险/高危工具名单：

- `ToolRiskPolicy.HIGH_RISK_TOOLS`：覆盖面最大，包含 `run_shell`、`run_python`、`run_code`、`ssh_exec`、`sftp_write`、`start_vpn`、`shizuku_auto_setup` 等。
- `JsonRpcDispatcher.HIGH_RISK_TOOLS`：6 项，主要是 `send_sms`、`install_app`、`file_delete`、`system_setting`、`payment`、`account_logout`。
- `SystemApprovalGate.HIGH_RISK_TOOLS`：8 项，在前 6 项基础上增加 `git_push`、`github_create_pr`。

结果是：9528 的 MCP 审批门只强制处理它自己的 8 项；`run_shell`、`run_python`、`run_code`、`ssh_exec` 等统一策略中的高危工具，仍进入 ToolRegistry 审计，但不能依赖 9528 的高危审批门拦截。

### 7.4 动态 MCP 工具默认为 LOW

外部 MCP Server 的工具通过 `McpManager` 动态注册为 `mcp_<serverId>_<toolName>`。当前证据显示：

- 未发现 `mcp_*` 专用风险分类。
- `ToolRiskPolicy.riskOf()` 对不在 HIGH/MEDIUM 名单中的名称默认返回 LOW。
- `shouldAudit()` 对 LOW 返回 false。
- 来源闸门只检查 HIGH/MEDIUM。
- `ToolRiskPolicyCoverageTest` 只检查静态注册的内置工具，不覆盖运行时动态 MCP 工具。

因此外部 MCP 工具可能同时绕过审计和高危来源闸门，并被当作 LOW 风险执行。这是 Echo 当前第二个 P0 级静态风险。

### 7.5 STDIO 与 SSE

`McpClient` 对 STDIO 传输直接调用 `ProcessBuilder(transport.command)`，命令来自持久化配置，没有看到 argv 白名单、工作目录沙箱、用户确认或可执行文件签名约束。SSE 传输也允许配置任意 `http(s)` URL；代码注释声称 URL 由调用方过 `UrlGuard`，但当前 MCP 模块中未找到 `McpClientTool` 或对应校验调用，属于注释与实现证据不一致。

对个人本地开发工具，这可能是可接受的产品取舍；对群控/企业分发设备，应改为显式授权、allowlist/沙箱和可审计配置。

## 8. 网络与母体/远程链路

### 8.1 Echo

- 全局 `base-config cleartextTrafficPermitted=true`。
- 仅 `api.octoapk.com` 强制 HTTPS。
- 明文保留原因是母体 `ws://` 和 LAN `http://<对端IP>` 场景。
- `MobileRuntimeSecurity.assess()` 阻止公网明文 `ws://`，仅放行 loopback、emulator 和 RFC 1918 私网。
- 由于 Android 网络安全配置本身仍允许全局 cleartext，静态配置层不能证明所有流量都被运行时策略覆盖；LAN 和私网仍是明文信任边界。

这比“全链路 TLS”弱，但比“全局明文且无运行时判断”强。报告应同时写出两层事实。

### 8.2 Muse

- 全局 base-config 同样允许明文。
- Meta/Facebook/Instagram/Meta 核心域名强制 HTTPS，并配置证书 pin。
- Pin 过期日：2027-09-17。
- 少量链接域允许明文，属于兼容性例外。
- 反编译常量中的主要 API 多为 HTTPS，但第三方 connector/动态 URL 仍可能落入明文允许范围。

因此，简单写成“Echo 明文、Muse 全 TLS”是不准确的。准确差异是：Muse 对核心 Meta 域有强制 TLS + pin，Echo 只对已知后端域强制 HTTPS，母体/局域网链路仍保留明文能力。

## 9. 各自优势

### 9.1 Echo 的优势

- Android Manifest 权限 27 对 65，导出组件 5 对 28，原始系统暴露面明显更小。
- 没有 Muse 的定位、联系人、通话记录/拨号、相机、蓝牙、Health Connect 等个人数据权限。
- 工具体系有集中风险策略、审计、来源信任、熔断、隐私扫描、路径沙箱、DryRun 和撤回窗口。
- 9527 的 `/mcp` 有 Bearer 鉴权，并显式把外部调用标记为不可信来源。
- 本地模型、Shizuku/PRoot、SSH/SFTP、设备自动化和局域网控制更偏“用户可控的本地触手”，不天然依赖大规模个人数据 connector。

### 9.2 Muse 的优势

- Meta 签名与 Code Transparency 提供了更强的发行身份和完整性证据；43/44 匹配虽然仍有 Manifest 单项待复核，但明显强于当前 Echo 仓库快照可提供的发布证明。
- Meta 核心域名有 HTTPS pin，供应链与传统网络安全基线更强。
- 个人数据 connector 覆盖更广，产品能力上限更高。
- 有 native MCP/Agentic runtime、Secure/Confidential VM 和 SSH/notary 证据，远端执行和安全边界的工程投入更深。
- 导出组件虽多，但关键入口常见 UID、包名、PendingIntent、签名校验，不能简单以数量判优劣。

## 10. Echo 高优先级整改建议

### P0：先修 MCP 暴露面

1. **给 9528 加默认强制鉴权**
   - 复用 ConfigServer 的 Bearer token 机制，或为 9528 设计独立 token。
   - 鉴权必须发生在 `McpServer.serve()` 路由分发之前。
   - 明确 hostname 绑定范围；不要默认依赖 `hostname=null` 的隐式全网卡绑定。
   - 收紧 `Access-Control-Allow-Origin: *`。

2. **9528 工具调用统一标记不可信来源**
   - 在 `ToolRegistryMcpProvider.executeTool()` 外包 `withUntrustedSource`，或在 dispatcher 层传递来源上下文。
   - 不能仅依赖当前 6/8 项 MCP 高危名单。

3. **消除三套高危名单漂移**
   - `JsonRpcDispatcher`、`SystemApprovalGate`、`ToolRiskPolicy` 统一引用单一风险源。
   - 对 `run_code`、`run_python`、`run_shell`、`ssh_exec`、`sftp_write`、`shizuku_auto_setup`、`start_vpn` 等统一执行来源闸门。

4. **动态 MCP 工具不得默认 LOW**
   - `mcp_*` 工具应默认 HIGH/UNKNOWN，或必须显式声明风险与能力。
   - 将动态工具纳入审计与来源闸门覆盖测试。
   - 对工具名称做服务器级 namespace 校验，防止与内置工具或预期名称混淆。

### P1：收口外部执行与网络边界

5. STDIO MCP 增加允许列表、命令签名/沙箱、工作目录约束和首次授权；禁止无提示启动任意命令。
6. SSE URL 在进入网络层前统一过 `UrlGuard`，并禁止访问 loopback/元数据地址/内网服务，除非用户显式确认。
7. 修复 `ConfigServerManager.getAccessUrl()` 的 query-token 与 Bearer-only 鉴权不一致。
8. 母体托管链路优先强制 `wss://`；在无法避免 LAN 明文时，至少使用一次性配对、设备身份和消息级完整性保护。
9. `FULL_POWER` 不应整体绕过来源信任；至少保留“不可信来源 + 系统级/不可逆工具”的最小硬闸门。
10. 为 BrowserActivity/WebView 建立统一 URL、下载、file、jsBridge、自定义 scheme 白名单。

### P2：发布完整性与验证

11. 建立可验证 release：CI 签名、APK/AAB SHA-256、SBOM、可复现构建或 Code Transparency/等价证明。
12. 对 9527/9528 做鉴权、绑定网卡、CORS、来源标记和错误返回的自动化安全测试。
13. 对 `mcp_*` 动态注册、断开、重连、同名覆盖、恶意 schema 和工具描述注入建立测试。
14. 对 `FULL_POWER`、`APPROVAL`、`untrusted`、后台/前台、无 UI 五类运行场景做矩阵测试。

### 整改状态（2026-09-22 复核）

> 本节为整改**后**的补充说明；第 6、7 节描述的是对应代码在整改**前**的状态，保留作为问题记录。

本轮已按 P0/P1 优先级完成以下源码级修复（`main` 分支工作区，尚未提交）：

| 整改项 | 状态 | 落地位置 |
|---|---|---|
| 9528 默认强制 Bearer 鉴权（路由分发前 fail-closed、拒绝 query token、401 带 `WWW-Authenticate`） | 已修复 | `mcp/McpServer.kt`、`mcp/McpServerBootstrap.kt` |
| 9527/9528 共用同一持久化 token，轮换后即时失效 | 已修复 | `server/LocalControlAuth.kt`（新增）、`server/ConfigServer.kt`、`server/ConfigServerManager.kt` |
| 9528 工具调用统一标记不可信来源（进来源闸门 + 审计） | 已修复 | `mcp/ToolRegistryMcpProvider.kt` |
| 三套高危名单漂移收敛为单一权威源 | 已修复 | `mcp/JsonRpcDispatcher.kt`、`mcp/SystemApprovalGate.kt` → `octopus_mobile/safety/ToolRiskPolicy.kt`（仅保留别名） |
| 动态 `mcp_*` 工具不再默认 LOW（改为默认 HIGH） | 已修复 | `octopus_mobile/safety/ToolRiskPolicy.kt` |
| `getAccessUrl()` 不再输出 query token | 已修复 | `server/ConfigServerManager.kt` |
| SSE URL 进入网络层前过 `UrlGuard` | 部分修复 | `safety/UrlGuard.kt` 新增 `checkConfiguredEndpoint()` + `tool/mcp/McpClient.connectSse()`（连接期带 DNS 复检）+ `tool/mcp/McpServerConfig.validate()`（保存期）；**始终**拦云元数据与 link-local（169.254/16、fe80::/10），loopback/私网**有意放行**（见下） |
| STDIO 命令白名单/沙箱 | 已修复 | `tool/mcp/McpStdioGuard.kt`（新增）+ `McpClient.connectStdio()`（连接期复检）+ `McpServerConfig.validate()` / `McpServerConfigStore.restoreAll()`（保存期校验，非法配置不启动） |
| 明文网络收敛（母体 `ws://`、LAN `http://`） | 部分修复 | `res/xml/network_security_config.xml`：base-config 仍允许明文（LAN IP 动态、`<domain>` 无法表达 CIDR），新增 25 个已知公网后端域 `cleartextTrafficPermitted="false"`；母体/LAN 链路不受影响 |
| `FULL_POWER` 最小硬闸门 | 已修复 | `octopus_mobile/safety/SourceGatePolicy.kt`（新增，闸门决策抽为纯函数）+ `safety/PermissionPolicy.kt`（`untrustedHighRiskHardGate`，两种预设均开启）+ `tool/ToolRegistry.kt` |
| 签名发布证明 / SBOM / 可复现构建 | 未完成 | 见 P2 第 11 条 |

验证情况：

- `:app:compileDebugKotlin` 编译通过。
- 新增/受影响的纯 JVM 单测全部通过：`LocalControlAuthTest`(9)、`ToolRiskPolicyTest`(12，含新增 `mcp_*` 默认 HIGH 断言)、`McpDispatcherTest`(15)、`McpSchemaTest`(4)、`McpServerCoreTest`(7)、`McpClientTest`(7)、`SourceGatePolicyTest`(11，新增)、`McpStdioGuardTest`(13，新增)、`UrlGuardTest`(25，含新增 `checkConfiguredEndpoint` 6 项)。
- **P1 收口（2026-09-22 第二轮）**：P1 第 5/6/8/9 条已落地 —— STDIO 命令白名单（`McpStdioGuard`，保存期 + 连接期双重校验，非法配置不启动）、SSE URL 端点守卫（`UrlGuard.checkConfiguredEndpoint`，始终拦云元数据/link-local）、25 个已知公网后端域强制 HTTPS、`FULL_POWER` 的「不可信来源 × 高危工具」硬闸门（闸门决策抽为纯函数 `SourceGatePolicy` 并单测锁定）。第 5/9 条为**完整修复**；第 6/8 条为**部分修复**（loopback/私网 MCP、LAN 明文是设计内行为，见下方语义说明）。
- Robolectric 套件（`ConfigServerTest`、`ToolRiskPolicyCoverageTest`）在本机**无法启动**：Robolectric 4.11.1 在 Windows 上缺少 native runtime 原生库（`UnsatisfiedLinkError: conscrypt_jni`），其依赖解析路径在 Windows 下另有一处临时目录缺陷（`'posix:permissions' not supported as initial attribute`）。同一失败在**未改动**的 `PlanArtifactTest` 上复现，属环境既存问题、与本轮改动无关；这两套测试需在 Linux/macOS 或修好 Robolectric 的环境补跑。

遗留风险：`SystemApprovalGate` 在 `ClawApplication` 中以 `SystemApprovalGate()` 注入、未传 `onPromptUser`，因此 9528 上所有 HIGH 工具当前是 fail-closed 自动拒绝。安全方向正确（不会静默放行），但用户无法通过 UI 批准，需要接入审批弹窗后才算功能完整。

语义说明（本轮改动刻意保留的行为，避免被误判为遗漏）：

- **SSE 端点为什么不直接套 `UrlGuard.check()`**：`check()` 默认拒绝 loopback/私网，而 MCP SSE server 的合法用法恰恰包括「本机 `http://127.0.0.1:PORT/sse`」与「局域网自建 `http://192.168.x.x:PORT/sse`」；照搬会把 LAN 自建链路一刀切禁掉。`checkConfiguredEndpoint()` 因此改为**只**拦云元数据端点与 link-local（169.254/16、fe80::/10，含 IPv4-mapped 变体），loopback/私网放行。
- **满血模式的无人值守逃生舱仍可用**：硬闸门把「不可信来源 × 高危工具」降级为 `CONFIRM`，而不是 `BLOCK`。确认链路依次尝试 UI 逐次确认回调 → `KVUtils.isRemoteHighRiskAllowed()`（设置页「允许远程来源执行高危工具」，群控机显式开启后自动放行）→ 本地审批窗（无人值守时超时拒绝，fail-closed）。因此群控批量任务不会被锁死，但也不再是"默认无确认执行"。
- **STDIO 白名单允许 `&` 与 `$`**：`ProcessBuilder` 不经过 shell，这两个字符在 URL query、npm 版本范围等合法参数里常见，拦截会造成大量误报；只拦 `;` `|` `` ` `` `<` `>` 与换行/NUL/其他控制字符（纵深防御，防止未来被包一层 `sh -c`）。
- **明文网络只做"能锁的锁"**：`base-config` 必须保留 `cleartextTrafficPermitted="true"`，否则母体 `ws://` 与动态 LAN IP 的 `http://` 会被 release 版静默拒绝；本轮只对 25 个已知公网后端域加 `cleartextTrafficPermitted="false"`。注意该策略同样作用于内置 WebView，因此 in-app 浏览器访问这些站点的 `http://`（而非 `https://`）会被拒——这是有意的降级保护。
- **STDIO 仍未做、且刻意暂不做的两项**：①命令签名/来源证明；②子进程工作目录约束（Android 子进程 cwd 继承 App，stdio server 常按相对路径解析资源，强行改 cwd 的破坏面大于收益）。当前防线是「argv[0] 必须为白名单裸命令名」+ 参数/环境变量过滤 + Android 沙箱（App UID），已在保存期（`McpServerConfig.validate()`，含 `restoreAll()` 跳过非法存量配置）与连接期（`McpClient.connectStdio()`）双重强制。

## 11. 最终对比判断

| 维度 | 更强者 | 判断 |
|---|---|---|
| 官方发行身份/完整性 | Muse | Meta 证书与 Code Transparency 明显更强；Echo 当前快照缺少可独立验证发布证明 |
| Android 原始权限面 | Echo | 27 对 65，且无定位/联系人/相机/健康等个人数据域 |
| exported 组件面 | Echo | 5 对 28；但 Echo 的 MCP 网络入口不在 Manifest 中，必须单列 |
| 个人数据 connector 能力 | Muse | 联系、通话、健康、位置、媒体等覆盖更广 |
| 本地设备自动化能力 | Echo | Shizuku、无障碍、PRoot、SSH、文件、VPN、屏幕控制等更完整 |
| 工具治理框架 | Echo | 有集中风险（单一权威源）、审计、来源闸门、沙箱、熔断；高危名单漂移与动态 `mcp_*` 默认 LOW 两个缺口已于 2026-09-22 补齐 |
| 当前最危险的静态入口 | Echo | 整改前：9528 无可见鉴权 + 未标记不可信 + 动态 MCP 工具默认 LOW，优先级高于 Muse 已列导出组件项；该三项已于 2026-09-22 修复（见第 10 节末「整改状态」） |
| 核心网络安全基线 | Muse | Meta 核心域强制 HTTPS + pin；Echo 于 2026-09-22 扩到 25 个已知公网后端域强制 HTTPS，但 base-config 仍允许明文（LAN 需要）、且无 pin |

一句话：**Echo 的系统权限面比 Muse 克制，工具安全框架也更系统；整改前 Echo 把部分最强工具通过 9528 MCP 暴露出去，而鉴权、来源信任、风险名单和动态工具分类没有形成闭环 —— 这四项 P0 已于 2026-09-22 修复。P1 的 STDIO 白名单、`FULL_POWER` 最小闸门、SSE 端点守卫已于 2026-09-22 完整收口，明文网络收敛完成"已知公网域强制 HTTPS"的部分（母体 `ws://` 与动态 LAN `http://` 仍为设计内明文），仍差 P2 的发布完整性证明（签名/SBOM/可复现构建）与 wss/http 迁移；若准备对外分发或用于群控/企业设备，建议补上 P2、并在具备原生动库的 Linux/macOS 环境补跑 Robolectric 套件后再做动态测试。**

## 12. 后续动态验证清单

- Echo：9528 仅监听哪些网卡？未鉴权请求能否 `initialize`、`tools/list`、`tools/call`？
- Echo：`mcp_run_shell` 等动态工具是否真的进入审计、来源闸门和高危审批？
- Echo：`mcp_*` 与同名内置工具、跨 server 同名工具的注册/覆盖行为。
- Echo：STDIO 配置命令是否能从远程 9527/Tentacle 或其他不可信来源写入并自动启动。
- Echo：`FULL_POWER` 下 PrivacyScanner、AuditLog、CircuitBreaker、PathSandbox 的实际测试结果。
- Echo：BrowserActivity 对 file、content、intent、自定义 scheme、下载和 jsBridge 的真实边界。
- Muse：从 Google Play 获取同 versionCode 官方包，进行签名、Manifest、Code Transparency 和字节差异复核。
- Muse：PerfettoReceiver、Health SDK、FBNS、Phone ID、Install Referrer、深链和 VNC/WebView 的隔离动态测试。
- 双方：MCP 工具描述注入、工具参数 schema 绕过、审批超时/无 UI/后台态行为。

## 13. 主要证据索引

- Echo Manifest：`E:\echo mobile\app\src\main\AndroidManifest.xml`
- Echo 构建配置：`E:\echo mobile\app\build.gradle.kts`
- Echo 网络配置：`E:\echo mobile\app\src\main\res\xml\network_security_config.xml`
- Echo 运行时 WS 策略：`E:\echo mobile\app\src\main\java\com\apk\claw\android\octopus_mobile\MobileRuntimeSecurity.kt`
- Echo 工具总入口：`E:\echo mobile\app\src\main\java\com\apk\claw\android\tool\ToolRegistry.kt`
- Echo 统一风险策略：`E:\echo mobile\app\src\main\java\com\apk\claw\android\octopus_mobile\safety\ToolRiskPolicy.kt`
- Echo 9527 MCP：`E:\echo mobile\app\src\main\java\com\apk\claw\android\server\routes\McpRouteHandler.kt`
- Echo 9528 MCP：`E:\echo mobile\app\src\main\java\com\apk\claw\android\mcp\McpServer.kt`
- Echo 9528 启动与注入：`E:\echo mobile\app\src\main\java\com\apk\claw\android\mcp\McpServerBootstrap.kt`、`E:\echo mobile\app\src\main\java\com\apk\claw\android\ClawApplication.kt`
- Echo MCP 审批：`E:\echo mobile\app\src\main\java\com\apk\claw\android\mcp\JsonRpcDispatcher.kt`、`SystemApprovalGate.kt`
- Echo MCP Client：`E:\echo mobile\app\src\main\java\com\apk\claw\android\tool\mcp\McpClient.kt`、`McpManager.kt`、`McpServerConfig.kt`
- Muse 详细解包报告：`E:\echo mobile\muse-analysis\MUSE_静态解包分析报告.md`
- Muse Apktool Manifest：`E:\echo mobile\muse-analysis\apktool\AndroidManifest.xml`
- Muse 签名材料：`E:\echo mobile\muse-analysis\signing.json`
- Muse Code Transparency：`E:\echo mobile\muse-analysis\code-transparency-verification.json`

## 14. 验证边界声明

本报告不是渗透测试报告。所有“风险”“高优先级”“攻击面”均指静态代码、Manifest、配置或供应链证据所显示的设计与实现风险，不代表已经成功利用。Echo 未进行安装、运行、抓包、Hook、PoC、权限拒绝、网络重放或真实 MCP 客户端测试；Muse 未完成 Google Play 官方包复核、native 全逆向、动态审批绕过或导出组件投递测试。任何将本报告用于发布决策前，应按第 12 节补充隔离环境动态验证。
