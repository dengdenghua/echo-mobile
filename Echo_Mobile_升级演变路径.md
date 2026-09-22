# Echo Mobile 升级演变路径

> 日期：2026-09-22　范围：`com.octopus.mobile`（OctopusMobile）　基线：P0/P1 整改已完成；P2-11 发布可信 L1 代码已落地，等待 Secrets 与首次 CI 实跑
> 命名沿用 `ECHO_vs_MUSE_对比报告.md` 第 10 节的 P0/P1/P2 编号，P3 及以后为本文件新分层。
> 每一条给出 **现状证据 → 落地位置 → 验收标准**，可直接当作工单拆分依据。

---

## 0. 全局定位：先认清"我们在优化什么"

本轮对比确定的结论不变：

- **Muse = 双执行面**：云端 Confidential VM 承担重活 + 手机本地 MCP／数据 connector。隔离靠"远程一次性 VM"。
- **Echo = 单执行面**：所有工具（含 HIGH 风险）都在真机 App UID 下跑。隔离靠"本地硬闸门 + 沙箱 + 审批"。

这个定位直接决定升级优先级：**因为 Echo 把能力放在真机上，配置失控的代价 100% 落在用户设备上**。所以 Echo 的演进主轴不是"追功能量"，而是 **让每一次能力扩张都有可验证的边界与可回滚的发布**。

---

## 1. 当前基线（已核实证据）

| 面 | 现状 | 证据位置 |
|---|---|---|
| CI | `assembleDebug` + `lintDebug`(baseline 棘轮) + `detekt`(baseline 棘轮) + `testDebugUnitTest`；只上传 lint 报告 | `.github/workflows/ci.yml` |
| 发布流水线 | 已新增 tag 触发的 `release.yml`：质量门 → 签名 APK/AAB → SHA-256 清单 → AAB/APK 验签 → SBOM → attestation → GitHub Release；**代码已落地，仓库 Secrets 尚未配置** | `.github/workflows/release.yml` |
| 签名配置 | `signingConfigs.release` 已就绪（`KEYSTORE_FILE`/`KEY_ALIAS` 走环境变量或 `local.properties`），release 已开 `minify`+`shrinkResources`，APK 启用 **v2+v3 签名块**（实测产物 APK Signing Block 内 `0x7109871a`(v2) 与 `0xf05368c0`(v3) 并存且各自校验通过：v2 兼容 API 24+ 与只认 v2 的渠道，v3 提供密钥轮换；CI 对两者分别断言） | `app/build.gradle.kts` L37-62、L132-147 |
| 版本 | `versionCode = 14`、`versionName = "1.0.0"`，无 product flavor／多渠道 | `app/build.gradle.kts` L69-70 |
| 分包 | 按 ABI 拆包 + universal 包；`arm64-v8a`/`armeabi-v7a`/`x86_64`；AAB 用 `-PechoBundle` 关闭 splits，避免 AGP 互斥失败；APK 文件名已去除时间戳 | `app/build.gradle.kts` L107-122、L331-340 |
| 安全扫描 | CodeQL（java-kotlin + python，每周一 08:00 UTC）；native NDK 交叉编译验证（cpp 变更时触发） | `codeql.yml`、`build-native.yml` |
| 测试 | 单元测试 122 个文件；**仪器测试仅 1 个**（`ExampleInstrumentedTest.kt` 模板） | `app/src/test`、`app/src/androidTest` |
| 覆盖率 | `jacocoTestReport` task 已存在，**未接入 CI** | `app/build.gradle.kts` L384-414 |
| 云端 | 无云端执行面（与 Muse 相反，是有意取舍） | — |

**一句话结论：源码侧治理（P0/P1）已经领先 Muse；工程侧可信交付（P2-11）已从零补到 L1，剩余缺口是 Code Transparency、测试基建与首次端到端发布实跑。**

---

## 2. P0 收口记录（代码级已闭环）

### P0-1　接通 9528 审批回路 ✅ 已修复

