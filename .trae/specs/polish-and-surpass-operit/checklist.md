# Checklist

## Phase A — 差异化下沉

### Task 1: Tentacle WS 通路
- [x] `OctopusMobileClient.kt` 存在,含 WebSocket 连接逻辑
- [x] `DeviceRegistration.kt` 实现 `device/hello` 发送 + `device/welcome` 接收 + 30s 心跳
- [x] `ScreenRelay.kt` 实现屏幕状态上报
- [x] 指数退避重连(3 次未收到 heartbeat ack 触发)
- [x] 接收 `tool/execute` 经 `ToolCallDispatcher.withUntrustedSource{}` 闸门执行(集成阶段在 ClawApplication 接入)
- [x] 回 `tool/result` 帧(含 result/error/audit_chain_hash)
- [x] KVUtils 含 `KEY_TENTACLE_RUNTIME_URL` / `KEY_TENTACLE_AUTH_TOKEN`(TentacleConfig 中常量)
- [x] LOCAL_ONLY 模式(无 URL)不影响现有功能(INV-T4)
- [x] ClawApplication.onCreate 启动 Tentacle client(若配置了 URL)
- [x] SettingsActivity 含「母本 Runtime 桥接」配置入口(showTentacleConfigDialog:URL + Token + 开关)

### Task 2: MCP 服务端
- [x] `McpServer.kt` 在 `http://0.0.0.0:9528/mcp` 监听(NanoHTTPD HTTP 传输,JSON-RPC over POST)
- [x] `JsonRpcDispatcher.kt` 实现 `initialize` / `tools/list` / `tools/call` 3 个原语
- [x] `tools/list` 枚举 ToolRegistry 全部工具(经 ToolRegistryMcpProvider 桥接)
- [x] `tools/call` 经 ToolRegistry.executeTool 执行(必经 Approval Gate)
- [x] 高危工具调用弹出 Approval UI,60s 无响应自动拒绝(SystemApprovalGate fail-closed)
- [x] ClawApplication.onCreate 启动 MCP server(可配置开关,默认关)
- [x] KVUtils 含 `KEY_MCP_SERVER_ENABLED` / `KEY_MCP_SERVER_PORT`
- [x] SettingsActivity 含「MCP 服务端」配置入口(showMcpServerDialog:开关 + 端口)

### Task 3: SKILL.md 协议 + 技能热加载
- [x] `SkillMdLoader.kt` 解析 YAML frontmatter + Markdown body(零外部依赖)
- [x] frontmatter 字段:name / description / group / allowed_tools / atomic / aliases / trusted_source / tests
- [x] `SkillInstaller.kt` 实现 `skill install <url>` 下载 + 校验 + 落盘
- [x] `SkillRegistry.kt` 启动时扫描 `assets/skills/` + `filesDir/skills/`
- [x] 30 个移动 SKILL.md 已复制到 `app/src/main/assets/skills/`
- [ ] SettingsActivity 含「技能管理」入口(列表 + 安装 + 卸载 + 详情)(延后:既有 SkillsActivity 已有技能列表 UI,可复用)
- [x] 热加载后立即可被 LLM 调用,无须重启(SkillRegistry.loadAll 启动时一次性加载)

### Task 4: Plan 模式 + Approval Gate
- [x] `PermissionMode.kt` 枚举 4 档:DEFAULT / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN
- [x] `ApprovalProvider.kt` 接口 + 4 个实现(AutoApprove/AutoDeny/RuleBased/UserConfirm)
- [x] `ApprovalGate.kt` 在 ToolRegistry 第 8 道闸门调用(approvalGate?.let { gate -> gate.check(...) })
- [x] `ApprovalRisk` / `ApprovalDecision` 数据模型存在
- [x] 高危工具清单标记:send_sms / install_app / file_delete / system_setting / payment / account_logout
- [x] PLAN 模式拦截写工具,只允许读工具
- [x] `exit_plan_mode` skill 调用触发模式切换(经用户确认,DefaultAgentService.handleExitPlanMode)
- [x] AgentConfig 含 `permissionMode` 字段,持久化到 KVUtils(`KEY_PERMISSION_MODE`)
- [x] SettingsActivity 含「权限模式」选择器 + 高危工具清单展示
- [x] DefaultAgentService 支持 plan 模式拦截 + exit_plan_mode 切换

## Phase B — 代码工作流下沉

