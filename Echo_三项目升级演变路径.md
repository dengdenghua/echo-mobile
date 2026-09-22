# Echo 家族协同升级演变路径（mobile / ai / os 三项目统观）

> 日期：2026-09-22
> 范围：`echo-mobile`（`E:\echo mobile`）、`echo-ai`（`D:\echo-ai`）、`echo-os`（`D:\飞牛os\echo-os`）
> 配套：`Echo_Mobile_升级演变路径.md`（mobile 单项目版）、`ECHO_vs_MUSE_对比报告.md`（对比版）
> 本文件回答的是：**把三个项目放在一起看，升级路径该怎么排。**

---

## 0. 先认清：这不是三个并列产品，是一个有分工的家族

| 项目 | 路径 | 家族定位 | 语言 | 追踪文件 | 版本 |
|---|---|---|---|---|---|
| **echo-mobile** | `E:\echo mobile` | Android 自动化客户端 → 家族里的 **Tentacle（触手）** | Kotlin | 1010 | versionCode 14 / 1.0.0 |
| **echo-ai** | `D:\echo-ai` | Agent 运行时独立发行形态（`echo-ai-runtime`） | Python | 8018 | 0.1.0 |
| **echo-os** | `D:\飞牛os\echo-os` | **唯一发行单元**：Agent + 桌面 + 文件 + 系统能力 | Python + TS | 8446 | 0.2.0 |

依赖方向（有源码级证据）：

```text
echo-mobile  ──ws://<runtime>:8765 tentacle 通路──►  runtime
   (触手/执行器)      (tool/execute 帧 + authToken)      ▲
                                                          │ 内建
echo-os  ────────────────────────────────────────────────┘
   (发行单元)   appliance/agent_api/* → runtime/*
echo-ai  ────────────────────────────────────────────────┘
   (运行时独立发行)
```

**证据**：

- `ClawApplication.kt:159-176` —— 注释原文「Tentacle WS 通路:连母本 Runtime,接收 tool/execute 帧」，经 `TentacleManager.start(runtimeUrl, authToken)` 接入，受 `LOCAL_ONLY` 模式开关约束（INV-T4）。
- `runtime/platform/ui/app.py:74` —— `tentacle_ws_port: int = 8765`，即 mobile 默认 `ws://10.0.2.2:8765` 指向端口。
- `docs/AGENT_OS_BOUNDARY.md` —— `devices` 领域：「Tentacle 协调器和活跃设备桥」；设备功能必须走 `appliance/agent_api/`。
- `AppViewModel.kt:175` —— `tentacleId = "${Build.BRAND}_${Build.MODEL}"`。

**⚠️ 已发现的家族隐患：runtime 重复。** `D:\echo-ai\runtime` 4034 文件、`D:\echo-os\runtime` 5072 文件，抽样文件哈希**完全相同**（`platform/ui/app.py`、`pet/udp_bridge.py`）。也就是说 echo-ai 与 echo-os 共享同一份 runtime 代码但各自持有副本，走的是"复制而非依赖"。这是后面 §5 的一条独立治理项。

---

## 1. 三项目的工程成熟度是**不对称**的（这是本轮最重要的发现）

| 维度 | echo-mobile | echo-ai | echo-os |
|---|---|---|---|
| CI workflow 数 | 3 | 8 | **15** |
| 单元测试文件 | 124 | 大量（tests/） | 大量（tests/ + tests/appliance/） |
| 仪器/端到端测试 | **1（模板）** | 有 build-linux/win | 有 build-linux/win/mac + 桌面 smoke |
| **发布流水线** | 🟡 `release.yml` 已落地（待 Secrets 实跑） | ✅ `release.yml` | ✅ `release.yml` + `os-image.yml` + `appliance-release.yml` |
| 版本契约校验 | 🟡 tag == versionName + versionCode 单调递增 | ✅ tag == pyproject == frontend/package.json | ✅ 同左 |
| 同 SHA 前置证据门 | 🟡 release job 内重跑单测/lint/detekt | ✅ behavioral-evidence | ✅ behavioral-evidence |
| **SBOM / provenance / attestation** | 🟡 SBOM + GitHub attestation 已落地 | 部分（provenance json） | ✅ `provenance: mode=max` + `sbom: true` + `attestations: write` |
| SHA-256 清单 | ✅ `SHA256SUMS.txt` + 发布前自检 | ✅ `sha256sum` 校验 | ✅ `*.sha256` 清单 |
| 签名 | ✅ APK v2+v3 签名 + AAB JAR 验签 | — | ✅ GPG keyring / 更新签名 / boot gate |