- **修复证据**：`94b4d0c` 已把 `appContext` 注入 `ToolRegistry`；CONFIRM 分支先查远程高危开关，再走 `ApprovalFlow.requestApproval(appContext, ...)` 弹审批窗并写审计。
- **设计要点**：新增 `enforcesOwnPolicy`，避免外层闸门二次裁决导致 fail-closed。
- **落地位置**：`ClawApplication.kt` 注入点、`ToolRegistry.kt` CONFIRM 分支、`ApprovalFlow`/`KVUtils` 策略读取。
- **验收标准**：① 真机触发一次 HIGH 工具，弹出审批 UI；② 拒绝 → 工具不执行且返回可读原因；③ 批准 → 执行一次且审计日志留痕；④ 无 UI（后台/无 Activity）时仍 fail-closed 且不 ANR。
- **结论**：代码级回归已消除；P0 不再阻塞后续发布工作，真机回归作为发布前检查项保留。

---

## 3. P1 收尾（收口攻击面，与 P2 并行）

### P1-10　WebView 统一 URL／下载／file／scheme 白名单 ✅ 已完成（跨域 iframe 彻底方案除外）

- **落地位置**：新增 `safety/WebViewUrlPolicy.kt`、`safety/DownloadFilePolicy.kt`（纯 JVM 策略 + 16 个单测）；Android 接入点为 `SystemWebViewEngine`、`GmApiBridge`、`BrowserPluginHost`、`DownloadHelper`、`WebActivity`、`MiniAppHost`、`OctopusBridge.navigate`。
- **实际防线**：
  1. **顶层导航**：只放行 http/https；`about:blank` 仅引擎内部清屏放行；file/content/javascript/intent/mailto/tel/market/chrome/blob 等 scheme 一律拦。
  2. **子资源**：放宽到 data/blob/about/ws/wss（内联资源、内联 iframe、长连接是浏览器常态），仍拦 file/content/javascript。
  3. **本地页面**：`MiniAppHost` 的 file:// 按插件沙箱根（filesDir 的 `plugins/<id>/`、`generated_apps/<id>/` 与 `android_asset/plugins/<id>/`）收窄；`OctopusBridge.navigate` 增加 canonicalPath 校验，`../` 逃逸到 shared_prefs/databases 一律拒绝。
  4. **jsBridge**：改为文档级授权——只有「该 URL 实际命中用户脚本、桥脚本确实会被注入」的 document 才能调用 13 个 `@JavascriptInterface` 方法；未授权或 origin 变化一律 fail-closed。`openInTab` 只允许 http/https，callbackId 改为 JSON 转义，iframe 内裸接口做 best-effort 移除。**设计结论：导航白名单是 scheme 白名单，不是域名白名单**（浏览器必须能上任意公网）；域名维度的约束落在 jsBridge/userscript 注入面。
  5. **下载**：引擎与 UI 双层都过 `DownloadFilePolicy.decide`：只允许 http/https 直链；文件名剥路径分隔符、控制字符、Windows 保留名、NTFS 数据流、前导点，超长截断保留扩展名；apk/dex/so 等可执行扩展名只标记审计，不阻断。
- **验证记录**：`:app:testDebugUnitTest` → 124 类 / **1374 测试** 全绿；`:app:detekt`、`:app:lintDebug` → BUILD SUCCESSFUL。
- **残余与后续**：`addJavascriptInterface` 仍会把接口注入所有 frame，跨域 iframe 的裸接口访问目前只在 JS 层 best-effort 屏蔽；彻底方案是迁移到 `WebViewCompat.addWebMessageListener`（可按 origin 绑定）。不阻塞当前发布，列入浏览器加固后续项。

### P1-8 剩余　母体链路 `ws://` → `wss://`

- **现状证据**：母体 `ws://` 明文链路未迁移；LAN 无一次性配对与设备身份。
- **落地位置**：母体连接层 + `network_security_config.xml`（注意该文件仍 **有意** 允许明文以支持动态 LAN IP，改造需区分"已知公网域已禁明文"与"LAN 动态"两类）。
- **验收标准**：① 公网母体走 `wss` 且校验证书；② LAN 保持可用但引入一次性配对码 + 设备公钥绑定；③ 消息级完整性（防中间人重放）。
- **已知取舍**：一次性配对会带来首连摩擦，需要 UI 引导，属**产品决策点**而非纯技术项。