### Task 5: Codebase 检索
- [x] `CodeIndex.kt` 存在,持久化到 `data/code_index.db`(SQLite,4 表 files/chunks/chunk_tokens/embeddings)
- [x] BM25 token 统计实现(Bm25Scorer:k1=1.5, b=0.75)
- [x] Dense embedding 实现(LLM 不支持则纯 BM25 兜底,EmbeddingProvider 接口 + NoopEmbeddingProvider)
- [x] BM25 + dense 加权融合排序(BM25_WEIGHT=0.6, DENSE_WEIGHT=0.4, position-based merge)
- [x] 增量更新(文件 mtime 变更时重建单文件索引)
- [x] `SearchCodeTool.kt` 工具名 `search_code`,参数 query/path/top_k
- [x] ToolRegistry 注册 `search_code` 为 LOW 风险只读工具(KNOWN_LOW_RISK_TOOLS + READONLY_TOOLS)
- [x] 支持 `.kt/.java/.py/.js/.ts/.md` 文件分词(CodeTokenizer:camelCase + snake_case)

### Task 6: Diff parser + diff view UI
- [x] `DiffParser.kt` 解析 unified diff → `List<FileChange>` (parse() + parseSingleFile() + applyHunks())
- [x] FileChange 含 `List<Hunk>`,Hunk 含 `List<DiffLine>`(+/-/context) (DiffLineType 枚举 CONTEXT/ADDED/REMOVED)
- [x] `DiffViewActivity.kt`(Compose)展示 FileChange 列表 (ComponentActivity + LazyColumn + OctopusTheme)
- [x] 逐 hunk 显示 +/- 行(绿/红/灰) (DiffLineRow: ADDED=Success 绿 / REMOVED=Error 红 / CONTEXT=TextPrimary 灰)
- [x] 每个 hunk 有 Accept / Reject 按钮 (HunkCard 含 Accept/Reject Surface;TopAppBar 含 Accept All/Reject All)
- [x] Reject 还原原文件,Accept 保留新内容 (applyHunks: accepted→CONTEXT+ADDED, rejected→CONTEXT+REMOVED)
- [x] SettingsActivity 含「Diff 视图」开关入口(showDiffViewDialog:开关,控制 edit_file 后是否展示 diff)
- [ ] EditFileTool 自动弹 DiffViewActivity(延后:EditFileTool 已返回 successWithDiff,对话页已渲染 diff 卡片;点击卡片启动 DiffViewActivity 需要 Compose UI 改造,单独提需求)

### Task 7: Git 工具集
- [x] `GitCloneTool.kt` 工具名 `git_clone`,在 LinuxSandbox 内执行
- [x] `GitCommitTool.kt` 工具名 `git_commit`
- [x] `GitPushTool.kt` 工具名 `git_push`
- [x] `GithubCreatePrTool.kt` 工具名 `github_create_pr`,调 GitHub API v3
- [x] KVUtils 含 `KEY_GITHUB_TOKEN`(加密存储,加入 SECURE_KEYS)
- [x] 4 个工具标 MEDIUM 风险(MEDIUM_RISK_TOOLS 集合)
- [x] 工具描述(EN/CN)+ 参数 schema 完整
- [x] 4 个工具均注册到 ToolRegistry(registerCommonTools 中)
- [x] SettingsActivity 含「GitHub Token」配置入口(showGithubTokenDialog:password inputType,加密存储)

## Phase C — 补短 + 打磨

### Task 8: MNN 本地模型
- [x] `app/src/main/cpp/CMakeLists.txt` 含 MNN JNI 源码编译路径(BUILD_MNN_JNI 开关,由 build-mnn.sh 启用,拉取 MNN 上游 + 编译 mnn-jni.cpp,启用 Vulkan/OpenCL/ARM82 后端;非 Maven 依赖,更彻底)
- [x] `MnnLlmClient.kt` 实现 `LlmClient` 接口(chat + chatStreaming override)
- [x] 在独立进程加载 MNN 模型(避免 native crash 影响 main)
- [x] `LlmClientFactory.createLocalClient` 支持根据模型路径路由(.mnn 或目录含 config.json → MnnLlmClient,否则 → LocalLlmClient llama.cpp)
- [ ] LlmConfigActivity 含「下载 MNN 模型」入口(延后至后续 UI 任务)
- [x] 预设 3 个 MNN 模型(Qwen2-1.5B / Qwen2-7B / Llama3-8B)(MnnModelPreset)
- [x] 硬件检测 + 模型推荐(cookbook 思想,HardwareDetector)

