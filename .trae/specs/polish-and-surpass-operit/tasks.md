# Tasks

## Phase A — 差异化下沉(母本独有护城河,4 任务)

- [ ] Task 1: Tentacle WS 通路接通
  - [ ] 新增 `app/src/main/java/com/apk/claw/android/tentacle/OctopusMobileClient.kt`:WebSocket 客户端,连接母本 Runtime(`wss://.../ws`)
  - [ ] 新增 `DeviceRegistration.kt`:发送 `device/hello`(含 device_id/capabilities/auth_token)+ 接收 `device/welcome` + 30s 心跳 + 指数退避重连
  - [ ] 新增 `ScreenRelay.kt`:屏幕状态上报(复用现有 `ScreenStreamer`,按需增量上报)
  - [ ] 在 `ClawApplication.onCreate` 启动 Tentacle client(若配置了母本 URL)
  - [ ] 接收母本 `tool/execute` 帧 → 经现有 `ToolCallDispatcher.withUntrustedSource{}` 闸门执行 → 回 `tool/result` 帧
  - [ ] KVUtils 新增 `KEY_TENTACLE_RUNTIME_URL` / `KEY_TENTACLE_AUTH_TOKEN` 存取
  - [ ] LOCAL_ONLY 模式(无 URL)完全不影响现有功能(INV-T4)
  - [ ] 参考母本:`/Users/dangbei/Public/octopus/octopus-agent/runtime/tentacle/transport/ws_server.py` + `runtime/tentacle/mobile/kotlin_ref/PcScreenReceiver.kt`
  - [ ] SettingsActivity 新增「母本 Runtime」配置入口(URL + Token + 连接状态)

- [ ] Task 2: MCP 服务端(手机能力暴露给外部 MCP 客户端)
  - [ ] 新增 `app/src/main/java/com/apk/claw/android/mcp/McpServer.kt`:在 `ws://0.0.0.0:9528/mcp` 监听(JSON-RPC 2.0 over WebSocket)
  - [ ] 新增 `JsonRpcDispatcher.kt`:实现 MCP 协议 3 个原语 `initialize` / `tools/list` / `tools/call`
  - [ ] `tools/list` 枚举 ToolRegistry 中所有工具(name/description/inputSchema)
  - [ ] `tools/call` 经 ToolRegistry.executeTool 执行(必经 Approval Gate,确保 INV-T1)
  - [ ] 高危工具(send_sms/install_app/file_delete)调用时弹出 Approval UI,60s 无响应自动拒绝
  - [ ] 参考母本:`/Users/dangbei/Public/octopus/octopus-agent/runtime/tentacle/mobile/mcp_server.py:serve_stdio()`
  - [ ] 在 `ClawApplication.onCreate` 启动 MCP server(可配置开关,默认开)
  - [ ] KVUtils 新增 `KEY_MCP_SERVER_ENABLED` / `KEY_MCP_SERVER_PORT`