### P1-5 剩余（当前刻意不做，需明示）

- ① STDIO 命令签名/来源证明；② 子进程工作目录约束。
- 现状防线：`argv[0]` 必须为白名单裸命令名 + 参数/环境变量过滤 + Android 沙箱（App UID），保存期与连接期双重强制。
- **演进触发条件**：只有当 Echo 允许"第三方分发 MCP 服务器配置"时，"来源证明"才从"可选"变为"必须"。当前不做是合理的。

---

## 4. P2 发布可信（工程侧最致命短板）

> 这是本轮唯一一条"零基础、但对外分发前必须补全"的路径。因为 Echo 跑本地，用户装到的包必须能自证来源；当前 L1 代码已就位，但**首次带 Secrets 的 Release 实跑之前，尚不能对外声称发布链已生效**。

### P2-11　可验证 release（拆成 5 个独立可交付项）

| 子项 | 现状 | 落地位置 | 验收标准 |
|---|---|---|---|
| 11a 签名 release 产物 | ✅ 代码已落地（待 Secrets 实跑） | `.github/workflows/release.yml` | tag 触发 → `bundleRelease -PechoBundle` + 分 ABI `assembleRelease`；keystore 走 Secrets；产物含 AAB + 各 ABI APK；APK 已验 v3（默认 minSdk）与 v2（显式 `--min-sdk-version 24`） |
| 11b SHA-256 清单 | ✅ 代码已落地 | release job 生成并在发布前 `sha256sum -c` 自检 | 所有产物有哈希；`SHA256SUMS.txt` 随 Release 提供 |
| 11c SBOM | ✅ 代码已落地 | `anchore/sbom-action` 生成 SPDX JSON | 每次 release 附 SBOM，可追溯 Chaquopy/Python/llama.cpp 依赖 |
| 11d 来源可证明 | 🟡 v2+v3 签名 + GitHub attestation 已完成；Code Transparency 未接 | Play App Signing + **Code Transparency** | 用户可独立验证 APK 由持有密钥者签发 |
| （可选）11e 可复现构建 | 🟡 文件名已确定化；两次全量构建 SHA-256 仍不同，待定位 R8/Chaquopy/签名差异 | 固定 toolchain/依赖锁 + 二方复现脚本 | 相同 commit 可产出字节一致（或差异可解释）的 APK |

**为什么 11d 单独列**：Muse 的核心发行优势正是 Code Transparency，这是 Echo 与它在"发行身份"上差距最大的一项，也是**唯一能靠工程补齐、无需产品妥协**的差距。

### P2-12　9527/9528 安全回归自动化

- **现状证据**：Bearer 鉴权、来源标记、错误返回等均无自动化测试；且本机 Robolectric 因 `conscrypt_jni` 缺原生库 + `'posix:permissions'` 问题**无法启动**（已在 `PlanArtifactTest` 复现，属环境既存问题）。
- **落地位置**：修 Robolectric 环境（Linux runner 或补 native runtime）→ 新增 `androidTest` 覆盖 9527/9528。
- **验收标准**：① 无 token 访问 → 401；② 错误/过期 token → 401 且不泄漏内部信息；③ 来源标记正确注入；④ 绑定网卡范围符合预期；⑤ CORS 预检符合白名单。
- **前置**：先在 CI（ubuntu）上跑通 Robolectric，绕开 Windows native 库问题。

### P2-13　MCP 全生命周期测试

- **现状证据**：动态 `mcp_*` 注册/断开/重连/同名覆盖/恶意 schema/工具描述注入 均无测试。
- **落地位置**：`app/src/test` 扩测 + 少量 `androidTest`。
- **验收标准**：① 恶意 schema（超大/循环/保留字段）被拒；② 工具描述注入（prompt injection in description）经隐私扫描 + 风险声明；③ 同名覆盖走统一风险策略而非静默 LOW；④ 断线重连不残留半注册工具。

### P2-14　五场景策略矩阵测试 ✅ 已完成（纯 JVM 部分）