**结论**：家族的 Python 两项目已经建立了严肃的发布纪律；Android 这一端过去是唯一空白，本轮已补上 L1 代码（`.github/workflows/release.yml`），但必须完成一次带 Secrets 的 tag 发布实跑才算真正闭环。

---

## 2. 核心判断：mobile 应该"接入家族已有能力"，而不是继续自己造

这是本轮统观后**改变了原路线图**的地方。

### 2.1 Echo OS 已经有一整套治理模型，mobile 只做出了贫化版

`docs/ECHO_CAPABILITY_CONTRACT.md`（Capability Contract v0.1）定义的五元组：

```text
Agent entitlement   Who may request a privileged mode
System capability   What bounded operation a provider implements
Policy decision     Whether this actor/intent/target is allowed now
Approval token      Short-lived, single-use authority for high-risk execution
Audit event         Durable evidence of the decision and result
```

执行流：`POST /api/appliance/capabilities/decisions → ALLOW | ASK | DENY`，审批令牌**短期、单次使用**，且**绑定 operator + target + action + intent**。

对照 mobile 现状：

| Capability Contract 概念 | mobile 对应物 | 差距 |
|---|---|---|
| Policy decision（ALLOW/ASK/DENY） | `SourceGatePolicy`（CONFIRM/…） | **枚举语义未对齐** |
| Approval token（单次、绑四元组） | 无 | **缺** |
| Audit event（防篡改链） | 有审计（`appliance` 侧称 audit chain） | 需确认链式防篡改 |
| entitlement / capability 分离 | `ToolRiskPolicy` 单层 | **缺分层** |
| 决策走统一 endpoint | `ToolRegistry.executeTool()` 内部判定 | 语义分散 |

### 2.2 所以 P0-1 的正确解法变了

原判断：`SystemApprovalGate` 无参注入 → HIGH 工具全部自动拒绝，**需要接一个弹窗**。

统观后的判断：**不要只为 mobile 写一个孤立弹窗**，而应让 mobile 的审批语义**对齐 Capability Contract**——决策枚举、令牌一次性、绑定目标。这样三端最终能收敛到同一套模型，而不是三套各自演化的审批逻辑。

- **最小可交付**：接通 UI 回调（解除 fail-closed 死锁）——这一步不变，仍是最紧急。
- **正确终局**：mobile 的 `SourceGatePolicy` 决策枚举与 OS 的 `ALLOW/ASK/DENY` 对齐，HIGH 工具走"单次令牌"而非"回调返回布尔值"。

---

## 3. 网络与设备身份：家族已有方案，直接复用

这是**从原路线图里可以省掉一整块设计工作**的地方。

`echo-ai/docs/cloud-edge.md` 已经定义了设备身份体系：

- 云服务器**不运行 Echo Agent**，只跑轻量账号/积分/消息服务 + SQLite；
- 每台设备生成独立 **Ed25519** 私钥，私钥只存本机 `0600` 配置文件；
- 设备先签一次性 **challenge**，换取 **15 分钟** Bearer 令牌；
- 设备被撤销后**立即拒绝**，即使旧令牌未过期；
- **一次性配对码** 10 分钟有效（`POST /api/cloud-edge/pairing-codes`）；
- SSE 实时消息流供 Agent / 订阅页 / 推送消费；
- 默认只发布到 `127.0.0.1:8090`，要求前置 Caddy/Nginx 上 HTTPS。

对照 mobile 的 P1-8（母体 `ws://` → `wss://`、LAN 一次性配对、设备身份、消息完整性）：

> **原路线图说"要设计一次性配对 + 设备身份"。统观后应改为"复用 cloud-edge 已有方案"**：Ed25519 设备密钥、一次性 challenge、短期 Bearer、10 分钟配对码——四件套已经存在且已写进文档，mobile 侧要做的是**对接**而非**发明**。