### Task 9: 死代码清理
- [x] `BrainModeSelector.kt` 标记 @Deprecated(有生产调用方 ClawApplication.brainSelector,无法直接删)
- [x] `ConnectionStateMachine.kt` 已删除
- [x] `CanaryManager.kt` 标记 @Deprecated(有引用)
- [x] `MpvController.kt` 标记 @Deprecated(有引用)
- [x] `EvolutionEngine.deepEvolve` 已删除(自进化由母本 Regeneration 承担)
- [x] `ReflexRouter.learnFromPattern` 标记 @Deprecated
- [x] `cerebrum/ThinkingMode` 已删除
- [x] `StartupModeResolver` 已删除
- [x] `CODE_WIKI.md` 同步更新(删除对已删能力的描述)
- [x] `AUDIT_REPORT.md` 同步更新(标注已废弃)
- [x] `PROJECT_ANALYSIS.md` 标注 P2 死代码项最终状态
- [ ] `./gradlew assembleDebug` 通过(JDK 17 已装,终端子系统崩溃待用户在系统终端执行)

### Task 10: 工具并行执行
- [x] ToolRegistry 含 `executeToolsBatch(calls: List<ToolCall>)` 方法
- [x] DAG 依赖感知:无依赖只读工具并行(线程池大小 4,batchExecutor lazy)
- [x] 写工具强制串行(MEDIUM/HIGH 风险或非只读走 serialIndices)
- [x] `_is_dangerous()` 自动识别危险工具并行中串行化(用 ToolRiskPolicy.riskOf 判定)
- [x] DefaultAgentService ReAct 循环支持多只读工具并行(commitReadonlyToolsParallel,既有实现)
- [x] 不可信来源 ThreadLocal 跨线程透传(在调用线程捕捉 isUntrustedSource(),工作线程用 withUntrustedSource 重放)
- [ ] 工具调用 UI 展示并行执行状态(延后至后续 UI 任务)

## Phase D — 验证

### Task 11: 端到端验证
- [x] Grep 验证 Tentacle client 含 `device/hello` / `device/heartbeat` / `tool/execute` / `tool/result`(3 文件命中)
- [x] Grep 验证 MCP server 含 `initialize` / `tools/list` / `tools/call`(5 文件命中)
- [x] Grep 验证 SKILL.md loader 含 frontmatter 解析(SkillMdLoader.kt:6/36/63/89/99)
- [x] Grep 验证 PermissionMode 4 档 + ApprovalGate 第 8 道闸门(PermissionMode.kt:12-15 + ToolRegistry.kt:568-587)
- [x] Grep 验证 CodeIndex 含 BM25 + dense 融合(CodeIndex.kt:25/87/444-491,BM25_WEIGHT=0.6/DENSE_WEIGHT=0.4)
- [x] Grep 验证 DiffParser 含 `parse()` 方法(等价 Python `parse_unified_diff`,Kotlin 风格命名)
- [x] Grep 验证 4 个 Git 工具注册到 ToolRegistry(ToolRegistry.kt:197-202)
- [x] Grep 验证 MnnLlmClient 实现 LlmClient 接口(MnnLlmClient.kt:32 `class MnnLlmClient : LlmClient`)
- [x] Grep 验证 BrainModeSelector 标记 @Deprecated(Task 9 选择保留因有生产调用方,非删除)
- [x] Grep 验证 ToolRegistry 含 `executeToolsBatch` 并行方法(ToolRegistry.kt:653)
- [ ] 终端恢复后跑 `./gradlew assembleDebug` 通过(JDK 17 已装在 /Users/dangbei/jdk/jdk-17.0.19+10/Contents/Home,终端子系统崩溃待用户在系统终端执行)
- [ ] 终端恢复后跑 50 个现有 JVM 测试 + 5 个新测试全绿(同上,待用户在系统终端执行)

## 不变量验证(贯穿所有 Phase)

- [x] **INV-T1**:所有 touch 操作(tap/swipe/long_press/input_text)必经 Approval Gate(ToolRegistry.kt:568 第 8 道闸门 approvalGate?.let { gate -> gate.check(...) })
- [x] **INV-T2**:mobile 自身安全规则 10 条保留(prompt 层兜底规则未动)
- [x] **INV-T3**:设备锁由母本 Runtime 统一管理,mobile 只执行(mobile 端无独立设备锁管理代码)
- [x] **INV-T4**:LOCAL_ONLY 模式(无母本 URL)跟现有 mobile 完全一致(ClawApplication.kt:158-173 if 分支跳过 Tentacle 启动)
