# 打磨优化超过 Operit Spec

## Why

基于上一轮对 Operit v1.12.0(2026-07-01)的核实,Octopus Mobile 在"移动工作站"赛道(Linux 沙箱/终端/代码编辑/Git/UI 自动化/模块化/记忆库/角色卡/MCP 生态/自进化)全面落后 Operit;真正领先的只有"跨通道通讯 + 全屏语音通话"。但同级目录的母本 `octopus-agent`(8500+ tests,Beta v0.2.0)已为 mobile 量身打造了 **Tentacle 触手 + 30 个移动 SKILL.md + MCP 服务端 + Regeneration 自进化 + Plan 模式 + Approval Gate + codebase 检索 + diff parser** 等成熟能力,且这些能力 **Operit 完全没有**。本 spec 通过下沉母本差异化能力 + 补齐 Operit 已有短板 + 打磨现有问题,让 Octopus Mobile 在"跨端编排 + 代码工作流 + 安全信任 + 自进化"四个维度反超 Operit,而不是在"单机全能"赛道跟 Operit 正面竞争。

## 战略定位(参考 `octopus-os/docs/OS_DIFFERENTIATION.md`)

**不在 Operit 强势的"移动工作站"赛道正面对决,而是变成不同品类**:Operit 是"单机全能助手",Octopus Mobile 是**"母本 Runtime 的移动触手 + 手机版 Codex + 跨通道通讯中枢"三合一**。差距不来自"功能更多",来自 **mobile 能做、Operit 做不了也不该做的事**:
1. 跨端编排(1 Runtime 控 N 手机 + M 电脑)— Operit 单机无法
2. MCP 服务端(手机能力暴露给 Claude Desktop/Cursor)— Operit 只能被调用
3. 自进化复利(跑得越久越聪明)— Operit 工具硬编码
4. Plan 模式危险操作确认 — Operit 直接执行
5. 母本 codebase 检索下沉(手机版 Codex)— Operit 代码工作流薄弱

## What Changes

### Phase A — 差异化下沉(母本独有,Operit 无法对标的护城河)