- **落地位置**：`app/src/test/java/com/apk/claw/android/octopus_mobile/safety/ScenarioMatrixTest.kt`（7 个测试，逐格断言 32 格笛卡尔积）。
- **实际维度澄清**：`SourceGatePolicy.actionFor` 只有 **3 个输入维度**（权限模式 × 来源可信度 × 风险等级）+ 1 个策略内开关（`untrustedHighRiskHardGate`）。路线图原先写的"前后台／无 UI"**不是**该函数的输入维度——那部分语义在 `ApprovalFlow.requestApproval`（`context == null` → auto-deny；`CountDownLatch` 30s 超时 → auto-deny）与 `ActivityUtils.getTopActivity()` 回落，属 Android 运行时，本机 Robolectric 无法启动（见 P2-12 前置），因此该两项只能随 P2-12 在 CI 补齐。
- **验收标准**：✅ 四维全枚举逐格断言 `actionFor(...)`；✅ 维护契约用测试强制——新增 `PermissionMode` 枚举值 / 新增 `ToolRiskPolicy.RISK_*` 常量 / 增删输入维度，矩阵都会立即失败（靠 `PermissionMode.entries` 与 `DECLARED_RISKS` 集合做探针，而非硬编码数量）。
- **核心安全断言**：`hard gate changes exactly one cell` —— `untrustedHighRiskHardGate` 的影响范围必须**恰好**是「FULL_POWER + 不可信来源 + 高危」一格。这条断言专门防未来把硬闸门误扩到 APPROVAL 或中危格（那会静默改变既定语义）。
- **显式化的设计取舍**：`full power short circuits medium risk by design` —— 满血模式下 `mediumRiskAction` 被 `trustAllSources` 短路，收紧为 `BLOCK` 也不生效。这是设计语义而非缺陷，测试把它钉住以免后来者误判。
- **验证记录**：P2-14 落地时 `:app:testDebugUnitTest` → 122 类 / **1358 测试** 全绿（本次 +7）；P1-10 接入后的收尾基线为 124 类 / **1374 测试** 全绿，`:app:detekt`、`:app:lintDebug` 均 BUILD SUCCESSFUL。

---

## 5. P3 治理与策略演进（从"写死"到"可运营"）

### P3-1　风险策略单一源 + 版本化

- **现状证据**：P0 已把"风险名单三重漂移"统一；`SourceGatePolicy` 已抽为纯函数并写了决策表 KDoc。**这是最好的起点**——策略已经是数据，不再是散落逻辑。
- **落地位置**：把策略从代码常量升级为**带版本号的策略文件**（如 `policy-v1.json`，含 `untrustedHighRiskHardGate` 等不可关闭项标记）。
- **验收标准**：① 策略有显式版本；② 不可关闭项无法被配置覆盖；③ 策略变更走评审 + 单测锁定。

### P3-2　策略远程下发与灰度/回滚（**高风险，需谨慎**）

- **动机**：Muse 靠云端下发能力；Echo 若要快速响应新型攻击，也需要"不发版即可收紧策略"。
- **红线**：**只允许"收紧"不允许"放宽"**；远程下发的策略不得覆盖硬闸门与不可关闭项。
- **落地位置**：新增策略拉取通道（复用现有 9527/9528 鉴权模型）+ 签名校验 + 本地回滚基线。
- **验收标准**：① 篡改签名 → 拒绝应用；② 下发放宽项 → 被硬闸门覆盖；③ 应用后异常 → 一键回滚到本地基线；④ 离线可降级为内置策略。

### P3-3　动态工具风险自声明（承接 P2-13）

- **现状证据**：动态注册的 `mcp_*` 此前默认 LOW（P0 已改为默认 HIGH，属于止血）。真正的解法是**让 MCP 服务器自声明风险等级**，并纳入统一策略校验。
- **落地位置**：`McpServerConfig` 增加 risk 声明字段 + `ToolRiskPolicy` 校验（自声明不得低于策略下限）。
- **验收标准**：① 未声明 → 默认 HIGH；② 声明低于下限 → 被抬回下限；③ 声明可审计、可追溯来源。

---

## 6. 架构演进（战略层，非近期）

### 6.1 是否要做"云执行面"（对齐 Muse 的双执行面）