同时注意一个正在泄漏的点：`SemanticSkillRanker.kt:39-40` 注释里写的是 `ws://host:8765 → http://host:8000`，并注明「仅 loopback/局域网回退,生产环境应配 wss://」——**说明生产环境 wss 是被预期的，但当前默认路径是明文**。

---

## 4. 分层升级路径（跨项目）

### L0 · 立即（跨项目契约对齐）

| 项 | 动作 | 验收 |
|---|---|---|
| **L0-1** | ✅ 已修复（`94b4d0c`）：接通 mobile 审批回路，解除 HIGH 工具全自动拒绝 | 代码级已闭环；真机 HIGH 工具批准/拒绝回归保留为发布前检查 |
| **L0-2** | 把 mobile 决策枚举对齐 OS `ALLOW/ASK/DENY`，并明确 `SourceGatePolicy` 与 Capability Contract 的映射表 | 有映射文档 + 单测锁定；枚举不再各自演化 |
| **L0-3** | 确认 mobile 审计是否具备 OS 的"防篡改链"语义 | 有明确结论（是/否 + 差距） |

### L1 · mobile 工程补齐（向家族发布纪律看齐）

**P2-11 已按 echo-os/echo-ai 的 `release.yml` 模式落地**（见 `.github/workflows/release.yml`），而不是从零设计：

| 抄什么 | 从哪抄 | 落到 mobile |
|---|---|---|
| tag 触发 + 版本契约校验 | `echo-os/.github/workflows/release.yml` | tag == `versionName` == `versionCode` 规则表 |
| 同 SHA 前置证据门 | 同上（`behavioral-evidence`） | 同 SHA 的 CI 全绿才允许 release |
| SBOM + provenance + attestation | `echo-os/.github/workflows/appliance-release.yml`（`provenance: mode=max`、`sbom: true`、`attestations: write`） | APK/AAB 的 SBOM + GitHub attestation |
| SHA-256 清单 | `echo-ai/.github/workflows/build-linux.yml`（`sha256sum`） | `SHA256SUMS.txt` |
| 签名产物 | `echo-mobile` 已有签名配置，只缺流水线 | `bundleRelease` + 分 ABI `assembleRelease` |

**这是家族内"已在别处验证过的作业"，落地风险远低于自研；剩余动作是配置 Secrets 并完成首次 tag 实跑。**

**mobile 另有独立的 WebView 安全收口 P1-10 已完成**（不涉及跨项目契约）：导航/子资源/下载/file 沙箱/jsBridge 文档级授权全部接入统一策略，详见 `Echo_Mobile_升级演变路径.md`。收尾验证：124 类 / **1374 单测** 全绿 + detekt + lint 通过。

### L2 · 网络与设备身份（对接，不发明）

- 复用 cloud-edge 的 Ed25519 + challenge + 15min Bearer + 10min 配对码（§3）。
- `ws://` → `wss://` 迁移；LAN 保留但加配对。
- **判据**：mobile 能与 echo-os 完成一次"配对 → 短期令牌 → 加密 RPC"闭环。

### L3 · 治理单一源（跨三项目）

- 风险策略/Capability 策略统一到单一源；mobile 的 `ToolRiskPolicy`/`SourceGatePolicy` 与 OS 的 capability decisions 共用语义。
- 策略版本化 + **只收紧不放宽** + 本地回滚基线。
- 远程下发必须过签名校验且不得覆盖硬闸门。

### L4 · 架构演进

1. **runtime 去重**（echo-ai vs echo-os）：目前是复制关系、哈希已见分歧风险（4034 vs 5072 文件）。要么择一为源、另一方依赖，要么明确单向同步。
2. **云执行面的取舍**——见 §5。
3. 三端能力契约收敛：mobile 的工具集应能投影为 OS capability registry 的一个子集。

---

## 5. 关于"云电脑 vs 本地"：家族其实**已经给出答案**

用户问过"Muse 是不是跑云电脑、我们跑本地"。把三项目放在一起后，这个答案更完整：