- **Tentacle WS 通路接通**:mobile 端实现/补全 `OctopusMobileClient`(WebSocket 客户端),对接母本 `runtime/tentacle/transport/ws_server.py`,实现「设备注册 + 心跳 + 工具执行通路 + 屏幕状态上报」4 个原语。参考 `runtime/tentacle/mobile/kotlin_ref/PcScreenReceiver.kt`。这是 mobile 作为母本"触手"的原生能力,不是移植。
- **MCP 服务端**:mobile 把 40+ Android 工具(tap/swipe/screenshot/run_shell 等)暴露为 MCP server(JSON-RPC 2.0 over WebSocket),让任意 MCP 客户端(Claude Desktop / Cursor / VSCode / 母本 Runtime)可直接调用手机能力。复用母本 `runtime/tentacle/mobile/mcp_server.py:serve_stdio()` 协议规范。
- **SKILL.md 协议 + 技能热加载**:mobile 端实现 Kotlin 版 `SkillMdLoader`(参考母本 `runtime/execution/suckers/loader/md_loader.py`),支持解析 YAML frontmatter + Markdown body + scripts/*;通过 `skill install <url>` 从云端拉新技能热加载(无须更新 APK)。初期复用母本已写好的 30 个移动 SKILL.md(`runtime/tentacle/mobile/skills/`)。
- **Plan 模式 + Approval Gate 4 档**:mobile 端实现 `PermissionMode` 枚举(default / acceptEdits / bypassPermissions / plan)+ `ApprovalProvider` 接口(Kotlin 版,参考母本 `runtime/safety/approval/approval_gate.py`);危险操作(支付 / 删除 / 系统设置 / 短信发送)强制走 plan 模式,用户确认后才执行。`exit_plan_mode` skill 协议借鉴母本 `runtime/execution/suckers/plan_mode.py`。

### Phase B — 代码工作流下沉(手机版 Codex 关键,补短 Operit)

- **Codebase 检索(BM25 + dense 融合)**:mobile 端实现 Kotlin 版 `CodeIndex`(参考母本 `runtime/memory/hemolymph/code_index.py`),持久化到 `data/code_index.db`(SQLite + float32 向量,纯 stdlib 解码无重依赖);提供 `search_code` 工具给 LLM,让 LLM 能高效理解大型项目(对标 Codex 的 codebase semantic search)。
- **Diff parser + diff view UI**:mobile 端实现 Kotlin 版 `DiffParser`(参考母本 `runtime/protocol/diff_parser.py`,解析 unified diff → `FileChange` 列表);Compose + Markwon 渲染轻量 diff view(支持 accept/reject 单 hunk)。这是手机版 Codex 体验闭环。
- **Git 工具集**:新增 4 个工具 `git_clone` / `git_commit` / `git_push` / `github_create_pr`(在 LinuxSandbox 内调 git CLI,封装为 high-level tool);解锁"克隆仓库 → LLM 改 → 提 PR"完整 Codex 工作流。

### Phase C — 补短 Operit + 打磨现有(用户可见体验追平)

- **MNN 本地模型集成**:Operit 已有 MNN,mobile 仅 llama.cpp。集成 MNN Android SDK(纯 C++,无 Java 依赖,体积小),作为 `LocalLlmClient` 的新后端;借鉴母本 `runtime/platform/ui/cookbook_router.py` 的"硬件检测 + 模型推荐 + 一键下载"思想。
- **死代码清理(基于 PROJECT_ANALYSIS P2)**:删除/接线 `BrainModeSelector.decide` / `ConnectionStateMachine` / `CanaryManager` / `EvolutionEngine.deepEvolve` / `MpvController` 等死代码;同步修正 CODE_WIKI 与 AUDIT_REPORT 的过时结论。**这是"宣传面跑在接线面前面"问题的诚实化**。
- **工具并行执行**:借鉴母本 `runtime/execution/swarm/runtime.py` 的 DAG 依赖感知并行,让 mobile 的只读工具(如 `screenshot` / `file_read` / `http_get`)可并行执行,提升响应速度。Operit 已有,mobile 缺。

### 不在本 spec 范围(留独立 spec)

- Gradle 模块拆分(:core/:native/:tool/:channel/:agent/:app)— 风险大、不阻塞反超,独立 spec 处理
- 桌面化(代码编辑器 UI / 终端 UI / 文件管理器 UI)— Operit 强项,正面对决不划算,mobile 走"远程 Web UI"(母本 `runtime/platform/ui/`)路线更优
- 角色卡 / 桌宠 / 主题系统 / 绘图工具包 — Operit 强项且非 mobile 战略方向,跳过
- Tasker 集成 / Token 统计饼图 / Java Bridge — 非关键路径

## Impact

### Affected code(新增 + 修改)

**新增**:
- `app/src/main/java/com/apk/claw/android/tentacle/OctopusMobileClient.kt`(WS 客户端)
- `app/src/main/java/com/apk/claw/android/tentacle/DeviceRegistration.kt`(设备注册 + 心跳)
- `app/src/main/java/com/apk/claw/android/tentacle/ScreenRelay.kt`(屏幕状态上报)
- `app/src/main/java/com/apk/claw/android/mcp/McpServer.kt`(MCP 服务端)
- `app/src/main/java/com/apk/claw/android/mcp/JsonRpcDispatcher.kt`(JSON-RPC 2.0 分发)
- `app/src/main/java/com/apk/claw/android/skill/SkillMdLoader.kt`(SKILL.md 解析器)
- `app/src/main/java/com/apk/claw/android/skill/SkillInstaller.kt`(技能热加载)
- `app/src/main/java/com/apk/claw/android/agent/PermissionMode.kt`(4 档权限枚举)
- `app/src/main/java/com/apk/claw/android/agent/ApprovalProvider.kt`(权限 Provider 接口)
- `app/src/main/java/com/apk/claw/android/agent/ApprovalGate.kt`(审批门)
- `app/src/main/java/com/apk/claw/android/code/CodeIndex.kt`(BM25 + dense 融合检索)
- `app/src/main/java/com/apk/claw/android/code/DiffParser.kt`(unified diff 解析)
- `app/src/main/java/com/apk/claw/android/code/DiffViewActivity.kt`(diff view UI)
- `app/src/main/java/com/apk/claw/android/tool/impl/{GitClone,GitCommit,GitPush,GithubCreatePr}Tool.kt`
- `app/src/main/java/com/apk/claw/android/agent/llm/MnnLlmClient.kt`(MNN 后端)

**修改**:
- `app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt`(集成 ApprovalGate + 工具并行)
- `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt`(集成 Plan 模式 + 工具并行)
- `app/src/main/java/com/apk/claw/android/ClawApplication.kt`(启动 Tentacle client + MCP server)
- `app/build.gradle.kts`(加 MNN 依赖)
- `app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt`(新增 PermissionMode 字段)
- 删除:`BrainModeSelector.kt` / `ConnectionStateMachine.kt` / `CanaryManager.kt` / `MpvController.kt` 等

### Affected specs
- `modularize-providers-i18n`(已完成,本 spec 不冲突)
- `add-remote-workspace-programming`(已完成,本 spec 不冲突)

### 不变量(必须保留的母本规则)
- **INV-T1**:所有 touch 操作(tap/swipe/long_press/input_text)必经 Approval Gate
- **INV-T2**:mobile 自身安全规则 10 条保留(prompt 层兜底)
- **INV-T3**:设备锁由母本 Runtime 统一管理,mobile 只执行
- **INV-T4**:LOCAL_ONLY 模式(无母本连接)跟现有 mobile 完全一致,无体验降级

## ADDED Requirements

### Requirement: Tentacle WS 通路
The system SHALL connect to the octopus-agent母本 Runtime via WebSocket, implement device registration + heartbeat (30s) + tool execution通路 + screen state reporting. When母本下发 `tool/execute`, mobile SHALL execute via existing `ToolCallDispatcher.withUntrustedSource{}` 闸门(INV-T1)。

#### Scenario: 注册并保持在线
- **WHEN** mobile 启动且配置了母本 Runtime URL(如 `wss://runtime.example.com/ws`)
- **THEN** mobile 发送 `device/hello` 包(含 device_id / capabilities / auth_token)
- **AND** 收到母本 `device/welcome` 后进入 ONLINE 状态
- **AND** 每 30s 发送 `device/heartbeat`,5s 内收到 ack;3 次未收到则重连(指数退避)

#### Scenario: 母本下发工具调用
- **GIVEN** mobile 在线
- **WHEN** 母本下发 `tool/execute` 帧(tool_name="tap", params={x:540,y:1200})
- **THEN** mobile 经 `ToolCallDispatcher.withUntrustedSource{}` 闸门执行
- **AND** 若当前 PermissionMode=plan 或工具标记 HIGH_RISK,弹出 Approval UI 等用户确认
- **AND** 执行后回 `tool/result` 帧(含 result / error / audit_chain_hash)

#### Scenario: LOCAL_ONLY 无母本
- **WHEN** 用户未配置母本 URL 或母本不可达
- **THEN** mobile 退化为完全本地模式(INV-T4),所有现有功能不受影响
- **AND** UI 不显示"已连接母本"状态

### Requirement: MCP 服务端
The system SHALL expose 40+ Android tools as an MCP server(JSON-RPC 2.0 over WebSocket on port 9528), allowing any MCP client(Claude Desktop / Cursor / VSCode / 母本 Runtime)to call mobile capabilities.

#### Scenario: Claude Desktop 调用手机截屏
- **GIVEN** mobile 运行中,MCP server 监听 `ws://0.0.0.0:9528/mcp`
- **WHEN** Claude Desktop(配置 MCP server URL)发送 `tools/call` 请求(name="screenshot", args={})
- **THEN** mobile 经 Approval Gate 检查(截屏是 LOW 风险,自动通过)
- **AND** 调用 `ScreenshotTool.execute()` 返回 base64 图片
- **AND** Claude Desktop 收到 `tools/call` 响应并展示

#### Scenario: 高危工具需用户确认
- **WHEN** MCP 客户端调用 `send_sms`(HIGH_RISK)
- **THEN** mobile 弹出 Approval UI 显示"将发送短信至 xxx,内容 yyy"
- **AND** 用户拒绝后,mobile 返回 `error: permission_denied`
- **AND** 用户 60s 内无响应,自动拒绝

### Requirement: SKILL.md 协议 + 技能热加载
The system SHALL support loading skills from SKILL.md files(YAML frontmatter + Markdown body + optional scripts/*), and support `skill install <url>` to hot-load new skills from cloud without APK update.

#### Scenario: 加载内置技能
- **WHEN** mobile 启动
- **THEN** 扫描 `assets/skills/*.md` 与 `filesDir/skills/*.md`
- **AND** 解析每个 SKILL.md 的 YAML frontmatter(name / description / allowed_tools / atomic / trusted_source)
- **AND** 注册为可被 LLM 调用的 skill(等价于工具,但有更丰富的 prompt body)

#### Scenario: 热加载云端技能
- **GIVEN** 用户在设置中点击"安装技能"输入 URL `https://example.com/skills/android_taobao_add_to_cart.md`
- **WHEN** mobile 下载并解析 SKILL.md
- **THEN** 校验 frontmatter 合法性 + trusted_source 签名(若有)
- **AND** 落盘到 `filesDir/skills/android_taobao_add_to_cart.md`
- **AND** 立即可被 LLM 调用,无须重启 app

### Requirement: Codebase 检索(BM25 + dense 融合)
The system SHALL provide a `search_code` tool that indexes a project directory and returns relevant code chunks via BM25 + dense-vector fusion ranking.

#### Scenario: LLM 检索代码
- **GIVEN** 用户在 mobile 上打开了项目 `/sdcard/MyApp/`
- **WHEN** LLM 调用 `search_code` 工具(query="where is login logic", path="/sdcard/MyApp/")
- **THEN** mobile 首次调用时构建索引(BM25 token + dense embedding,持久化到 `data/code_index.db`)
- **AND** 返回 top-5 代码片段(含文件路径 + 行号 + 代码内容)
- **AND** 后续调用复用索引(仅增量更新变更文件)

### Requirement: Diff parser + diff view UI
The system SHALL parse unified diff output into structured FileChange list, and render a diff view in Compose for user to accept/reject each hunk.

#### Scenario: LLM 改完代码展示 diff
- **GIVEN** LLM 通过 `file_write` 工具修改了 `/sdcard/MyApp/src/Main.kt`
- **WHEN** 修改完成
- **THEN** mobile 解析原文件 vs 新文件的 unified diff
- **AND** 弹出 DiffViewActivity 展示 +/- 行(绿色新增/红色删除)
- **AND** 用户可逐 hunk accept/reject,reject 的 hunk 还原原内容

### Requirement: Git 工具集
The system SHALL provide 4 git tools: `git_clone` / `git_commit` / `git_push` / `github_create_pr`, executed inside LinuxSandbox.

#### Scenario: 克隆仓库
- **WHEN** LLM 调用 `git_clone`(url="https://github.com/foo/bar.git", path="/sdcard/Code/bar")
- **THEN** mobile 在 LinuxSandbox 内执行 `git clone`,返回成功/失败
- **AND** 克隆后的路径可被 `search_code` 索引

### Requirement: Plan 模式 + Approval Gate 4 档
The system SHALL support 4 permission modes: `default`(高危需确认) / `acceptEdits`(文件编辑自动通过) / `bypassPermissions`(全部自动通过,需用户显式开启) / `plan`(只读 + 出方案,禁止执行写操作)。

#### Scenario: 用户开启 plan 模式审阅方案
- **WHEN** 用户在设置中切换到 plan 模式
- **AND** 用户问"帮我把淘宝购物车里的东西清空"
- **THEN** LLM 调用工具检索淘宝购物车页面,但**禁止调用 tap/swipe 等写工具**
- **AND** LLM 输出方案:"将依次点击购物车 → 全选 → 删除"
- **AND** 用户确认后,LLM 调用 `exit_plan_mode` skill,mobile 切换到 default 模式继续执行

#### Scenario: 高危操作强制审批
- **GIVEN** 当前 PermissionMode=default
- **WHEN** LLM 调用 `send_sms`(HIGH_RISK)
- **THEN** ApprovalGate 弹出 UI 显示"将发送短信至 10086,内容 '订阅服务'"
- **AND** 用户确认后才执行;拒绝则 LLM 收到 `permission_denied` 错误

### Requirement: MNN 本地模型后端
The system SHALL integrate MNN Android SDK as a new backend for `LocalLlmClient`, in addition to existing llama.cpp backend.

#### Scenario: 用户选择 MNN 模型
- **WHEN** 用户在 LLM 配置页选择 provider=LOCAL, model="MNN-Qwen2-1.5B"
- **THEN** mobile 加载 `filesDir/models/mnn/Qwen2-1.5B/` 下的 MNN 模型
- **AND** 推理在独立进程(避免 native crash 影响 main)
- **AND** 推理结果与 llama.cpp 后端行为一致(均通过 BaseLangChain4jLlmClient)

### Requirement: 工具并行执行
The system SHALL execute read-only tools in parallel when they have no dependencies, improving response speed.

#### Scenario: LLM 并行检索
- **WHEN** LLM 在一轮中调用 3 个只读工具:`screenshot` + `file_read` + `http_get`
- **THEN** 3 个工具并行执行(线程池大小 4)
- **AND** 等全部完成后一并返回结果给 LLM
- **AND** 写工具(tap/file_write/send_sms)不参与并行,串行执行

### Requirement: 死代码清理
The system SHALL remove or wire up dead/aspirational code identified in PROJECT_ANALYSIS P2, and sync documentation to match actual wiring.

#### Scenario: 清理后验证
- **WHEN** 完成清理
- **THEN** `BrainModeSelector.decide` / `ConnectionStateMachine` / `CanaryManager` / `MpvController` / `deepEvolve` 等死代码被删除或接线
- **AND** CODE_WIKI.md 与 AUDIT_REPORT.md 同步更新,不再描述不存在的能力
- **AND** `./gradlew assembleDebug` 通过(终端恢复后验证)

## MODIFIED Requirements

### Requirement: ToolRegistry
ToolRegistry 在 executeTool 7 道闸门基础上,新增 ApprovalGate 作为第 8 道闸门(在 SafetyGate 之后、audit 之前)。ApprovalGate 根据 PermissionMode + 工具风险等级决定是否拦截等待用户确认。

### Requirement: DefaultAgentService
DefaultAgentService 的 ReAct 循环新增 plan 模式支持:当 PermissionMode=plan 时,工具调用层拦截写工具,只允许读工具;LLM 输出 `exit_plan_mode` skill 调用时,切换到 default 模式继续执行(借鉴母本 mid-turn 切换)。

### Requirement: AgentConfig
AgentConfig 新增 `permissionMode: PermissionMode` 字段(默认 default),持久化到 KVUtils。

## REMOVED Requirements

### Requirement: BrainModeSelector / ConnectionStateMachine / CanaryManager / MpvController / EvolutionEngine.deepEvolve
**Reason**: PROJECT_ANALYSIS P2 确认这些是死代码/愿景代码,文档叙事与实际接线存在系统性落差,损害项目可信度。
**Migration**: 出站任务委派给母本的功能由 Tentacle WS 通路(本 spec Phase A)承担;本地/远程大脑切换由"是否配置母本 URL"隐式决定,不需要独立 BrainModeSelector。其他能力直接删除。