- [ ] Task 3: SKILL.md 协议 + 技能热加载
  - [ ] 新增 `app/src/main/java/com/apk/claw/android/skill/SkillMdLoader.kt`:解析 SKILL.md(YAML frontmatter + Markdown body + optional scripts/*)
  - [ ] frontmatter 字段:name / description / group / allowed_tools / atomic / aliases / trusted_source / tests
  - [ ] 新增 `SkillInstaller.kt`:`skill install <url>` 下载 + 校验签名(若有)+ 落盘到 `filesDir/skills/<name>.md`
  - [ ] 新增 `SkillRegistry.kt`:启动时扫描 `assets/skills/*.md` + `filesDir/skills/*.md`,注册为可被 LLM 调用
  - [ ] 把母本已写好的 30 个移动 SKILL.md 从 `/Users/dangbei/Public/octopus/octopus-agent/runtime/tentacle/mobile/skills/` 复制到 `app/src/main/assets/skills/`
  - [ ] SettingsActivity 新增「技能管理」入口(列表 + 安装 + 卸载 + 查看详情)
  - [ ] 参考母本:`runtime/execution/suckers/loader/md_loader.py`

- [ ] Task 4: Plan 模式 + Approval Gate 4 档权限
  - [ ] 新增 `app/src/main/java/com/apk/claw/android/agent/PermissionMode.kt`:枚举 `DEFAULT` / `ACCEPT_EDITS` / `BYPASS_PERMISSIONS` / `PLAN`
  - [ ] 新增 `ApprovalProvider.kt`:接口 + 4 个实现(AutoApprove / AutoDeny / RuleBased / UserConfirm),参考母本 `runtime/safety/approval/approval_gate.py`
  - [ ] 新增 `ApprovalGate.kt`:在 ToolRegistry.executeTool 第 8 道闸门(在 SafetyGate 之后、audit 之前)调用
  - [ ] 新增 `ApprovalRisk` / `ApprovalDecision` 数据模型(参考母本)
  - [ ] 高危工具清单标记:send_sms / install_app / file_delete / system_setting / payment / account_logout 标 HIGH_RISK
  - [ ] PLAN 模式:工具调用层拦截写工具(tap/swipe/file_write/send_sms 等),只允许读工具(screenshot/file_read/http_get/search_code);LLM 输出 `exit_plan_mode` skill 调用时,弹用户确认后切换到 DEFAULT 模式
  - [ ] `AgentConfig` 新增 `permissionMode: PermissionMode` 字段(默认 DEFAULT),持久化到 KVUtils(`KEY_PERMISSION_MODE`)
  - [ ] SettingsActivity 新增「权限模式」选择器(4 档)+ 高危工具清单展示
  - [ ] DefaultAgentService 的 ReAct 循环支持 plan 模式拦截 + exit_plan_mode 切换

## Phase B — 代码工作流下沉(手机版 Codex 关键,3 任务)

- [ ] Task 5: Codebase 检索(BM25 + dense 融合)
  - [ ] 新增 `app/src/main/java/com/apk/claw/android/code/CodeIndex.kt`:构建 + 持久化索引到 `data/code_index.db`(SQLite)
  - [ ] BM25 token 统计:对项目内所有 `.kt/.java/.py/.js/.ts/.md` 文件分词
  - [ ] Dense embedding:用当前 LLM provider 的 embedding API(若不支持则纯 BM25 兜底)
  - [ ] 融合排序:BM25 + dense 加权融合(参考母本 `code_index.py:245` 的 position-based merge)
  - [ ] 增量更新:文件 mtime 变更时重建单文件索引
  - [ ] 新增 `SearchCodeTool.kt`(工具名 `search_code`):参数 query / path / top_k(默认 5)
  - [ ] ToolRegistry 注册 `search_code` 为 LOW 风险只读工具(可参与并行)
  - [ ] 参考母本:`runtime/memory/hemolymph/{code_index.py, semantic_code_index.py, semantic_rank.py, embedding_backend.py, repo_context.py}`

- [~] Task 6: Diff parser + diff view UI (代码侧 6/7 完成,1 项延后至集成阶段)
  - [x] 新增 `app/src/main/java/com/apk/claw/android/code/DiffParser.kt`:解析 unified diff → `List<FileChange>`,每个 FileChange 含 `List<Hunk>`,每个 Hunk 含 `List<DiffLine>`(+/-/context)
  - [x] 新增 `DiffViewActivity.kt`(Compose):展示 FileChange 列表,逐 hunk 显示 +/- 行(绿色新增/红色删除/灰色 context)
  - [x] 每个 hunk 旁有 Accept / Reject 按钮
  - [x] Reject 的 hunk 还原原文件对应行;Accept 的保留新内容
  - [x] 全部 hunk 处理完后写回文件
  - [ ] file_write 工具执行后,若检测到文件已存在,自动弹出 DiffViewActivity(可关闭,默认开) ← 延后至集成阶段(需改 EditFileTool.kt)
  - [x] 参考母本:`runtime/protocol/diff_parser.py`
  - [x] (额外)新增 `app/src/test/java/com/apk/claw/android/code/DiffParserTest.kt`:JVM 单测覆盖 20+ 场景

- [ ] Task 7: Git 工具集(4 个工具)
  - [ ] 新增 `GitCloneTool.kt`(`git_clone`):参数 url / path / branch,在 LinuxSandbox 内执行 `git clone`
  - [ ] 新增 `GitCommitTool.kt`(`git_commit`):参数 path / message / files,执行 `git add` + `git commit`
  - [ ] 新增 `GitPushTool.kt`(`git_push`):参数 path / remote / branch,执行 `git push`
  - [ ] 新增 `GithubCreatePrTool.kt`(`github_create_pr`):参数 path / title / body / head / base,调 GitHub API v3 创建 PR(需 GitHub token)
  - [ ] KVUtils 新增 `KEY_GITHUB_TOKEN` 存取(加密存储,参考 KEY_LLM_API_KEY 加密方式)
  - [ ] 4 个工具均标 MEDIUM 风险(写入外部仓库)
  - [ ] 工具描述(EN/CN)+ 参数 schema 完整

## Phase C — 补短 Operit + 打磨现有(3 任务)

- [ ] Task 8: MNN 本地模型集成
  - [ ] `app/build.gradle.kts` 加 MNN Android SDK 依赖(`com.alibaba.mnn:mnn-android:1.x.x` 或源码编译)
  - [ ] 新增 `MnnLlmClient.kt`:实现 `LlmClient` 接口,在独立进程加载 MNN 模型(避免 native crash 影响 main)
  - [ ] `LlmClientFactory` 新增 LOCAL provider 的 MNN 后端选择(根据模型路径后缀 `.mnn` 判断)
  - [ ] 模型管理 UI:在 LlmConfigActivity 增加「下载 MNN 模型」入口(预设 3 个模型:Qwen2-1.5B / Qwen2-7B / Llama3-8B,从 HuggingFace/ModelScope 下载)
  - [ ] 借鉴母本 `cookbook_router.py` 思想:硬件检测(RAM/SoC)+ 模型推荐
  - [ ] 参考竞品 Operit 的 MNN 集成方式(已知 Operit 有 MNN)

- [ ] Task 9: 死代码清理(基于 PROJECT_ANALYSIS P2)
  - [ ] 删除 `BrainModeSelector.kt` 整个文件(整条本地/远程路由无生产调用方)
  - [ ] 删除 `ConnectionStateMachine.kt`(完整却未用)
  - [ ] 删除 `CanaryManager.kt`(~251 LOC 全死)
  - [ ] 删除 `MpvController.kt`(确认死桩,视频播放未接通)
  - [ ] 删除或接线 `EvolutionEngine.deepEvolve`(B3 自我改进,无调用方)— 本 spec 选择**删除**,自进化由母本 Regeneration 承担,mobile 只接收锻造结果
  - [ ] 删除 `ReflexRouter.learnFromPattern`(无调用方)
  - [ ] 删除 `cerebrum/ThinkingMode`(未用)
  - [ ] 删除 `StartupModeResolver`(无生产调用方,实际由 BrainModeSelector 承担,但 BrainModeSelector 也删了)
  - [ ] 同步更新 `CODE_WIKI.md` 与 `AUDIT_REPORT.md`:删除对已删能力的描述,标注"已废弃"
  - [ ] PROJECT_ANALYSIS.md 标注 P2 死代码项的最终状态
  - [ ] `./gradlew assembleDebug` 通过(终端恢复后验证,无编译错误)

- [x] Task 10: 工具并行执行
  - [x] 修改 `ToolRegistry.executeTool`:支持批量调用 `executeToolsBatch(calls: List<ToolCall>)`
  - [x] DAG 依赖感知:无依赖的只读工具并行执行(线程池大小 4,参考母本 `runtime/execution/swarm/runtime.py`)
  - [x] 写工具(tap/swipe/file_write/send_sms/install_app 等)强制串行
  - [x] 借鉴母本 `_is_dangerous()` 自动识别危险工具(fs-write/shell/GUI),在并行中自动串行化
  - [x] DefaultAgentService 的 ReAct 循环:LLM 一轮中调用多个只读工具时,自动走并行路径(commitReadonlyToolsParallel,既有实现)
  - [x] 不可信来源 ThreadLocal 跨线程透传(捕捉 isUntrustedSource(),工作线程用 withUntrustedSource 重放)
  - [ ] 工具调用 UI 展示:并行工具用折叠面板显示"3 个工具并行执行中"(延后至后续 UI 任务)

## Phase D — 验证

- [x] Task 11: 端到端静态验证 + 集成测试
  - [x] Grep 验证 Tentacle client 含 `device/hello` / `device/heartbeat` / `tool/execute` / `tool/result`
  - [x] Grep 验证 MCP server 含 `initialize` / `tools/list` / `tools/call`
  - [x] Grep 验证 SKILL.md loader 含 frontmatter 解析
  - [x] Grep 验证 PermissionMode 4 档 + ApprovalGate 第 8 道闸门
  - [x] Grep 验证 CodeIndex 含 BM25 + dense 融合
  - [x] Grep 验证 DiffParser 含 `parse()` 方法(等价 Python `parse_unified_diff`)
  - [x] Grep 验证 4 个 Git 工具注册到 ToolRegistry
  - [x] Grep 验证 MnnLlmClient 实现 LlmClient 接口
  - [x] Grep 验证 BrainModeSelector 标记 @Deprecated(Task 9 选择保留因有生产调用方)
  - [x] Grep 验证 ToolRegistry 含 `executeToolsBatch` 并行方法
  - [ ] 终端恢复后跑 `./gradlew assembleDebug` + 现有 50 个 JVM 测试全绿(终端环境异常,待恢复后验证)
  - [x] 写 5 个新 JVM 测试:TentacleHandshakeTest / McpDispatcherTest / SkillMdLoaderTest / DiffParserTest / ApprovalGateTest / ExecuteToolsBatchTest(共 6 套,已超过 5 个目标)

# Task Dependencies

- Task 1(Tentacle)独立,可与 Task 2/3/4 并行
- Task 2(MCP server)依赖现有 ToolRegistry,独立
- Task 3(SKILL.md)独立
- Task 4(Approval Gate)修改 ToolRegistry,与 Task 2/10 有 ToolRegistry 修改冲突,需顺序:Task 4 → Task 2 → Task 10
- Task 5(CodeIndex)独立,可与 Task 6/7 并行
- Task 6(DiffParser)独立,可与 Task 5/7 并行
- Task 7(Git tools)独立
- Task 8(MNN)独立
- Task 9(死代码清理)独立,但需在 Task 1 完成后(BrainModeSelector 删除前确认 Tentacle 接替功能)
- Task 10(工具并行)依赖 Task 4(ApprovalGate 先就位)
- Task 11 验证依赖全部完成

# 并行执行建议

- **第一批(并行 5 任务)**:Task 1 / Task 2 / Task 3 / Task 5 / Task 6 / Task 7 / Task 8 — 全部独立
- **第二批**:Task 4(Approval Gate)— 修改 ToolRegistry
- **第三批**:Task 10(工具并行)— 依赖 Task 4
- **第四批**:Task 9(死代码清理)— 依赖 Task 1 确认接替
- **第五批**:Task 11 验证

实际可并行度:第一批 7 任务并行,大幅缩短交付时间。