- **Muse**：把 **Agent 本体**放进云 Confidential VM，手机是显示与交互端。
- **Echo 家族**：`cloud-edge.md` 明确写了「**云服务器不运行 Echo Agent**……它只运行一个独立的轻量业务服务和一份 SQLite 数据库」——云端只承担账号、积分、消息、订阅，**Agent 与执行永远在本地**。

也就是说，Echo 不是"暂时没上云"，而是**明确选择不做云端 Agent**，只做"云端业务 + 本地执行 + 设备身份"。这是一个**已经写进架构文档的产品决策**，不是技术欠债。

- **建议保持这条边界**，不要为对齐 Muse 而把 Agent 搬上云——那会同时丢掉"数据不出机"和现有 Capability Contract 的本地权威性。
- 若将来确实需要云算力，正确形态是"**可选云执行面 + 默认关闭**"，且**必须纳入 Capability Contract**（否则会出现"上了云就绕过本地闸门"的最大风险）。

---

## 6. 统一执行顺序

```text
L0-1 接通审批回路 ──► L0-2 枚举对齐 OS Capability Contract ──► L0-3 审计链核对
        │
        ├──► L1 mobile 发布可信（抄家族 release.yml；对外分发硬门槛）
        │
        ├──► L2 网络与设备身份（复用 cloud-edge 四件套）
        │
        └──► L3 治理单一源（策略版本化 / 只收紧 / 可回滚）

L4 架构演进：runtime 去重 · 云执行面（默认关闭）· 能力契约收敛
        ▲
        └── 触发条件：算力瓶颈被验证，或三端契约开始漂移
```

**判据**：

- **只自用 / 家族内跑通** → L0 + L2 收益最大（家族已有方案，落地快）。
- **对外分发 mobile** → L1 是硬门槛，**当前发布完整性为零，且家族其他成员都已具备**。
- **要加云能力** → 先做 L3，再谈云面，否则绕过风险不可控。

---

## 7. 三项目统观后，与 Muse 的对比结论更新

| 能力 | Muse | Echo 家族（统观） |
|---|---|---|
| 发行身份 / 可验证 | Code Transparency | ✅ **有**（echo-os 侧：GPG / attestation / sbom / 镜像签名）；🟡 mobile 侧 L1 已落地（v2+v3 签名 + SBOM + attestation + SHA-256 清单），待 Secrets 实跑 |
| 运行时隔离 | 云 Confidential VM | 本地硬闸门 + Capability Contract（**不同取舍，非缺陷**） |
| 设备身份体系 | 有 | ✅ Ed25519 + challenge + 短期令牌 + 配对码 |
| 单次审批令牌 | 有 | ✅ Capability Contract 已定义（**mobile 侧尚未接入**） |
| 能力契约 | 有 | ✅ v0.1 已发布 |
| 数据不出机 | ❌ | ✅ **家族级明确边界**（cloud-edge 明文规定云端不跑 Agent） |
| 发布完整性（移动端） | 有 | 🟡 **L1 代码已补齐，待 Secrets 实跑** |

**更新后的判决保持一致**：仍然没有单一赢家。但统观三项目后要补一句——**Echo 的"强"是家族级的（治理模型、设备身份、整机签名），"弱"曾集中在 mobile 单点的发布完整性上；L1 已补代码，当前只剩 Secrets 与首次发布实跑。** 下一步优先级应转向 L2（`wss` + 设备身份）与跨项目治理单一源。

---

## 8. 验证边界

- 家族关系判定基于源码引用 + 各仓库 README/架构文档；**未做跨仓库端到端运行验证**。
- runtime 重复结论基于文件数与**抽样**哈希（2 个文件），不等于全量逐文件比对结论。
- echo-os / echo-ai 的发布能力来自 **workflow 源码**，未验证其最近一次实际运行结果。
- mobile 侧结论均为源码级；`SystemApprovalGate` 无参注入已由 `94b4d0c` 代码级修复，真机批准/拒绝回归仍待执行。
- 发布流水线、SBOM、attestation、v2+v3 签名均为本机/源码级验证（v2 在默认 minSdk 下被 apksigner 报 false 属 minSdk 感知行为，已用 --min-sdk-version 24 实测澄清）；GitHub 上首次带 Secrets 的 tag 发布尚未执行。