| 维度 | 纯本地（现状） | 引入可选云执行面 |
|---|---|---|
| 数据出境 | 零 | 取决于工具，需显式同意 |
| 重活能力（长时任务/大模型） | 受设备算力限制 | 可承载 |
| 隔离强度 | 依赖本地硬闸门 | 一次性 VM，隔离天然更强 |
| 攻击面 | 母体链路 + 本地工具 | 母体链路 + 云链路 + 云端租户边界 |
| 合规 | 简单 | 需处理数据驻留/删除证明 |

- **建议路径**：**不做全量云化**，而是"**可选云执行面 + 默认关闭**"，由 HIGH 风险工具与长时任务显式选择。这样保留 Echo 的核心卖点（数据不出机），同时补上算力天花板。
- **验收标准（若启动）**：① 云面默认关闭；② 每类数据出境有独立同意项；③ 云端任务结束即销毁并给出证明；④ 本地硬闸门对云面同样生效（不接受"上了云就绕过策略"）。

### 6.2 跨端能力（Muse 的生态优势）

- Muse 强在生态成熟度（8 类数据 connector）。Echo 的对应路径是 **MCP 生态**：把 `mcp_*` 从"能连"推进到"可分发、可声明、可审计"（依赖 P2-13 + P3-3）。
- **不建议**：为追 connector 数量而快速扩张导出组件（Echo 当前 `exported=true` 仅 5 个 vs Muse 28 个，这是优势不是劣势）。

---

## 7. 推荐执行顺序（依赖图）

```
P0-1 审批回路 ─────────────┐（阻塞闸门可用，必须先做）
                            │
P1-10 WebView 收口 ─────────┤
P1-8  wss + 配对 ───────────┤（可与 P2 并行）
                            ▼
P2-11 可验证 release ──► P2-12/13/14 测试基建 ──► P3-1/2/3 策略运营
        ▲                                            ▲
        └──────── 对外分发前必须完成 ────────────────┘
                            │
P3/6 架构演进（云执行面/跨端）── 战略层，触发条件：算力瓶颈或生态需求被验证
```

**判据**：
- 若**只自用/内部分发** → P1-10 + P2-11 的验签/SHA 自证已经足够显著提升安全姿态。
- 若**对外分发** → P2-11a-c 必须先配 Secrets 并完成首次实跑，P2-11d 与 P2-12~14 紧随其后。
- 若**要做云能力** → 必须先完成 P3-1/P3-2（策略可管控），否则"云面绕过本地闸门"会成为最大风险。

---

## 8. 与 Muse 的差距收敛表（升级后预期）

| 能力 | Muse | Echo 现状 | 补齐路径 | 补齐后 |
|---|---|---|---|---|
| 发行身份/可验证 | Code Transparency | 🟡 v2+v3 + attestation 已有；Code Transparency 未接 | P2-11d | ✅ 对齐 |
| 运行时隔离 | 云 Confidential VM | 本地硬闸门 | 6.1（可选） | ⚠️ 取舍不同 |
| 权限面 | 65 | **27** | 无需补 | ✅ 保持优势 |
| 导出组件 | 28 | **5** | 无需补 | ✅ 保持优势 |
| 工具治理 | 弱 | **强（集中策略+审计+熔断）** | P3-1/2 深化 | ✅ 扩大优势 |
| 数据不出机 | ❌ | **✅** | 6.1 需守住 | ✅ 核心卖点 |
| 发布完整性 | 有 | 🟡 L1 代码已补齐，待 Secrets 实跑 | P2-11 | ✅ 补齐 |
| 生态成熟度 | 强 | 弱 | P2-13 + 6.2 | ⚠️ 长期 |

---

## 9. 验证边界声明（沿用主报告口径）

- 本文件所有"Muse 有/无"结论均来自**静态解包**，未做动态运行验证。
- Echo 侧现状均为**源码级**证据（文件路径+行号），未做 release 混淆后的运行时验证。
- P0-1 已由 `94b4d0c` 做了代码级修复；仍缺一次真机 HIGH 工具批准/拒绝回归。
- `lintVitalRelease` 会提示 baseline 与 release variant 不一致：这是 fatal-only 分析对全量 baseline 的正常噪声；真正的增量门是 CI 的 `lintDebug`。
